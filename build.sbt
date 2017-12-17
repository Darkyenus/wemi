
name := "WEMI" // Wonders Expeditiously, Mundane Instantly

version := "0.1-SNAPSHOT"

organization := "com.darkyen"

crossPaths := false

autoScalaLibrary := false

kotlinVersion := "1.1.4"

kotlinLib("stdlib")

javacOptions += "-g"

libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.25"

resolvers += "jitpack" at "https://jitpack.io"

libraryDependencies += "com.github.Darkyenus" % "tproll" % "v1.2.5"

libraryDependencies += "com.github.Darkyenus" % "DaveWebb" % "v1.2"

libraryDependencies += "com.github.EsotericSoftware" % "jsonbeans" % "cb0f3406fc"

libraryDependencies += "org.jetbrains.kotlin" % "kotlin-compiler" % "1.1.4" % Provided

libraryDependencies += "org.jline" % "jline" % "3.3.0"

libraryDependencies += "junit" % "junit" % "4.12" % Test

libraryDependencies += "com.novocode" % "junit-interface" % "0.11" % Test

testOptions in Test := Seq(Tests.Argument(TestFrameworks.JUnit, "-a"))

assemblyMergeStrategy in assembly := {stuff => if (stuff.endsWith(".kotlin_module")) {
  MergeStrategy.deduplicate
} else if (stuff.equals("META-INF/MANIFEST.MF")) {
  MergeStrategy.rename
} else MergeStrategy.deduplicate}

mainClass in assembly := Some("wemi.boot.MainKt")

import sbtassembly.AssemblyPlugin.defaultShellScript

assemblyOption in assembly := (assemblyOption in assembly).value.copy(prependShellScript = Some(defaultShellScript))

assemblyJarName in assembly := s"wemi"