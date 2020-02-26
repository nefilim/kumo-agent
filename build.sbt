import Dependencies._

ThisBuild / scalaVersion     := "2.13.1"
ThisBuild / version          := "0.2.0"
ThisBuild / organization     := "org.nefilim"
ThisBuild / organizationName := "kumo"

libraryDependencies ++= Seq(
  hiveMQ,
  sttp,
  sttpOkHttp,
  json4sJackson,
  akka,
  ficus,
  scalaJava8Compat,
  scalaLogging,
  logback,
  scalaTest % Test
)

enablePlugins(JavaServerAppPackaging)
enablePlugins(DockerPlugin)

dockerBaseImage := "openjdk:8"
dockerUpdateLatest := true
dockerUsername in Docker := Some("nefilim")
packageSummary in Docker := "Kumo MQTT agent"
packageDescription := "Kumo Agent"

// Only add this if you want to rename your docker image name
packageName in Docker := "kumo-agent"

lazy val root = (project in file("."))
  .settings(
    name := "Kumo Agent"
  )

