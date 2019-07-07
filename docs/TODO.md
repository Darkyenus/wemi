# Things to do next

## For next release

- Rewrite directorySynchronized based on exclusive file creation and timestamp
	- Make it possible to synchronize on multiple paths (which have to be sorted to prevent deadlock!)
		- This is used in DependencyResolution

- Maven resolution
	- TEST: When resolving snapshots, check both variants for cache first and also check all available repositories if they have cache, before downloading
	- Ensure that when there is a dependency in the graph twice, with different scope, it is resolved correctly: https://cwiki.apache.org/confluence/display/MAVENOLD/Dependency+Mediation+and+Conflict+Resolution (down)
	- Handle certificate problems from Webb: https://pastebin.com/raw/npZHjqft
		- Investigate options to turn off checking on per-repo basis

- Kotlin compiler MUST be the same as wemi kotlin stdlib version! (Or maybe just older or same). 1.30 now fails to compile!

- Allow to generate run script for external tools (profilers, etc.), possibly by printing the executing command line
	- "run dry" ?

## For later
- Test everything, especially javadoc/dokka/tests/plugins

- Fatjars in Java 9+ ?
	- Put all libraries in separate packages?

- Automated tests

- Dynamic "what test is executing right now?", or maybe even results as they come in

- Explain why dokka is in its own project: because I suspect that it bundles its own kotlin stdlib and I don't want that to pollute everything (oh and make sure that that cursed fatjar never gets added to IDE dependencies!!!)

- Consider using https://github.com/Kotlin/KEEP/blob/master/proposals/scripting-support.md

- Maven dependency resolution improvements:
	- <profiles> support
		- https://dzone.com/articles/maven-profile-best-practices
		- https://blog.gradle.org/maven-pom-profiles
	

- In assembling, handle LIST files and signatures

- Add system of automatic MANIFEST.MF generation
	- Manifest files seem to have weird wrapping rules
	- What we have now is inconsistent with build.kt

- Code generation system from build, then get rid of duplicate WemiSettings place

- Allow to build IntelliJ plugins: https://github.com/JetBrains/gradle-intellij-plugin

- New project wizard (IDE)

- Kotlin Multiplatform
	- JS
	- Native
	- When Kotlin multiplatform projects are standardized, consider rewriting compiler facade from maven plugin
    https://github.com/JetBrains/kotlin/tree/master/libraries/tools/kotlin-maven-plugin/src/main/java/org/jetbrains/kotlin/maven
    Currently, maven does not support multiplatform projects, so doing it now is futile.

- Android/MOE/RoboVM/TeaVM plugins

- Maven
	- Investigate Parallel repository resolution
	- Support publishing unique snapshots
	- PGP signatures
	- When generating maven publish metadata, add only those repositories, which were used. And don't add exclude when there are no excludes.