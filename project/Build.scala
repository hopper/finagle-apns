import sbt._
import Keys._

object FinagleApns extends Build {

  object V {
    val finagle = "6.8.1"
  }

  val baseSettings = Defaults.defaultSettings ++ Seq(
    libraryDependencies ++= Seq(
      "com.twitter" %% "finagle-core" % V.finagle,
      "com.google.guava" % "guava" % "15.0",
      "com.google.code.findbugs" % "jsr305" % "1.3.9",
      "org.scalatest" %% "scalatest" % "1.9.2" % "test"
    ))

  lazy val buildSettings = Seq(
    organization := "com.hopper",
    version := V.finagle,
    crossScalaVersions := Seq("2.9.2", "2.10.3")
  )

  lazy val root = Project(id = "finagle-apns",
    base = file("."),
    settings = baseSettings ++ buildSettings)

}
