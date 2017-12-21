name := "wormly"

version := "0.1"

scalaVersion := "2.12.4"

libraryDependencies ++= {
  val akkaVersion = "2.5.8"
  val akkaHttpVersion = "10.0.11"
  Seq(
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,

    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,

    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,

    "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,

    "org.scalatest" %% "scalatest" % "3.0.4" % Test,
    "com.lihaoyi" %% "upickle_sjs0.6" % "0.4.4"
  )
}

lazy val wormly = project in file(".")