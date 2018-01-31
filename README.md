# WEMI Build System
*Wonders Expeditiously, Mundane Instantly*

## Why‽
All major Java/Kotlin-building build systems feel clunky and slow.
I don't want to, wait 8 seconds just to load the build script,
I don't want to download hundreds of JARs whenever I want to run on
a different machine, I don't want to read and write baroque XML,
I don't want everything to be a magical macro just because it solves problems that I don't have,
I want to do whatever I want and I don't want to wait for it.


This is my attempt at fixing all that. Even the command name is quick to write!

## Getting started
While the project is still work in progress and far from being production ready,
you are welcome to try it out! However remember that everything is subject to change,
so don't get too attached to any of the features or bugs.

1. Start by downloading `wemi` launcher (= the whole build system) and IntelliJ plugin from the releases.
If you don't use IntelliJ, don't worry, Wemi is designed to be used standalone.
2. Then put downloaded `wemi` file directly into the root of your project (yes, see below if that seems weird).
3. Create a build script, in `<your-project-root>/build/build.wemi`, with following lines:
```kotlin
// myProject here can be whatever you want, it is the name by which Wemi will refer to your project
val myProject by project {

	// These are Maven coordinates of your project
    projectGroup set { "com.example" }
    projectName set { "MyProject" }
    projectVersion set { "1.0" }

	// This is how Maven-like dependencies are added
    //libraryDependencies add { dependency("com.esotericsoftware:kryo:4.0.1") }

	// Main class, if you have any
    mainClass set { "com.example.MyMain" }
}
```
All `.wemi` files are like Kotlin `.kt` files, just different extension.
Things like `projectGroup` or `mainClass` above, are keys. Setting any of them is optional, most of them have
sensible default values. There is more to them, check the [design documentation](DESIGN.md).
4. Now you have a build script that can compile and run Java and Kotlin projects. Add your sources to 
`<your-project-root>/src/main/java` and/or `<your-project-root>/src/main/kotlin`.
Dependency on Kotlin's standard library is added by default (if you do not want that, add line 
`libraryDependencies set { emptyList() }`, or change the project's archetype to `wemi.Archetypes.JavaProject`
if you don't want Kotlin at all) .
5. Run it! Open your shell with `bash` support (sorry, `cmd.exe` won't do) and in your project's root directory, type `./wemi run`.
This will compile the sources, with specified libraries on classpath, and run the specified main class. 

Running `./wemi` by itself will start an interactive session for your tasks. This is actually preferred,
at least when developing, because the compile times are often much shorter.

And that is it. If you are interested, look at the examples in [test repositories](test%20repositories) and
the [design document](DESIGN.md) with detailed documentation of Wemi's inner workings.

## Contributing & License
The code is not yet under any license, but you can still read it.
Likewise, contributions are not accepted by default - yet, but if you want to
join the effort or send feedback, send me a mail!
