ThisBuild / scalaVersion         := "2.13.12"
ThisBuild / version              := "0.1.0-SNAPSHOT"
ThisBuild / versionScheme        := Some("early-semver")
ThisBuild / homepage             := Some(url("https://github.com/kivikakk/ili9341spi"))
ThisBuild / organization         := "ee.kivikakk"
ThisBuild / organizationHomepage := Some(url("https://github.com/kivikakk"))

val chiselVersion = "6.4.0"

lazy val root = (project in file("."))
  .settings(
    name := "ili9341spi",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel"    % chiselVersion,
      "org.scalatest"     %% "scalatest" % "3.2.18" % "test",
      "ee.hrzn"           %% "chryse"    % "0.1.1-SNAPSHOT",
      "ee.hrzn"           %% "athena"    % "0.1.0-SNAPSHOT",
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls", "-deprecation", "-feature", "-Xcheckinit",
      "-Ymacro-annotations", "-Xlint",
    ),
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full,
    ),
  )

// Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oF")
