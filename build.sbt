import Dependencies._

ThisBuild / scalaVersion     := "2.13.1"
ThisBuild / version          := "0.4.0"
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
enablePlugins(AshScriptPlugin)

Compile / packageBin / mappings := {
  val umappings = (Compile / packageBin / mappings).value
  umappings filter {
    case (_, name) => !name.endsWith("application.conf")
  }
}

dockerBaseImage := "openjdk:8-alpine"
dockerUpdateLatest := true
dockerExposedVolumes := Seq("/opt/docker/conf")
dockerUsername in Docker := Some("nefilim")
packageSummary in Docker := "Kumo MQTT agent"
packageDescription := "Kumo Agent"

// Only add this if you want to rename your docker image name
packageName in Docker := "kumo-agent"

javaOptions in Universal ++= Seq(
  // -J params will be added as jvm parameters
  "-J-Xmx64m",
  "-J-Xms64m",

  // others will be added as app parameters
  "-Dconfig.file=/opt/docker/conf/application.conf"
)

lazy val root = (project in file("."))
  .settings(
    name := "Kumo Agent"
  )

