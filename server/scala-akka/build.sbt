name := "scala-akka"

version := "0.1"

scalaVersion := "2.12.4"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-slf4j" % "2.5.8",
  "com.typesafe.akka" %% "akka-actor" % "2.5.8",
  "com.typesafe.akka" %% "akka-testkit" % "2.5.8" % Test,
  "com.typesafe.akka" %% "akka-http" % "10.0.11",
  "com.typesafe.akka" %% "akka-http-testkit" % "10.0.11" % Test,
  "org.scalatest" %% "scalatest" % "3.0.4" % Test,
  "com.lihaoyi" %% "upickle_sjs0.6" % "0.4.4"
)