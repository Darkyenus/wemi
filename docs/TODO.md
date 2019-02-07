# Things to do next

## For next release
- Maven snapshots

## For later
- Rewrite directorySynchronized based on exclusive file creation and timestamp

- WEMI IS COMPILED MULTIPLE TIMES!!! cache compile!
	- Warnings in EnclaveCL

- Test everything, especially javadoc/dokka/tests/plugins

- Fatjars in Java 9+ ?
	- Put all libraries in separate packages?

- Automated tests

- Dynamic "what test is executing right now?", or maybe even results as they come in

- Explain why dokka is in its own project: because I suspect that it bundles its own kotlin stdlib and I don't want that to pollute everything (oh and make sure that that cursed fatjar never gets added to IDE dependencies!!!)

- Streamline local dependency retrieval, no need to keep it all in memory, possibility to compute hashes during download

- When Kotlin multiplatform projects are standardized, consider rewriting compiler facade from maven plugin
https://github.com/JetBrains/kotlin/tree/master/libraries/tools/kotlin-maven-plugin/src/main/java/org/jetbrains/kotlin/maven
Currently, maven does not support multiplatform projects, so doing it now is futile.

- Consider using https://github.com/Kotlin/KEEP/blob/master/proposals/scripting-support.md

- Maven dependency resolution improvements:
	- <profiles> support
		- https://dzone.com/articles/maven-profile-best-practices
		- https://blog.gradle.org/maven-pom-profiles
	- Transitive dependencies are not yet affected by dependencyManagement
		- https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Management
		- https://www.davidjhay.com/maven-dependency-management/

- In assembling, handle LIST files and signatures
- Add system of automatic MANIFEST.MF generation
	- Manifest files seem to have weird wrapping rules
	- What we have now is inconsistent with build.kt

- Code generation system from build, then get rid of duplicate WemiSettings place

- Create system, to allow automatic caching of key results, when no input keys have changed.
	- This will need good arbitrary object serialization/comparison - with files serialized as their file/directory/exist info + checksum of files
	- Maybe not serialization, just checksuming... interface Checksumable { String checksum() } ?
	- Source code change detection and caching (Check if worth it)

- Allow to build IntelliJ plugins: https://github.com/JetBrains/gradle-intellij-plugin

- New project wizard (IDE)

- Kotlin Multiplatform
	- Kotlin JS

- Android/MOE/RoboVM/TeaVM plugins

- Maven parallel repository resolution

- PGP signatures

- Kotlin Native
