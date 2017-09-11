# WEMI Build System
*Wonders Expeditiously, Mundane Instantly*

## Why‽
All major Java/Kotlin-building build systems feel clunky and slow.
I don't want to, wait 8 seconds just to load the build script,
I don't want to download hundreds of JARs whenever I want to run on
a different machine, I don't want to read and write baroque XML,
I don't want everything to be macro just because it is slightly "more elegant",
I want to do whatever I want and I don't want to wait for it.


This is my attempt at fixing all that. Even the command name is quick to write!

## How do I use this?
While the project is still WIP and far from being production ready,
you are welcome to try it out! However remember that everything is subject to change,
so don't get too attached to any of the features or bugs.

The project is currently built by sbt, which you will have to use to create
a binary distribution. (By the time you obtain the JAR file,
you will probably understand why I started working on this.)
To get the JAR, install [sbt](http://www.scala-sbt.org) and run `sbt assembly`.

Then somehow alias `wemi` to `java -jar path-to-the-assembled-jar`
in your favourite shell and you are set.

## Getting started
It is too early in the project's lifetime to write a comprehensive getting started guide.
If you are interested, look at the [test repositories](test%20repositories) and
the [design document](DESIGN.md).

## Contributing & License
The code is not yet under any license, but you can still read it.
Likewise, contributions are not accepted by default, but if you want to
join or send feedback, send me a mail!
