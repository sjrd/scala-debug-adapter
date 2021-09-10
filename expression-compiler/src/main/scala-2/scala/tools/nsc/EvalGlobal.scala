package scala.tools.nsc

import scala.reflect.internal.util.BatchSourceFile
import scala.tools.nsc.reporters.Reporter
import scala.tools.nsc.transform.{Transform, TypingTransformers}

private[nsc] class EvalGlobal(
    settings: Settings,
    reporter: Reporter,
    val line: Int,
    val expression: String,
    defNames: Set[String],
    expressionClassName: String,
    valuesByNameIdentName: String
) extends Global(settings, reporter) {
  private var valOrDefDefs: Map[TermName, ValOrDefDef] = Map()
  private var extractedExpression: Tree = _
  private var expressionOwners: List[Symbol] = _

  override protected def computeInternalPhases(): Unit = {
    super.computeInternalPhases()

    addToPhasesSet(
      new InsertExpression,
      "Insert expression which is going to be evaluated"
    )
    addToPhasesSet(
      new GenerateExpression,
      "Generate the final form of the expression"
    )
  }

  class InsertExpression extends Transform with TypingTransformers {
    override val global: EvalGlobal.this.type = EvalGlobal.this
    override val phaseName: String = "insertExpression"
    override val runsAfter: List[String] = List("parser")
    override val runsRightAfter: Option[String] = None

    override protected def newTransformer(
        unit: CompilationUnit
    ): Transformer = {
      if (unit.source.file.name == "<source>") new InsExprTransformer()
      else noopTransformer
    }

    /**
     * This transformer:
     * - inserts the expression at the line of the breakpoint, which allows us to extract all needed defs, including implicit ones too.
     * Thanks to that, evaluation of expression is done in the context of a breakpoint and logic behaves the same way like the
     * normal code would behave.
     * - inserts the expression class in the same package as the expression. This allows us to call package private methods without any trouble.
     */
    class InsExprTransformer extends Transformer {
      private val expressionClassSource =
        s"""class $expressionClassName {
           |  def evaluate(names: Array[Any], values: Array[Any]) = {
           |    val $valuesByNameIdentName = names.map(_.asInstanceOf[String]).zip(values).toMap
           |    $valuesByNameIdentName
           |    ()
           |  }
           |}
           |""".stripMargin

      private val parsedExpression = parseExpression(expression)
      private val parsedExpressionClass = parseExpressionClass(
        expressionClassSource
      )

      private var expressionInserted = false

      override def transform(tree: Tree): Tree = tree match {
        case tree if tree.pos.line == line =>
          expressionInserted = true
          atPos(tree.pos)(Block(List(parsedExpression), tree))
        case tree: PackageDef =>
          val transformed = super.transform(tree).asInstanceOf[PackageDef]
          if (expressionInserted) {
            expressionInserted = false
            atPos(tree.pos)(
              treeCopy.PackageDef(
                transformed,
                transformed.pid,
                transformed.stats :+ parsedExpressionClass
              )
            )
          } else {
            transformed
          }
        case _ =>
          super.transform(tree)
      }

      private def parseExpression(expression: String): Tree = {
        // It needs to be wrapped because it's not possible to parse single expression
        val wrappedExpressionSource =
          s"""object Expression {
             |  $expression
             |}
             |""".stripMargin
        val parsedWrappedExpression =
          parse("<wrapped-expression>", wrappedExpressionSource)
            .asInstanceOf[PackageDef]
        parsedWrappedExpression.stats.head
          .asInstanceOf[ModuleDef]
          .impl
          .body
          .last
          .setPos(NoPosition)
      }

      private def parseExpressionClass(source: String): Tree = {
        val parsedExpressionClass =
          parse("<expression>", source).asInstanceOf[PackageDef]
        parsedExpressionClass.stats.head.setPos(NoPosition)
      }

      private def parse(sourceName: String, source: String): Tree = {
        newUnitParser(
          new CompilationUnit(new BatchSourceFile(sourceName, source))
        ).parse()
      }
    }
  }

  /**
   * This transformer extracts transformed expression, extracts all defs (local variables, fields, arguments, etc.)
   * and transforms `Expression` class that was inserted by the [[InsertExpression]] in the following way:
   * - creates local variables that are equivalent to accessible values,
   * - inserts extracted expression at the end of the `evaluate` method,
   * - modifies the return type of `evaluate` method.
   */
  class GenerateExpression extends Transform with TypingTransformers {
    override val global: EvalGlobal.this.type = EvalGlobal.this
    override val phaseName: String = "generateExpression"
    override val runsAfter: List[String] = List("delambdafy")
    override val runsRightAfter: Option[String] = None

    override protected def newTransformer(
        unit: CompilationUnit
    ): Transformer = {
      if (unit.source.file.name == "<source>") new ExprEvalTransformer(unit)
      else noopTransformer
    }

    class ExprEvalTransformer(unit: CompilationUnit) extends Transformer {
      override def transform(tree: Tree): Tree = tree match {
        case _ =>
          new ExpressionExtractor().traverse(tree)
          new DefExtractor().traverse(tree)
          new GenExprTransformer(unit).transform(tree)
      }
    }

    /**
     * Extracts transformed expression that was inserted by the [[InsertExpression]] at the line of the breakpoint.
     */
    class ExpressionExtractor extends Traverser {
      override def traverse(tree: Tree): Unit = tree match {
        // Don't extract expression from the Expression class
        case tree: ClassDef if tree.name.decode == expressionClassName =>
        // ignore
        case tree: Block if tree.pos.line == line =>
          expressionOwners = currentOwner.ownerChain
          extractedExpression = tree.stats.head
        case _ if tree.pos.line == line =>
          expressionOwners = currentOwner.ownerChain
          extractedExpression = tree
        case _ =>
          super.traverse(tree)
      }
    }

    /**
     * Extracts all defs (local variables, fields, arguments, etc.) that are accessible in the line of the breakpoint.
     */
    class DefExtractor extends Traverser {
      override def traverse(tree: Tree): Unit = tree match {
        // Don't extract expression from the Expression class
        case tree: ClassDef if tree.name.decode == expressionClassName =>
        // ignore
        case tree: ValDef
            if expressionOwners.contains(tree.symbol.owner) && defNames
              .contains(tree.name.decode) =>
          valOrDefDefs += (tree.name -> tree)
          super.traverse(tree)
        case tree: DefDef
            if expressionOwners.contains(
              tree.symbol.owner
            ) && tree.symbol.isGetter && defNames.contains(tree.name.decode) =>
          valOrDefDefs += (tree.name -> tree)
          super.traverse(tree)
        case _ => super.traverse(tree)
      }
    }

    /**
     * Transforms `Expression` class:
     * - for every extracted def copy its value to the local variable in the method `evaluate` in the form:
     * `val foo: String = valuesByName("foo").asInstanceOf[String]`,
     * - inserts the expression (the one that was inserted at the line of the breakpoint by [[InsertExpression]])
     * at the end of `evaluate` method,
     * - replaces symbols in the expression with their local equivalents
     * - modifies return type of the `evaluate` method.
     */
    class GenExprTransformer(unit: CompilationUnit)
        extends TypingTransformer(unit) {

      import definitions._
      import typer._

      private var valuesByNameIdent: Ident = _

      override def transform(tree: Tree): Tree = tree match {
        // Don't transform class different than Expression
        case tree: ClassDef if tree.name.decode != expressionClassName =>
          tree
        case tree: Ident
            if tree.name == TermName(
              valuesByNameIdentName
            ) && valuesByNameIdent == null =>
          valuesByNameIdent = tree
          EmptyTree
        case tree: DefDef if tree.name == TermName("evaluate") =>
          // firstly, transform the body of the method
          super.transform(tree)

          var tpt: TypeTree = TypeTree()
          val derived = deriveDefDef(tree) { rhs =>
            // we can be sure that `rhs` is an instance of a `Block`
            val block = rhs.asInstanceOf[Block]

            val thisSymbol = expressionOwners
              .find(_.isClass)
              .map(_.asInstanceOf[ClassSymbol])
              .get
            val thisValDef = newThisValDef(tree, thisSymbol)
              .map { case (thisName, thisValDef) =>
                Seq(thisName -> thisValDef)
              }
              .getOrElse(Seq())
            // replace original valDefs with synthesized valDefs with values that will be sent via JDI
            val newValOrDefDefs = valOrDefDefs.map { case (_, valOrDefDef) =>
              newValDef(tree, valOrDefDef)
            } ++ thisValDef

            val symbolsByName = newValOrDefDefs.mapValues(_.symbol).toMap

            // replace symbols in the expression with those from the `evaluate` method
            val newExpression = new ExpressionTransformer(symbolsByName)
              .transform(extractedExpression)

            // create a new body
            val newRhs =
              new Block(block.stats ++ newValOrDefDefs.values, newExpression)

            tpt = TypeTree().copyAttrs(newExpression)
            typedPos(tree.pos)(newRhs).setType(tpt.tpe)
          }
          // update return type of the `evaluate` method
          derived.symbol
            .asInstanceOf[MethodSymbol]
            .modifyInfo(info => {
              val methodType = info.asInstanceOf[MethodType]
              methodType.copy(resultType = tpt.tpe)
            })
          derived.setType(tpt.tpe)
        case _ =>
          super.transform(tree)
      }

      private def newThisValDef(
          owner: DefDef,
          thisSymbol: ClassSymbol
      ): Option[(Name, ValDef)] = {
        if (!defNames.contains("$this")) {
          None
        } else {
          val name = TermName("$this")
          val app =
            Apply(valuesByNameIdent, List(Literal(Constant(name.decode))))
          val tpt = TypeTree().setType(thisSymbol.tpe)
          val casted = mkCast(app, tpt)

          val sym = owner.symbol.newValue(name).setInfo(tpt.tpe)
          val newValDef =
            ValDef(Modifiers(), TermName(name.decode), tpt, casted)
              .setSymbol(sym)
          Some(name -> newValDef)
        }
      }

      private def newValDef(
          owner: DefDef,
          valOrDefDef: ValOrDefDef
      ): (Name, ValDef) = {
        val name = valOrDefDef.name
        val app = Apply(valuesByNameIdent, List(Literal(Constant(name.decode))))
        val tpt = valOrDefDef.tpt.asInstanceOf[TypeTree]
        if (tpt.symbol.isPrimitiveValueClass) {
          val unboxedMethodSym =
            currentRun.runDefinitions.unboxMethod(tpt.symbol)
          val casted = Apply(unboxedMethodSym, app)
          newValDef(owner, name, casted)
        } else {
          val casted = mkCast(app, tpt)
          newValDef(owner, name, casted)
        }
      }

      private def mkCast(app: Apply, tpt: TypeTree) = {
        val tapp = gen.mkTypeApply(
          gen.mkAttributedSelect(app, Object_asInstanceOf).asInstanceOf[Tree],
          List(tpt)
        )
        Apply(tapp, Nil)
      }

      private def newValDef(
          owner: DefDef,
          name: TermName,
          rhs: Tree
      ): (TermName, ValDef) = {
        val tpt = valOrDefDefs(TermName(name.decode)).tpt
        val sym = owner.symbol.newValue(name.toTermName).setInfo(tpt.tpe)
        val newValDef =
          ValDef(Modifiers(), TermName(name.decode), tpt, rhs).setSymbol(sym)
        name -> newValDef
      }
    }

    class ExpressionTransformer(symbolsByName: Map[Name, Symbol])
        extends Transformer {
      override def transform(tree: Tree): Tree = tree match {
        case tree: This =>
          val name = TermName("$this")
          if (symbolsByName.contains(name)) ident(name)
          else super.transform(tree)
        case tree: Ident =>
          val name = tree.name
          if (symbolsByName.contains(name)) ident(name)
          else super.transform(tree)
        case tree: Apply if tree.fun.symbol.isGetter =>
          val fun = tree.fun.asInstanceOf[Select]
          val name = fun.name
          val qualifier = fun.qualifier
          if (qualifier.isInstanceOf[This] && symbolsByName.contains(name))
            ident(name)
          else super.transform(tree)
        case _ =>
          super.transform(tree)
      }

      private def ident(name: Name) = {
        val symbol = symbolsByName(name)
        Ident(name)
          .setSymbol(symbol)
          .setType(symbol.tpe)
          .setPos(symbol.pos)
      }
    }
  }
}