name := "Carrier"

version := "0.1"

scalaVersion := "2.13.1"


val akkaVersion = "2.6.3"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % "10.1.10",
  "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % "1.0.5" exclude("com.typesafe.akka", "akka-discovery"),
  "com.typesafe.akka" %% "akka-discovery" % akkaVersion,
  "ch.qos.logback" % "logback-classic" % "1.1.3" % Runtime,
  "com.amazonaws" % "aws-java-sdk" % "1.11.820"
)

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

dockerExposedPorts := Seq(3000)

maintainer in Docker := "Erwin Debusschere <erwincdl@gmail.com>"
packageSummary in Docker := "Carrier"
dockerEntrypoint := Seq("/opt/docker/bin/test-server")
packageDescription := "Carrier"
