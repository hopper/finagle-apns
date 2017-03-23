import sbt._
import Keys._
import sbtrelease.ReleasePlugin._

object FinagleApns extends Build {

  object V {
    val finagle = "6.27.0"
  }

  val publishToHopperNexus: Def.Initialize[Option[sbt.Resolver]] =
    version { (v: String) =>
      val nexus = "http://nexus.lab.mtl/nexus/"
      if (v.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases"  at nexus + "content/repositories/releases")
    }

  val baseSettings = Defaults.coreDefaultSettings ++ Seq(
    libraryDependencies ++= Seq(
      "com.twitter" %% "finagle-core" % V.finagle,
      "org.scalatest" %% "scalatest" % "2.2.6" % "test"
    )
  )

  lazy val buildSettings = Seq(
    organization := "com.hopper",
    scalaVersion := "2.11.8",
    publishTo <<= publishToHopperNexus
  )

  lazy val root = Project(id = "finagle-apns",
    base = file("."),
    settings = baseSettings ++ buildSettings ++ releaseSettings
  )

}
