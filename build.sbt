organization := "org.scalaconsole"

name := "ScalaConsole"

version := "1.5.RC5"

scalaVersion := "2.11.0"

scalaSource in Compile <<= baseDirectory(_ / "src")

scalaSource in Test <<= baseDirectory(_ / "test")

javaSource in Compile <<= baseDirectory(_ / "src")

unmanagedResourceDirectories in Compile <+= baseDirectory(_ / "resources")

fork := true

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

//mainResourcesPath := "src"
//
// testScalaSourcePath := "test"
//
//testResourcesPath := "test"
//
//managedDependencyPath := "lib"
//
////  override def mainSources = super.mainSources --- ("src" ** "CommaSeperatedData.scala")
//mainResources := super.mainResources --- ("src" ** ("*.scala" | "*.java"))
//
//scalacOptions += "-optimise"
//

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies ++= Seq(
  "org.apache.ivy" % "ivy" % "2.2.0",
  "org.scala-lang" % "scala-compiler" % scalaVersion.value,
  "org.controlsfx" % "controlsfx" % "8.0.5",
  "com.google.guava" % "guava" % "17.0",
  "com.google.code.gson" % "gson" % "2.2.4",
  "org.specs2" %% "specs2" % "2.3.11" % "test"
)


