name := "BatRam"

version := "0.1"

scalaVersion := "2.13.1"


val akkaVersion = "2.6.3"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % "10.1.11",
  "ch.qos.logback" % "logback-classic" % "1.1.3" % Runtime
)

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

dockerExposedPorts := Seq(3000)

maintainer in Docker := "Erwin Debusschere <erwincdl@gmail.com>"
packageSummary in Docker := "BatRam"
dockerEntrypoint := Seq("/opt/docker/bin/test-server")
packageDescription := "BatRam"
