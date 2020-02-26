import sbt._

object Dependencies {
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.8"
  lazy val hiveMQ = "com.hivemq" % "hivemq-mqtt-client" % "1.1.3"

  lazy val sttp = "com.softwaremill.sttp.client" %% "core" % "2.0.0-RC13"
  lazy val sttpOkHttp = "com.softwaremill.sttp.client" %% "okhttp-backend" % "2.0.0-RC13"
  lazy val akka = "com.typesafe.akka" %% "akka-actor-typed" % "2.6.3"
  lazy val scalaJava8Compat = "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.1"

  lazy val ficus = "com.iheart" %% "ficus" % "1.4.7"
  lazy val json4sJackson = "org.json4s" %% "json4s-jackson" % "3.6.7"
  lazy val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2"
  lazy val logback = "ch.qos.logback" % "logback-classic" % "1.2.3"
}
