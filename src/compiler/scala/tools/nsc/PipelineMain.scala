/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.tools.nsc

import java.io.{BufferedOutputStream, File}
import java.lang.Thread.UncaughtExceptionHandler
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

import javax.tools.ToolProvider

import scala.collection.{immutable, mutable}
import scala.concurrent._
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.Ops._
import scala.reflect.internal.pickling.PickleBuffer
import scala.reflect.internal.util.FakePos
import scala.reflect.io.RootPath
import scala.tools.nsc.PipelineMain.{BuildStrategy, Pipeline, Traditional}
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.reporters.{ConsoleReporter, Reporter}
import scala.tools.nsc.util.ClassPath
import scala.util.{Failure, Success, Try}

class PipelineMainClass(label: String, parallelism: Int, strategy: BuildStrategy, argFiles: Seq[Path], useJars: Boolean) {
  private val pickleCacheConfigured = System.getProperty("scala.pipeline.picklecache")
  private val pickleCache: Path = {
    if (pickleCacheConfigured == null) Files.createTempDirectory("scala.picklecache")
    else {
      Paths.get(pickleCacheConfigured)
    }
  }
  private def cachePath(file: Path): Path = {
    val newExtension = if (useJars) ".jar" else ""
    changeExtension(pickleCache.resolve("./" + file).normalize(), newExtension)
  }

  private val strippedAndExportedClassPath = mutable.HashMap[Path, Path]()

  /** Forward errors to the (current) reporter. */
  protected def scalacError(msg: String): Unit = {
    reporter.error(FakePos("scalac"), msg + "\n  scalac -help  gives more information")
  }

  private var reporter: Reporter = _

  private object handler extends UncaughtExceptionHandler {
    override def uncaughtException(t: Thread, e: Throwable): Unit = {
      e.printStackTrace()
      System.exit(-1)
    }
  }

  implicit val executor = ExecutionContext.fromExecutor(new java.util.concurrent.ForkJoinPool(parallelism), t => handler.uncaughtException(Thread.currentThread(), t))
  val fileManager = ToolProvider.getSystemJavaCompiler.getStandardFileManager(null, null, null)
  def changeExtension(p: Path, newExtension: String): Path = {
    val fileName = p.getFileName.toString
    val changedFileName = fileName.lastIndexOf('.') match {
      case -1 => fileName + newExtension
      case n => fileName.substring(0, n) + newExtension
    }
    p.getParent.resolve(changedFileName)
  }

  def registerPickleClassPath[G <: Global](output: Path, data: mutable.AnyRefMap[G#Symbol, PickleBuffer]): Unit = {
    val jarPath = cachePath(output)
    val root = RootPath(jarPath, writable = true)
    Files.createDirectories(root.root)

    val dirs = mutable.Map[G#Symbol, Path]()
    def packageDir(packSymbol: G#Symbol): Path = {
      if (packSymbol.isEmptyPackageClass) root.root
      else if (dirs.contains(packSymbol)) dirs(packSymbol)
      else if (packSymbol.owner.isRoot) {
        val subDir = root.root.resolve(packSymbol.encodedName)
        Files.createDirectories(subDir)
        dirs.put(packSymbol, subDir)
        subDir
      } else {
        val base = packageDir(packSymbol.owner)
        val subDir = base.resolve(packSymbol.encodedName)
        Files.createDirectories(subDir)
        dirs.put(packSymbol, subDir)
        subDir
      }
    }
    val written = new java.util.IdentityHashMap[AnyRef, Unit]()
    try {
      for ((symbol, pickle) <- data) {
        if (!written.containsKey(pickle)) {
          val base = packageDir(symbol.owner)
          val primary = base.resolve(symbol.encodedName + ".sig")
          val writer = new BufferedOutputStream(Files.newOutputStream(primary))
          try {
            writer.write(pickle.bytes, 0, pickle.writeIndex)
          } finally {
            writer.close()
          }
          written.put(pickle, ())
        }
      }
    } finally {
      root.close()
    }
    Files.setLastModifiedTime(jarPath, FileTime.from(Instant.now()))
    strippedAndExportedClassPath.put(output.toRealPath().normalize(), jarPath)
  }


  def writeDotFile(dependsOn: mutable.LinkedHashMap[Task, List[Dependency]]): Unit = {
    val builder = new java.lang.StringBuilder()
    builder.append("digraph projects {\n")
    for ((p, deps) <- dependsOn) {
      //builder.append("  node \"[]").append(p.label).append("\";\n")
      for (dep <- deps) {
        builder.append("   \"").append(p.label).append("\" -> \"").append(dep.t.label).append("\" [")
        if (dep.isMacro) builder.append("label=M")
        else if (dep.isPlugin) builder.append("label=P")
        builder.append("];\n")
      }
    }
    builder.append("}\n")
    val path = Paths.get("projects.dot")
    Files.write(path, builder.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    println("Wrote project dependency graph to: " + path.toAbsolutePath)
  }

  private case class Dependency(t: Task, isMacro: Boolean, isPlugin: Boolean)

  def process(): Boolean = {
    println(s"parallelism = $parallelism, strategy = $strategy")

    reporter = new ConsoleReporter(new Settings(scalacError))

    def commandFor(argFileArg: Path): Task = {
      val ss = new Settings(scalacError)
      val command = new CompilerCommand(("@" + argFileArg) :: Nil, ss)
      Task(argFileArg, command, command.files)
    }

    val projects: List[Task] = argFiles.toList.map(commandFor)
    val numProjects = projects.size
    val produces = mutable.LinkedHashMap[Path, Task]()
    for (p <- projects) {
      produces(p.outputDir) = p
    }
    val dependsOn = mutable.LinkedHashMap[Task, List[Dependency]]()
    for (p <- projects) {
      val macroDeps = p.macroClassPath.flatMap(p => produces.get(p)).toList.filterNot(_ == p).map(t => Dependency(t, isMacro = true, isPlugin = false))
      val pluginDeps = p.pluginClassPath.flatMap(p => produces.get(p)).toList.filterNot(_ == p).map(t => Dependency(t, isMacro = false, isPlugin = true))
      val classPathDeps = p.classPath.flatMap(p => produces.get(p)).toList.filterNot(_ == p).filterNot(p => macroDeps.exists(_.t == p)).map(t => Dependency(t, isMacro = false, isPlugin = false))
      dependsOn(p) = classPathDeps ++ macroDeps ++ pluginDeps
    }
    val dependedOn: Set[Task] = dependsOn.valuesIterator.flatten.map(_.t).toSet
    val externalClassPath = projects.iterator.flatMap(_.classPath).filter(p => !produces.contains(p) && Files.exists(p)).toSet

    if (strategy != Traditional) {
      val exportTimer = new Timer
      exportTimer.start()
      for (entry <- externalClassPath) {
        val extracted = cachePath(entry)
        val sourceTimeStamp = Files.getLastModifiedTime(entry)
        if (Files.exists(extracted) && Files.getLastModifiedTime(extracted) == sourceTimeStamp) {
          // println(s"Skipped export of pickles from $entry to $extracted (up to date)")
        } else {
          PickleExtractor.process(entry, extracted)
          Files.setLastModifiedTime(extracted, sourceTimeStamp)
          println(s"Exported pickles from $entry to $extracted")
          Files.setLastModifiedTime(extracted, sourceTimeStamp)
        }
        strippedAndExportedClassPath(entry) = extracted
      }
      exportTimer.stop()
      println(f"Exported external classpath in ${exportTimer.durationMs}%.0f ms")
    }

    writeDotFile(dependsOn)

    val timer = new Timer
    timer.start()

    def awaitAll(fs: Seq[Future[_]]): Future[_] = {
      val done = Promise[Any]()
      val allFutures = projects.flatMap(_.futures)
      val count = allFutures.size
      val counter = new AtomicInteger(count)
      val handler = (a: Try[_]) => a match {
        case f @ Failure(_) =>
          done.complete(f)
        case Success(_) =>
          val remaining = counter.decrementAndGet()
          if (remaining == 0) done.success(())
      }

      allFutures.foreach(_.onComplete(handler))
      done.future
    }

    def awaitDone(): Unit = {
      val allFutures: immutable.Seq[Future[_]] = projects.flatMap(_.futures)
      val numAllFutures = allFutures.size
      val awaitAllFutures: Future[_] = awaitAll(allFutures)
      val numTasks = awaitAllFutures
      var lastNumCompleted = allFutures.count(_.isCompleted)
      while (true) try {
        Await.result(awaitAllFutures, Duration(60, "s"))
        timer.stop()
        val numCompleted = allFutures.count(_.isCompleted)
        println(s"PROGRESS: $numCompleted / $numAllFutures")
        return
      } catch {
        case _: TimeoutException =>
          val numCompleted = allFutures.count(_.isCompleted)
          if (numCompleted == lastNumCompleted) {
            println(s"STALLED: $numCompleted / $numAllFutures")
            println("Outline/Scala/Javac")
            projects.map {
              p =>
                def toX(b: Future[_]): String = b.value match { case None => "-"; case Some(Success(_)) => "x"; case Some(Failure(_)) => "!" }
                val s = List(p.outlineDoneFuture, p.groupsDoneFuture, p.javaDoneFuture).map(toX).mkString(" ")
                println(s + " " + p.label)
            }
          } else {
            println(s"PROGRESS: $numCompleted / $numAllFutures")
          }
      }
    }
    strategy match {
      case Pipeline =>
        projects.foreach { p =>
          val depsReady = Future.traverse(dependsOn.getOrElse(p, Nil))(task => p.dependencyReadyFuture(task))
          val f = for {
            _ <- depsReady
            _ <- {
              val isLeaf = !dependedOn.contains(p)
              if (isLeaf) {
                p.outlineDone.complete(Success(()))
                p.fullCompile()
              } else
                p.fullCompileExportPickles()
              // Start javac after scalac has completely finished
              Future.traverse(p.groups)(_.done.future)
            }
          } yield {
            p.javaCompile()
          }
          f.onComplete { _ => p.compiler.close() }
        }
        awaitDone()

        for (p <- projects) {
          val dependencies = dependsOn(p).map(_.t)

          def maxByOrZero[A](as: List[A])(f: A => Double): Double = if (as.isEmpty) 0d else as.map(f).max

          val maxOutlineCriticalPathMs = maxByOrZero(dependencies)(_.outlineCriticalPathMs)
          p.outlineCriticalPathMs = maxOutlineCriticalPathMs + p.outlineTimer.durationMs
          p.regularCriticalPathMs = maxOutlineCriticalPathMs + maxByOrZero(p.groups)(_.timer.durationMs)
          p.fullCriticalPathMs = maxByOrZero(dependencies)(_.fullCriticalPathMs) + p.groups.map(_.timer.durationMs).sum
        }

        if (parallelism == 1) {
          val criticalPath = projects.maxBy(_.regularCriticalPathMs)
          println(f"Critical path: ${criticalPath.regularCriticalPathMs}%.0f ms. Wall Clock: ${timer.durationMs}%.0f ms")
        } else
          println(f" Wall Clock: ${timer.durationMs}%.0f ms")
      case Traditional =>
        projects.foreach { p =>
          val f1 = Future.traverse(dependsOn.getOrElse(p, Nil))(_.t.javaDone.future)
          val f2 = f1.flatMap { _ =>
            p.outlineDone.complete(Success(()))
            p.fullCompile()
            Future.traverse(p.groups)(_.done.future).map(_ => p.javaCompile())
          }
          f2.onComplete { _ => p.compiler.close() }
        }
        awaitDone()

        for (p <- projects) {
          val dependencies = dependsOn(p).map(_.t)

          def maxByOrZero[A](as: List[A])(f: A => Double): Double = if (as.isEmpty) 0d else as.map(f).max

          p.fullCriticalPathMs = maxByOrZero(dependencies)(_.fullCriticalPathMs) + p.groups.map(_.timer.durationMs).sum
        }
        if (parallelism == 1) {
          val maxFullCriticalPath: Double = projects.map(_.fullCriticalPathMs).max
          println(f"Critical path: $maxFullCriticalPath%.0f ms. Wall Clock: ${timer.durationMs}%.0f ms")
        } else {
          println(f"Wall Clock: ${timer.durationMs}%.0f ms")
        }
    }

    writeChromeTrace(projects)
    deleteTempPickleCache()
    true
  }

  private def deleteTempPickleCache(): Unit = {
    if (pickleCacheConfigured == null) {
      AbstractFile.getDirectory(pickleCache.toFile).delete()
    }
  }

  private def writeChromeTrace(projects: List[Task]) = {
    val trace = new java.lang.StringBuilder()
    trace.append("""{"traceEvents": [""")
    val sb = new mutable.StringBuilder(trace)

    def durationEvent(name: String, cat: String, t: Timer): String = {
      s"""{"name": "$name", "cat": "$cat", "ph": "X", "ts": ${(t.startMicros).toLong}, "dur": ${(t.durationMicros).toLong}, "pid": 0, "tid": ${t.thread.getId}}"""
    }

    def projectEvents(p: Task): List[String] = {
      val events = List.newBuilder[String]
      if (p.outlineTimer.durationMicros > 0d) {
        val desc = "parser-to-pickler"
        events += durationEvent(p.label, desc, p.outlineTimer)
        events += durationEvent(p.label, "pickle-export", p.pickleExportTimer)
      }
      for ((g, ix) <- p.groups.zipWithIndex) {
        if (g.timer.durationMicros > 0d)
          events += durationEvent(p.label, "compile-" + ix, g.timer)
      }
      if (p.javaTimer.durationMicros > 0d) {
        val desc = "javac"
        events += durationEvent(p.label, desc, p.javaTimer)
      }
      events.result()
    }

    projects.iterator.flatMap(projectEvents).addString(sb, ",\n")
    trace.append("]}")
    val traceFile = Paths.get(s"build-${label}.trace")
    Files.write(traceFile, trace.toString.getBytes())
    println("Chrome trace written to " + traceFile.toAbsolutePath)
  }

  case class Group(files: List[String]) {
    val timer = new Timer
    val done = Promise[Unit]()
  }

  private case class Task(argsFile: Path, command: CompilerCommand, files: List[String]) {
    val label = argsFile.toString.replaceAll("target/", "").replaceAll("""(.*)/(.*).args""", "$1:$2")
    override def toString: String = argsFile.toString
    def outputDir: Path = command.settings.outputDirs.getSingleOutput.get.file.toPath.toAbsolutePath.normalize()
    private def expand(s: command.settings.PathSetting): List[Path] = {
      ClassPath.expandPath(s.value, expandStar = true).map(s => Paths.get(s).toAbsolutePath.normalize())
    }
    lazy val classPath: Seq[Path] = expand(command.settings.classpath)
    lazy val macroClassPath: Seq[Path] = expand(command.settings.YmacroClasspath)
    lazy val macroClassPathSet: Set[Path] = macroClassPath.toSet
    lazy val pluginClassPath: Set[Path] = {
      def asPath(p: String) = ClassPath split p

      val paths = command.settings.plugin.value filter (_ != "") flatMap (s => asPath(s) map (s => Paths.get(s)))
      paths.toSet
    }
    def dependencyReadyFuture(dependency: Dependency) = if (dependency.isMacro) {
      log(s"dependency is on macro classpath, will wait for .class files: ${dependency.t.label}")
      dependency.t.javaDone.future
    } else if (dependency.isPlugin) {
      log(s"dependency is on plugin classpath, will wait for .class files: ${dependency.t.label}")
      dependency.t.javaDone.future
    } else
      dependency.t.outlineDone.future


    val cacheMacro = java.lang.Boolean.getBoolean("scala.pipeline.cache.macro.classloader")
    val cachePlugin = java.lang.Boolean.getBoolean("scala.pipeline.cache.plugin.classloader")
    if (cacheMacro)
      command.settings.YcacheMacroClassLoader.value = "always"
    if (cachePlugin)
      command.settings.YcachePluginClassLoader.value = "always"

    if (strategy != Traditional) {
      command.settings.YpickleJava.value = true
    }

    val groups: List[Group] = {
      val isScalaLibrary = files.exists(_.endsWith("Predef.scala"))
      if (isScalaLibrary) {
        Group(files) :: Nil
      } else {
        command.settings.classpath.value = command.settings.outputDirs.getSingleOutput.get.toString + File.pathSeparator + command.settings.classpath.value
        val length = files.length
        val groups = (length.toDouble / 128).toInt.max(1)
        files.grouped((length.toDouble / groups).ceil.toInt.max(1)).toList.map(Group(_))
      }
    }
    command.settings.outputDirs.getSingleOutput.get.file.mkdirs()

    val isGrouped = groups.size > 1

    val outlineTimer = new Timer()
    val pickleExportTimer = new Timer
    val javaTimer = new Timer()

    var outlineCriticalPathMs = 0d
    var regularCriticalPathMs = 0d
    var fullCriticalPathMs = 0d
    val outlineDone: Promise[Unit] = Promise[Unit]()
    val outlineDoneFuture = outlineDone.future
    val javaDone: Promise[Unit] = Promise[Unit]()
    val javaDoneFuture: Future[_] = javaDone.future
    val groupsDoneFuture: Future[List[Unit]] = Future.traverse(groups)(_.done.future)
    val futures: List[Future[_]] = {
      outlineDone.future :: javaDone.future :: groups.map(_.done.future)
    }

    val originalClassPath: String = command.settings.classpath.value

    lazy val compiler: Global = try {
      val result = newCompiler(command.settings)
      val reporter = result.reporter
      if (reporter.hasErrors)
        reporter.flush()
      else if (command.shouldStopWithInfo)
        reporter.echo(command.getInfoMessage(result))
      result
    } catch {
      case t: Throwable =>
        t.printStackTrace()
        throw t
    }


    def fullCompile(): Unit = {
      command.settings.stopAfter.value = Nil
      command.settings.Ymacroexpand.value = command.settings.MacroExpand.Normal

      val groupCount = groups.size
      for ((group, ix) <- groups.zipWithIndex) {
        group.done.completeWith {
          Future {
            log(s"scalac (${ix + 1}/$groupCount): start")
            group.timer.start()
            val compiler2 = newCompiler(command.settings)
            try {
              val run2 = new compiler2.Run()
              run2 compile group.files
              compiler2.reporter.finish()
              if (compiler2.reporter.hasErrors) {
                group.done.complete(Failure(new RuntimeException(label + ": compile failed: ")))
              } else {
                group.done.complete(Success(()))
              }
            } finally {
              compiler2.close()
              group.timer.stop()
            }
            log(f"scalac (${ix + 1}/$groupCount): done ${group.timer.durationMs}%.0f ms")
          }
        }
      }
    }

    def fullCompileExportPickles(): Unit = {
      assert(groups.size == 1)
      val group = groups.head
      log("scalac: start")
      outlineTimer.start()
      try {
        val run2 = new compiler.Run() {

          override def advancePhase(): Unit = {
            if (compiler.phase == this.picklerPhase) {
              outlineTimer.stop()
              log(f"scalac outline: done ${outlineTimer.durationMs}%.0f ms")
              pickleExportTimer.start()
              registerPickleClassPath(command.settings.outputDirs.getSingleOutput.get.file.toPath, symData)
              pickleExportTimer.stop()
              log(f"scalac: exported pickles ${pickleExportTimer.durationMs}%.0f ms")
              outlineDone.complete(Success(()))
              group.timer.start()
            }
            super.advancePhase()
          }
        }

        run2 compile group.files
        compiler.reporter.finish()
        group.timer.stop()
        if (compiler.reporter.hasErrors) {
          log("scalac: failed")
          if (!outlineDone.isCompleted)
            outlineDone.complete(Failure(new RuntimeException(label + ": compile failed: ")))
          group.done.complete(Failure(new RuntimeException(label + ": compile failed: ")))
        } else {
          log(f"scalac: done ${group.timer.durationMs}%.0f ms")
          //        outlineDone.complete(Success(()))
          group.done.complete(Success(()))
        }
      } catch {
        case t: Throwable =>
          t.printStackTrace()
          if (!outlineDone.isCompleted)
            outlineDone.complete(Failure(new RuntimeException(label + ": compile failed: ")))
          if (!group.done.isCompleted)
            group.done.complete(Failure(new RuntimeException(label + ": compile failed: ")))
      }
    }

    def javaCompile(): Unit = {
      val javaSources = files.filter(_.endsWith(".java"))
      if (javaSources.nonEmpty) {
        log("javac: start")
        javaTimer.start()
        javaDone.completeWith(Future {
          val opts = java.util.Arrays.asList("-d", command.settings.outdir.value, "-cp", command.settings.outdir.value + File.pathSeparator + originalClassPath)
          val compileTask = ToolProvider.getSystemJavaCompiler.getTask(null, null, null, opts, null, fileManager.getJavaFileObjects(javaSources.toArray: _*))
          compileTask.setProcessors(Collections.emptyList())
          compileTask.call()
          javaTimer.stop()
          log(f"javac: done ${javaTimer.durationMs}%.0f ms")
          ()
        })
      } else {
        javaDone.complete(Success(()))
      }
    }
    def log(msg: String): Unit = println(this.label + ": " + msg)
  }

  final class Timer() {
    private var startNanos: Long = 0
    private var endNanos: Long = 0
    def start(): Unit = {
      assert(startNanos == 0L)
      startNanos = System.nanoTime
    }
    var thread: Thread = Thread.currentThread()
    def stop(): Unit = {
      thread = Thread.currentThread()
      endNanos = System.nanoTime()
    }
    def startMs: Double = startNanos.toDouble / 1000 / 1000
    def durationMs: Double = {
      val result = (endNanos - startNanos).toDouble / 1000 / 1000
      if (result < 0)
        getClass
      result
    }
    def startMicros: Double = startNanos.toDouble / 1000d
    def durationMicros: Double = (endNanos - startNanos).toDouble / 1000d
  }

  protected def newCompiler(settings: Settings): Global = {
    if (strategy != Traditional) {
      val classPath = ClassPath.expandPath(settings.classpath.value, expandStar = true)
      val modifiedClassPath = classPath.map { entry =>
        val entryPath = Paths.get(entry)
        if (Files.exists(entryPath))
          strippedAndExportedClassPath.getOrElse(entryPath.toRealPath().normalize(), entryPath).toString
        else
          entryPath
      }
      settings.classpath.value = modifiedClassPath.mkString(java.io.File.pathSeparator)
    }
    Global(settings)
  }
}


object PipelineMain {
  sealed abstract class BuildStrategy

  /** Begin compilation as soon as the pickler phase is complete on all dependencies. */
  case object Pipeline extends BuildStrategy

  /** Emit class files before triggering downstream compilation */
  case object Traditional extends BuildStrategy

  def main(args: Array[String]): Unit = {
    val strategies = List(Pipeline, Traditional)
    val strategy = strategies.find(_.productPrefix.equalsIgnoreCase(System.getProperty("scala.pipeline.strategy", "pipeline"))).get
    val parallelism = java.lang.Integer.getInteger("scala.pipeline.parallelism", java.lang.Runtime.getRuntime.availableProcessors())
    val useJars = java.lang.Boolean.getBoolean("scala.pipeline.use.jar")
    val argFiles: Seq[Path] = args match {
      case Array(path) if Files.isDirectory(Paths.get(path)) =>
        Files.walk(Paths.get(path)).iterator().asScala.filter(_.getFileName.toString.endsWith(".args")).toList
      case _ =>
        args.map(Paths.get(_))
    }
    val main = new PipelineMainClass("1", parallelism, strategy, argFiles, useJars)
    val result = main.process()
    if (!result)
      System.exit(1)
    else
      System.exit(0)
  }
}

//object PipelineMainTest {
//  def main(args: Array[String]): Unit = {
//    var i = 0
//    val argsFiles = Files.walk(Paths.get("/code/guardian-frontend")).iterator().asScala.filter(_.getFileName.toString.endsWith(".args")).toList
//    for (_ <- 1 to 2; n <- List(parallel.availableProcessors); start <- List(Pipeline)) {
//      i += 1
//      val main = new PipelineMainClass(start + "-" + i, n, start, argsFiles, useJars = false)
//      println(s"====== ITERATION $i=======")
//      val result = main.process()
//      if (!result)
//        System.exit(1)
//    }
//    System.exit(0)
//  }
//}
