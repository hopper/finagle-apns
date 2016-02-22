import sbt._
import Keys._
import sbtrelease.ReleasePlugin._

object FinagleApns extends Build {

  object V {
    val finagle = "6.22.0"
  }

  val baseSettings = Defaults.coreDefaultSettings ++ Seq(
    libraryDependencies ++= Seq(
      "com.twitter" %% "finagle-core" % V.finagle,
      "org.scalatest" %% "scalatest" % "2.2.6" % "test"
    )
  )

  lazy val buildSettings = Seq(
    organization := "com.hopper",
    crossScalaVersions := Seq("2.10.5"),
    publishTo := Some(Resolver.file("maven-local", file(Path.userHome + "/.m2/repository")))
  )

  lazy val root = Project(id = "finagle-apns",
    base = file("."),
    settings = baseSettings ++ buildSettings ++ releaseSettings
  )

}
