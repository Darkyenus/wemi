
name := "WEMI" // Wonders Expeditiously, Mundane Instantly

version := "0.1-SNAPSHOT"

organization := "com.darkyen"

crossPaths := false

autoScalaLibrary := false

kotlinVersion := "1.1.2"

kotlinLib("stdlib")

javacOptions += "-g"

libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.25"

resolvers += "jitpack" at "https://jitpack.io"

libraryDependencies += "com.github.Darkyenus" % "tproll" % "v1.2.2"

libraryDependencies += "com.github.Darkyenus" % "DaveWebb" % "v1.2"

libraryDependencies += "org.jetbrains.kotlin" % "kotlin-compiler" % "1.1.3-2" % Provided

assemblyMergeStrategy in assembly := {stuff => if (stuff.startsWith("META-INF/")) MergeStrategy.discard else MergeStrategy.first}

mainClass in assembly := Some("wemi.boot.MainKt")