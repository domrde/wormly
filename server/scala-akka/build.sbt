
name := "wormly"

version := "1.0"

lazy val wormly = (project in file("."))
  .settings(
    scalaVersion := "2.12.4",
    libraryDependencies ++= {
      val akkaVersion = "2.5.8"
      val akkaHttpVersion = "10.0.11"
      Seq(
        "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
        "ch.qos.logback" % "logback-classic" % "1.2.3",

        "com.typesafe.akka" %% "akka-actor" % akkaVersion,
        "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test,

        "com.typesafe.akka" %% "akka-stream" % akkaVersion,
        "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,

        "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
        "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,

        "org.scalatest" %% "scalatest" % "3.0.4" % Test,
        "com.lihaoyi" %% "upickle" % "0.4.4"
      )
    })