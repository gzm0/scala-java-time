import org.scalajs.linker.interface.ModuleSplitStyle
import sbtcrossproject.CrossPlugin.autoImport.{ CrossType, crossProject }
import sbt._
import sbt.io.Using

val scalaVer    = "3.0.0-M2"
val tzdbVersion = "2019c"
Global / onChangedBuildSource := ReloadOnSourceChanges

Global / resolvers += Resolver.sonatypeRepo("public")

lazy val downloadFromZip: TaskKey[Unit] =
  taskKey[Unit]("Download the tzdb tarball and extract it")

addCommandAlias("validate", ";clean;scalajavatimeTestsJVM/test;scalajavatimeTestsJS/test")
addCommandAlias("demo", ";clean;demo/fastOptJS;demo/fullOptJS")

inThisBuild(
  List(
    organization := "io.github.cquiroz",
    homepage := Some(url("https://github.com/cquiroz/scala-java-time")),
    licenses := Seq("BSD 3-Clause License" -> url("https://opensource.org/licenses/BSD-3-Clause")),
    developers := List(
      Developer("cquiroz",
                "Carlos Quiroz",
                "carlos.m.quiroz@gmail.com",
                url("https://github.com/cquiroz")
      )
    ),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/cquiroz/scala-java-time"),
        "scm:git:git@github.com:cquiroz/scala-java-time.git"
      )
    )
  )
)

skip in publish := true

def scalaVersionSpecificFolders(srcName: String, srcBaseDir: java.io.File, scalaVersion: String) = {
  def extraDirs(suffix: String) =
    List(CrossType.Pure, CrossType.Full)
      .flatMap(_.sharedSrcDir(srcBaseDir, srcName).toList.map(f => file(f.getPath + suffix)))
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((2, y))     => extraDirs("-2.x") ++ (if (y >= 13) extraDirs("-2.13+") else Nil)
    case Some((0 | 3, _)) => extraDirs("-2.13+") ++ extraDirs("-3.x")
    case _                => Nil
  }
}

lazy val commonSettings = Seq(
  description := "java.time API implementation in Scala and Scala.js",
  scalaVersion := scalaVer,
  crossScalaVersions := Seq("2.11.12", "2.12.12", "2.13.4", "3.0.0-M2", "3.0.0-M3"),
  // Don't include threeten on the binaries
  mappings in (Compile, packageBin) := (mappings in (Compile, packageBin)).value.filter {
    case (f, s) => !s.contains("threeten")
  },
  scalacOptions in Compile ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, scalaMajor)) if scalaMajor == 13 =>
        Seq("-deprecation:false")
      case _                                         =>
        Seq.empty
    }
  },
  scalacOptions in (Compile, doc) := {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, scalaMajor)) if scalaMajor >= 11 =>
        Seq("-deprecation:false")
      case _                              =>
        Seq.empty
    }
  },
  Compile / unmanagedSourceDirectories ++= scalaVersionSpecificFolders("main",
                                                                       baseDirectory.value,
                                                                       scalaVersion.value
  ),
  Test / unmanagedSourceDirectories ++= scalaVersionSpecificFolders("test",
                                                                    baseDirectory.value,
                                                                    scalaVersion.value
  ),
  scalacOptions ++= {if (isDotty.value) Seq.empty else Seq("-target:jvm-1.8")},
  javaOptions ++= Seq("-Dfile.encoding=UTF8"),
  autoAPIMappings := true,
  Compile / doc / sources := { if (isDotty.value) Seq() else (Compile / doc / sources).value }
)

/**
 * Copy source files and translate them to the java.time package
 */
def copyAndReplace(srcDirs: Seq[File], destinationDir: File): Seq[File] = {
  // Copy a directory and return the list of files
  def copyDirectory(
    source:               File,
    target:               File,
    overwrite:            Boolean = false,
    preserveLastModified: Boolean = false
  ): Set[File] =
    IO.copy(PathFinder(source).allPaths.pair(Path.rebase(source, target)).toTraversable,
            overwrite,
            preserveLastModified,
            false
    )

  val onlyScalaDirs                      = srcDirs.filter(_.getName.matches(".*scala(-\\d\\.x)?"))
  // Copy the source files from the base project, exclude classes on java.util and dirs
  val generatedFiles: List[java.io.File] = onlyScalaDirs
    .foldLeft(Set.empty[File]) { (files, sourceDir) =>
      files ++ copyDirectory(sourceDir, destinationDir, overwrite = true)
    }
    .filterNot(_.isDirectory)
    .filter(_.getName.endsWith(".scala"))
    .filterNot(_.getParentFile.getName == "util")
    .toList

  // These replacements will in practice rename all the classes from
  // org.threeten to java.time
  def replacements(line: String): String =
    line
      .replaceAll("package org.threeten$", "package java")
      .replaceAll("package object bp", "package object time")
      .replaceAll("package org.threeten.bp", "package java.time")
      .replaceAll("""import org.threeten.bp(\..*)?(\.[A-Z_{][^\.]*)""", "import java.time$1$2")
      .replaceAll("import zonedb.threeten", "import zonedb.java")
      .replaceAll("private\\s*\\[bp\\]", "private[time]")

  // Visit each file and read the content replacing key strings
  generatedFiles.foreach { f =>
    val replacedLines = IO.readLines(f).map(replacements)
    IO.writeLines(f, replacedLines)
  }
  generatedFiles
}

lazy val scalajavatime = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(commonSettings: _*)
  .settings(
    name := "scala-java-time",
    libraryDependencies += ("org.portable-scala" %%% "portable-scala-reflect" % "1.0.0")
      .withDottyCompat(scalaVersion.value),
    mimaPreviousArtifacts +=
        "org.scala-js" %%% "scalajs-java-time" % "1.0.0",
  )
  .jsSettings(
    scalacOptions ++= {
      if (isDotty.value) Seq("-scalajs-genStaticForwardersForNonTopLevelObjects")
      else Seq("-P:scalajs:genStaticForwardersForNonTopLevelObjects")
    },
    scalacOptions ++= {

      if (isDotty.value) Seq.empty
      else {
        val tagOrHash =
          if (isSnapshot.value) sys.process.Process("git rev-parse HEAD").lineStream_!.head
          else s"v${version.value}"
        (sourceDirectories in Compile).value.map { f =>
          val a = f.toURI.toString
          val g =
            "https://raw.githubusercontent.com/cquiroz/scala-java-time/" + tagOrHash + "/shared/src/main/scala/"
          s"-P:scalajs:mapSourceURI:$a->$g/"
        }
      }
    },
    sourceGenerators in Compile += Def.task {
      val srcDirs        = (sourceDirectories in Compile).value
      val destinationDir = (sourceManaged in Compile).value
      copyAndReplace(srcDirs, destinationDir)
    }.taskValue,
    libraryDependencies ++= Seq(
      "io.github.cquiroz" %%% "scala-java-locales" % "1.1.0"
    )
  )

lazy val scalajavatimeTZDB = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("tzdb"))
  .settings(commonSettings)
  .settings(
    name := "scala-java-time-tzdb"
  )
  .jsSettings(
    dbVersion := TzdbPlugin.Version(tzdbVersion),
    includeTTBP := true,
    sourceGenerators in Compile += Def.task {
      val srcDirs        = (sourceManaged in Compile).value
      val destinationDir = (sourceManaged in Compile).value
      copyAndReplace(Seq(srcDirs), destinationDir)
    }.taskValue
  )
  .jvmSettings(
    includeTTBP := true,
    jsOptimized := false
  )
  .dependsOn(scalajavatime)

lazy val scalajavatimeTZDBJVM = scalajavatimeTZDB.jvm.enablePlugins(TzdbPlugin)
lazy val scalajavatimeTZDBJS  = scalajavatimeTZDB.js.enablePlugins(TzdbPlugin)

lazy val scalajavatimeTests = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("tests"))
  .settings(commonSettings: _*)
  .settings(
    name := "scala-java-time-tests",
    // No, SBT, we don't want any artifacts for root.
    // No, not even an empty jar.
    publish := {},
    publishLocal := {},
    publishArtifact := false,
    Keys.`package` := file(""),
    skip.in(compile) := isDotty.value,
    libraryDependencies +=
      ("org.scalatest" %%% "scalatest" % "3.2.3" % "test").withDottyCompat(scalaVersion.value),
    scalacOptions ~= (_.filterNot(
      Set("-Wnumeric-widen", "-Ywarn-numeric-widen", "-Ywarn-value-discard", "-Wvalue-discard")
    ))
  )
  .jvmSettings(
    // Fork the JVM test to ensure that the custom flags are set
    fork in Test := true,
    baseDirectory in Test := baseDirectory.value.getParentFile,
    // Use CLDR provider for locales
    // https://docs.oracle.com/javase/8/docs/technotes/guides/intl/enhancements.8.html#cldr
    javaOptions in Test ++= Seq("-Duser.language=en",
                                "-Duser.country=US",
                                "-Djava.locale.providers=CLDR"
    )
  )
  .jsSettings(
    parallelExecution in Test := false,
    sourceGenerators in Test += Def.task {
      val srcDirs        = (sourceDirectories in Test).value
      val destinationDir = (sourceManaged in Test).value
      copyAndReplace(srcDirs, destinationDir)
    }.taskValue,
    libraryDependencies ++= Seq(
      "io.github.cquiroz" %%% "locales-full-db" % "1.1.0"
    )
  )
  .dependsOn(scalajavatime, scalajavatimeTZDB)

val zonesFilterFn = (x: String) => {
  x == "Europe/Helsinki" || x == "America/Santiago"
}

lazy val demo = project
  .in(file("demo"))
  .dependsOn(scalajavatime.js)
  .enablePlugins(ScalaJSPlugin)
  .enablePlugins(TzdbPlugin)
  .settings(
    scalaVersion := scalaVer,
    name := "demo",
    publish := {},
    publishLocal := {},
    publishArtifact := false,
    Keys.`package` := file(""),
    // scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    // scalaJSLinkerConfig ~= (_.withModuleSplitStyle(ModuleSplitStyle.SmallestModules)),
    scalaJSUseMainModuleInitializer := true,
    zonesFilter := zonesFilterFn
  )

// lazy val docs = project
//   .in(file("docs"))
//   .dependsOn(scalajavatime.jvm, scalajavatime.js)
//   .settings(commonSettings)
//   .settings(name := "docs")
//   .enablePlugins(MicrositesPlugin)
//   .settings(
//     micrositeName := "scala-java-time",
//     micrositeAuthor := "Carlos Quiroz",
//     micrositeGithubOwner := "cquiroz",
//     micrositeGithubRepo := "scala-java-time",
//     micrositeBaseUrl := "/scala-java-time",
//     micrositePushSiteWith := GitHub4s,
//     //micrositeDocumentationUrl := "/scala-java-time/docs/",
//     micrositeHighlightTheme := "color-brewer",
//     micrositeGithubToken := Option(System.getProperty("GH_TOKEN"))
//   )
