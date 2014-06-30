import sbt._
import Keys._
import sbtrelease.ReleasePlugin._

object FinagleApns extends Build {

  object V {
    val finagle = "6.18.0"
  }

  val baseSettings = Defaults.defaultSettings ++ Seq(
    libraryDependencies ++= Seq(
      "com.twitter" %% "finagle-core" % V.finagle,
      "com.google.guava" % "guava" % "15.0",
      "com.google.code.findbugs" % "jsr305" % "1.3.9",
      "org.scalatest" %% "scalatest" % "1.9.2" % "test"
    )
  )

  lazy val buildSettings = Seq(
    organization := "com.hopper",
    crossScalaVersions := Seq("2.9.2", "2.10.4"),
    publishTo := Some(Resolver.file("maven-local", file(Path.userHome + "/.m2/repository")))
  )

  lazy val root = Project(id = "finagle-apns",
    base = file("."),
    settings = baseSettings ++ buildSettings ++ releaseSettings
  )

}
