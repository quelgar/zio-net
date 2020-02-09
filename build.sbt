import Dependencies._

ThisBuild / scalaVersion := "2.13.1"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "dev.zio"
ThisBuild / organizationName := "ZIO"
ThisBuild / licenses := List(
  "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
)

ThisBuild / scalacOptions ++= Seq("-feature")

lazy val root = (project in file("."))
  .settings(
    name := "zio-net",
    libraryDependencies += zioStreams,
    libraryDependencies += zioNio
  )

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
