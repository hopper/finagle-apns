lazy val root = (project in file("."))
  .settings(
    name                     := "finagle-apns",
    organization             := "com.hopper",
    scalaVersion             := "2.11.12",
    version                  := (ThisBuild / version).value,
    libraryDependencies ++= Seq(
      "com.twitter"   %% "finagle-core" % "6.27.0",
      "org.scalatest" %% "scalatest"    % "3.2.11" % "test"
    ),
    githubOwner              := "hopper",
    githubRepository         := "finagle-apns",
    githubTokenSource        := TokenSource.Or(
      TokenSource.Environment("GITHUB_TOKEN"),
      TokenSource.GitConfig("github.token")
    ),
    releaseCommitMessage     := s"ci: bumps version to ${(ThisBuild / version).value}",
    releaseNextCommitMessage := s"ci: bumps version to ${(ThisBuild / version).value}",
//    publishTo                := publishToHopperNexus
  )

//val publishToHopperNexus = {
//  val nexus = "http://nexus.lab.mtl/nexus/"
//  if (isSnapshot.value)
//    Some("snapshots" at nexus + "content/repositories/snapshots")
//  else
//    Some("releases" at nexus + "content/repositories/releases")
//}

//********* COMMANDS ALIASES *********
addCommandAlias("t", "test")
addCommandAlias("to", "testOnly")
addCommandAlias("tq", "testQuick")
addCommandAlias("tsf", "testShowFailed")

addCommandAlias("c", "compile")
addCommandAlias("tc", "test:compile")

addCommandAlias("f", "scalafmt")
addCommandAlias("fc", "scalafmtCheck")
addCommandAlias("tf", "test:scalafmt")
addCommandAlias("tfc", "test:scalafmtCheck")
addCommandAlias("fmt", ";f;tf")
addCommandAlias("fmtCheck", ";fc;tfc")

addCommandAlias("build", ";c;tc")
