/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt

import java.io.File
import java.lang.ProcessBuilder.Redirect

import scala.sys.process.Process
import OutputStrategy._
import sbt.internal.util.Util
import Util.{ AnyOps, none }

import java.lang.{ ProcessBuilder => JProcessBuilder }

/**
 * Represents a command that can be forked.
 *
 * @param commandName The java-like binary to fork.  This is expected to exist in bin/ of the Java home directory.
 * @param runnerClass If Some, this will be prepended to the `arguments` passed to the `apply` or `fork` methods.
 */
final class Fork(val commandName: String, val runnerClass: Option[String]) {

  /**
   * Forks the configured process, waits for it to complete, and returns the exit code.
   * The command executed is the `commandName` defined for this Fork instance.
   * It is configured according to `config`.
   * If `runnerClass` is defined for this Fork instance, it is prepended to `arguments` to define the arguments passed to the forked command.
   */
  def apply(config: ForkOptions, arguments: Seq[String]): Int = fork(config, arguments).exitValue()

  /**
   * Forks the configured process and returns a `Process` that can be used to wait for completion or to terminate the forked process.
   * The command executed is the `commandName` defined for this Fork instance.
   * It is configured according to `config`.
   * If `runnerClass` is defined for this Fork instance, it is prepended to `arguments` to define the arguments passed to the forked command.
   */
  def fork(config: ForkOptions, arguments: Seq[String]): Process = {
    import config.{ envVars => env, _ }
    val executable = Fork.javaCommand(javaHome, commandName).getAbsolutePath
    val preOptions = makeOptions(runJVMOptions, bootJars, arguments)
    val (classpathEnv, options) = Fork.fitClasspath(preOptions)
    val command = executable +: options

    val environment: List[(String, String)] = env.toList ++
      (classpathEnv map { value =>
        Fork.ClasspathEnvKey -> value
      })
    val jpb = new JProcessBuilder(command.toArray: _*)
    workingDirectory foreach (jpb directory _)
    environment foreach { case (k, v) => jpb.environment.put(k, v) }
    if (connectInput) {
      jpb.redirectInput(Redirect.INHERIT)
      ()
    }
    val process = Process(jpb)

    outputStrategy.getOrElse(StdoutOutput: OutputStrategy) match {
      case StdoutOutput => process.run(connectInput = false)
      case out: BufferedOutput =>
        out.logger.buffer { process.run(out.logger, connectInput = false) }
      case out: LoggedOutput => process.run(out.logger, connectInput = false)
      case out: CustomOutput => (process #> out.output).run(connectInput = false)
    }
  }
  private[this] def makeOptions(
      jvmOptions: Seq[String],
      bootJars: Iterable[File],
      arguments: Seq[String]
  ): Seq[String] = {
    val boot =
      if (bootJars.isEmpty) none[String]
      else
        ("-Xbootclasspath/a:" + bootJars.map(_.getAbsolutePath).mkString(File.pathSeparator)).some
    jvmOptions ++ boot.toList ++ runnerClass.toList ++ arguments
  }
}
object Fork {
  private val ScalacMainClass = "scala.tools.nsc.Main"
  private val ScalaMainClass = "scala.tools.nsc.MainGenericRunner"
  private val JavaCommandName = "java"

  val java = new Fork(JavaCommandName, None)
  val javac = new Fork("javac", None)
  val scala = new Fork(JavaCommandName, Some(ScalaMainClass))
  val scalac = new Fork(JavaCommandName, Some(ScalacMainClass))

  private val ClasspathEnvKey = "CLASSPATH"
  private[this] val ClasspathOptionLong = "-classpath"
  private[this] val ClasspathOptionShort = "-cp"
  private[this] def isClasspathOption(s: String) =
    s == ClasspathOptionLong || s == ClasspathOptionShort

  /** Maximum length of classpath string before passing the classpath in an environment variable instead of an option. */
  private[this] val MaxConcatenatedOptionLength = 5000

  private def fitClasspath(options: Seq[String]): (Option[String], Seq[String]) =
    if (Util.isWindows && optionsTooLong(options))
      convertClasspathToEnv(options)
    else
      (None, options)
  private[this] def optionsTooLong(options: Seq[String]): Boolean =
    options.mkString(" ").length > MaxConcatenatedOptionLength

  private[this] def convertClasspathToEnv(options: Seq[String]): (Option[String], Seq[String]) = {
    val (preCP, cpAndPost) = options.span(opt => !isClasspathOption(opt))
    val postCP = cpAndPost.drop(2)
    val classpathOption = cpAndPost.drop(1).headOption
    val newOptions = if (classpathOption.isDefined) preCP ++ postCP else options
    (classpathOption, newOptions)
  }

  private[sbt] def javaCommand(javaHome: Option[File], name: String): File = {
    val home = javaHome.getOrElse(new File(System.getProperty("java.home")))
    new File(new File(home, "bin"), name)
  }
}
