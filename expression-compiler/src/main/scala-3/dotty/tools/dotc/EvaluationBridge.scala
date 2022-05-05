package dotty.tools.dotc

import java.nio.file.Path
import java.util.function.Consumer
import java.{util => ju}
import collection.JavaConverters._
import scala.util.control.NonFatal

class EvaluationBridge:
  def run(
      expressionDir: Path,
      expressionClassName: String,
      valuesByNameIdentName: String,
      callPrivateMethodName: String,
      classPath: String,
      code: String,
      line: Int,
      expression: String,
      defNames: ju.Set[String],
      errorConsumer: Consumer[String],
      timeoutMillis: Long
  ): Boolean =
    val settings = List(
      "-d",
      expressionDir.toString,
      "-classpath",
      classPath,
      "-Yskip:pureStats"
      // "-Vprint:insert-expression,typer,extract-expression,extract-defs,insert-extracted,adapt-expression,cleanup"
    )
    val evaluationDriver =
      EvaluationDriver(
        settings,
        expressionClassName,
        line,
        expression,
        defNames.asScala.toSet
      )
    try
      val errors = evaluationDriver.run(code)
      val error = errors.headOption.map(_.msg.message)
      error.foreach(errorConsumer.accept)
      error.isEmpty
    catch
      case NonFatal(t) =>
        t.printStackTrace()
        errorConsumer.accept(t.getMessage)
        false