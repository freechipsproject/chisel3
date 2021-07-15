// See LICENSE for license details.

enablePlugins(SiteScaladocPlugin)

val defaultVersions = Map(
  "firrtl" -> "edu.berkeley.cs" %% "firrtl" % "1.5-SNAPSHOT",
  "treadle" -> "edu.berkeley.cs" %% "treadle" % "1.5-SNAPSHOT"
)

lazy val commonSettings = Seq (
  resolvers ++= Seq(
    Resolver.sonatypeRepo("snapshots"),
    Resolver.sonatypeRepo("releases")
  ),
  organization := "edu.berkeley.cs",
  version := "3.5-SNAPSHOT",
  autoAPIMappings := true,
  scalaVersion := "2.13.6",
  crossScalaVersions := Seq("2.13.6", "2.12.13"),
  scalacOptions := Seq("-deprecation", "-feature"),
  libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  // Macros paradise is integrated into 2.13 but requires a scalacOption
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, n)) if n >= 13 => "-Ymacro-annotations" :: Nil
      case _ => Nil
    }
  },
  libraryDependencies ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, n)) if n >= 13 => Nil
      case _ => compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full) :: Nil
    }
  }
)

lazy val publishSettings = Seq (
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { x => false },
  pomExtra := <url>http://chisel.eecs.berkeley.edu/</url>
    <licenses>
      <license>
        <name>apache-v2</name>
        <url>https://opensource.org/licenses/Apache-2.0</url>
        <distribution>repo</distribution>
      </license>
    </licenses>
    <developers>
      <developer>
        <id>jackbackrack</id>
        <name>Jonathan Bachrach</name>
        <url>http://www.eecs.berkeley.edu/~jrb/</url>
      </developer>
    </developers>,

  publishTo := {
    val v = version.value
    val nexus = "https://oss.sonatype.org/"
    if (v.trim.endsWith("SNAPSHOT")) {
      Some("snapshots" at nexus + "content/repositories/snapshots")
    }
    else {
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
    }
  }
)

lazy val chiselSettings = Seq (
  name := "chisel3",

  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.2.9" % "test",
    "org.scalatestplus" %% "scalacheck-1-14" % "3.2.2.0" % "test",
    "com.lihaoyi" %% "os-lib" % "0.7.8",
  ),
) ++ (
  // Tests from other projects may still run concurrently
  //  if we're not running with -DminimalResources.
  // Another option would be to experiment with:
  //  concurrentRestrictions in Global += Tags.limit(Tags.Test, 1),
  sys.props.contains("minimalResources") match {
    case true  => Seq( Test / parallelExecution := false )
    case false => Seq( fork := true,
                       Test / testForkedParallel := true )
  }
)

autoCompilerPlugins := true

// Plugin must be fully cross-versioned (published for Scala minor version)
// The plugin only works in Scala 2.12+
lazy val pluginScalaVersions = Seq(
  // scalamacros paradise version used is not published for 2.12.0 and 2.12.1
  "2.12.2",
  "2.12.3",
  "2.12.4",
  "2.12.5",
  "2.12.6",
  "2.12.7",
  "2.12.8",
  "2.12.9",
  "2.12.10",
  "2.12.11",
  "2.12.12",
  "2.12.13",
  "2.13.0",
  "2.13.1",
  "2.13.2",
  "2.13.3",
  "2.13.4",
  "2.13.5"
)

lazy val plugin = (project in file("plugin")).
  settings(name := "chisel3-plugin").
  settings(commonSettings: _*).
  settings(publishSettings: _*).
  settings(
    libraryDependencies += "org.scala-lang" % "scala-compiler" % scalaVersion.value,
    scalacOptions += "-Xfatal-warnings",
    crossScalaVersions := pluginScalaVersions,
    // Must be published for Scala minor version
    crossVersion := CrossVersion.full,
    crossTarget := {
      // workaround for https://github.com/sbt/sbt/issues/5097
      target.value / s"scala-${scalaVersion.value}"
    }
  ).
  settings(
    mimaPreviousArtifacts := {
      Set()
    }
  )

lazy val usePluginSettings = Seq(
  scalacOptions in Compile ++= {
    val jar = (plugin / Compile / Keys.`package`).value
    val addPlugin = "-Xplugin:" + jar.getAbsolutePath
    // add plugin timestamp to compiler options to trigger recompile of
    // main after editing the plugin. (Otherwise a 'clean' is needed.)
    val dummy = "-Jdummy=" + jar.lastModified
    Seq(addPlugin, dummy)
  }
)

lazy val macros = (project in file("macros")).
  settings(name := "chisel3-macros").
  settings(commonSettings: _*).
  settings(publishSettings: _*).
  settings(mimaPreviousArtifacts := Set())

lazy val firrtlRef = ProjectRef(workspaceDirectory / "firrtl", "firrtl")

lazy val core = (project in file("core")).
  sourceDependency(firrtlRef, defaultVersions("firrtl")).
  settings(commonSettings: _*).
  enablePlugins(BuildInfoPlugin).
  settings(
    buildInfoPackage := "chisel3",
    buildInfoUsePackageAsPath := true,
    buildInfoKeys := Seq[BuildInfoKey](buildInfoPackage, version, scalaVersion, sbtVersion)
  ).
  settings(publishSettings: _*).
  settings(mimaPreviousArtifacts := Set()).
  settings(
    name := "chisel3-core",
    scalacOptions := scalacOptions.value ++ Seq(
      "-deprecation",
      "-explaintypes",
      "-feature",
      "-language:reflectiveCalls",
      "-unchecked",
      "-Xcheckinit",
      "-Xlint:infer-any"
//      , "-Xlint:missing-interpolator"
    )
  ).
  dependsOn(macros)

// This will always be the root project, even if we are a sub-project.
lazy val root = RootProject(file("."))

lazy val chisel = (project in file(".")).
  enablePlugins(ScalaUnidocPlugin).
  settings(commonSettings: _*).
  settings(chiselSettings: _*).
  settings(publishSettings: _*).
  settings(usePluginSettings: _*).
  dependsOn(macros).
  dependsOn(core).
  aggregate(macros, core, plugin).
  settings(
    mimaPreviousArtifacts := Set(),
    libraryDependencies += defaultVersions("treadle") % "test",
    scalacOptions in Test ++= Seq("-language:reflectiveCalls"),
    scalacOptions in Compile in doc ++= Seq(
      "-diagrams",
      "-groups",
      "-skip-packages", "chisel3.internal",
      "-diagrams-max-classes", "25",
      "-doc-version", version.value,
      "-doc-title", name.value,
      "-doc-root-content", baseDirectory.value+"/root-doc.txt",
      "-sourcepath", (baseDirectory in ThisBuild).value.toString,
      "-doc-source-url",
      {
        val branch =
          if (version.value.endsWith("-SNAPSHOT")) {
            "master"
          } else {
            s"v${version.value}"
          }
        s"https://github.com/chipsalliance/chisel3/tree/$branch€{FILE_PATH_EXT}#L€{FILE_LINE}"
      }
    )
  )

lazy val noPluginTests = (project in file ("no-plugin-tests")).
  dependsOn(chisel).
  settings(commonSettings: _*).
  settings(chiselSettings: _*).
  settings(Seq(
    // Totally don't know why GitHub Action won't introduce FIRRTL to dependency.
    libraryDependencies += defaultVersions("firrtl"),
  ))

lazy val docs = project       // new documentation project
  .in(file("docs-target")) // important: it must not be docs/
  .dependsOn(chisel)
  .enablePlugins(MdocPlugin)
  .settings(usePluginSettings: _*)
  .settings(commonSettings)
  .settings(
    scalacOptions += "-language:reflectiveCalls",
    mdocIn := file("docs/src"),
    mdocOut := file("docs/generated"),
    mdocExtraArguments := Seq("--cwd", "docs", "--no-link-hygiene"),
    mdocVariables := Map(
      "BUILD_DIR" -> "docs-target" // build dir for mdoc programs to dump temp files
    )
  )

addCommandAlias("com", "all compile")
addCommandAlias("lint", "; compile:scalafix --check ; test:scalafix --check")
addCommandAlias("fix", "all compile:scalafix test:scalafix")
