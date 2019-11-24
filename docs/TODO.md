# Things to do next

- IDE rewrite:
    - Before run does not work yet
    - Remove what is not needed anymore
    - Check all TODOs
- Java 13 invalid reflexive access
- http://tutorials.jenkov.com/java/modules.html
- IDE: Run Main from whatever buttons
- Unit test output from Wemi in IDE
- DO NOT USE `extend(compiling or whatever)` to denote scopes!
    It is flawed and will cause runtime only dependencies to appear in compile classpath!

## For next release

## For later
- Test everything, especially javadoc/dokka/tests/plugins

- Fatjars in Java 9+ ?
	- Put all libraries in separate packages?
	- Maybe we don't need fatjars, because Java 9 has jlink

- Automated tests

- Dynamic "what test is executing right now?", or maybe even results as they come in

- Explain why dokka is in its own project: because I suspect that it bundles its own kotlin stdlib and I don't want that to pollute everything (oh and make sure that that cursed fatjar never gets added to IDE dependencies!!!)

- Consider using https://github.com/Kotlin/KEEP/blob/master/proposals/scripting-support.md

- Maven dependency resolution improvements:
	- `<profiles>` support
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