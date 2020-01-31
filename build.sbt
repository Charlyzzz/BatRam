name := "BatRam"

version := "0.1"

scalaVersion := "2.13.1"


val akkaVersion = "2.6.3"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
  "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % "1.0.5" exclude("com.typesafe.akka", "akka-discovery"),
  "com.typesafe.akka" %% "akka-discovery" % akkaVersion,
  "ch.qos.logback" % "logback-classic" % "1.1.3" % Runtime
)

enablePlugins(JavaAppPackaging)
