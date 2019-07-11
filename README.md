![WEMI Build System](docs/logo_doc.svg)  
*Wonders Expeditiously, Mundane Instantly*

[![Jitpack badge](https://jitpack.io/v/Darkyenus/wemi.svg)](https://jitpack.io/#Darkyenus/wemi) [![Join the chat at https://gitter.im/wemi-build/community](https://badges.gitter.im/wemi-build/community.svg)](https://gitter.im/wemi-build/community?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Build system aimed at those, who don't want to be limited nor slowed down by their tools.
Key features:
- Simple and expressive
	- Build scripts in Kotlin allow to easily create any functionality with Wemi's DSL, usually in just a few lines of code
	- Convention dominates default settings, but everything is easily reconfigurable
	- Transparent internal structure without magic is easy to debug and understand
- No installation, simple upgrades
	- Whole build system is in single executable (~5MB) that goes directly into the project's directory
- Fast
	- Written with performance and your valuable time in mind
	- Minimal use of libraries means that the code does exactly what it should be, and nothing more
- Works
- Out of the box supports:
	- Java and Kotlin compiling
	- Javadoc, Dokka
	- JUnit 5
	- Artifact assembling (including fat-jars) and publishing

## Overview
This is how a simple build script might look like:
```kotlin
val iceCreamFactory by project {
    projectGroup set { "com.example.ice.cream" }
    projectName set { "ice-cream-factory" }
    projectVersion set { "1.0" }

    libraryDependencies add { dependency("com.example", "ice-provider", "2.1.1") }
    libraryDependencies add { dependency("com.example", "flavor-provider", "2.0.0") }
    
    extend(testing) {
        libraryDependencies add { JUnitAPI }
        libraryDependencies add { JUnitEngine }
        libraryDependencies add { dependency("com.example", "flavor-tester", "2.0.0") }
    }

    mainClass set { "com.example.ice.cream.Main" }
}
```
Whole configuration is stored in key-value pairs, including tasks - there is no distinction between keys that store 
simple static settings and those that store computations. So, for example `libraryDependencies` key binds collection
of Maven library coordinates, and `compile` key binds a code that collects settings from other keys, like library jars
and source files, and invokes the compiler. These dependencies are easily seen with `trace` command.

Key-value entries are not stored globally, but in scopes. Scope is composed of a project and one or more configurations.
For example, in the example above, `JUnitAPI` dependency is added to the `iceCreamFactory/testing` scope, which,
as name suggests, is used when executing unit tests.

Keys have sensible defaults bound to them in the relevant scopes. You can also easily declare your own keys and configurations.

## Getting started
While the project is still work in progress and far from being production ready,
you are welcome to try it out! However remember that everything is subject to change,
so don't get too attached to any of the features or bugs.

1. Start by downloading `wemi` launcher (= the whole build system) and IntelliJ plugin from the releases.
If you don't use IntelliJ, don't worry, Wemi is designed to be used standalone anyway.
2. Then put downloaded `wemi` file directly into the root of your project (see below if that seems weird).
3. Create a build script, in `<your-project-root>/build/build.kt`, with following lines:
```kotlin
// myProject here can be whatever you want, it is the name by which Wemi will refer to your project
val myProject by project {

	// These are Maven coordinates of your project
    projectGroup set { "com.example" }
    projectName set { "MyProject" }
    projectVersion set { "1.0" }

	// This is how Maven-like dependencies are added
    libraryDependencies add { dependency("com.esotericsoftware:kryo:4.0.1") }

	// Main class, if you have any
    mainClass set { "com.example.MyMain" }
}
```
Things like `projectGroup` or `mainClass` above, are keys. Setting any of them is optional, most of them have
sensible default values. There is more to them, check the [design documentation](docs/DESIGN.md).

4. Now you have a build script that can compile and run Java and Kotlin projects. Add your sources to 
`<your-project-root>/src/main/java` and/or `<your-project-root>/src/main/kotlin`.
Dependency on Kotlin's standard library is added by default (if you do not want that, add line 
`libraryDependencies set { emptyList() }`, or change the project's archetype to `wemi.Archetypes.JavaProject`
if you don't want Kotlin at all).
5. Run it! Open your shell with `bash`/`sh` support (sorry, `cmd.exe` won't do) and in your project's root directory, type `./wemi run`.
This will compile the sources, with specified libraries on classpath, and run the specified main class. 

Running `./wemi` by itself will start an interactive session for your tasks. This is actually preferred,
at least when developing, because the compile times are often much shorter.
Type `help` to see what you can do and feel free to experiment.

Also check out examples in [test repositories](test%20repositories) and
the [design document](docs/DESIGN.md) with detailed documentation of Wemi's inner workings.

## Contributing & License
Wemi and its plugins included in this repository are licensed under Mozilla Public License Version 2.0.
IDE plugins are licensed under a more permissive MIT license.

Bug reports and pull requests are welcome, along with any [feedback, questions](https://gitter.im/wemi-build/community), etc.
Feature requests are collected, but without any guarantees on their fulfillment. However, I will be happy to assist anyone
who would wish to contribute or just to understand the code.
