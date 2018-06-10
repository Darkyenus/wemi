# Things to do next

- Maven dependency resolution improvements:
	- <profiles> support
		- https://dzone.com/articles/maven-profile-best-practices
		- https://blog.gradle.org/maven-pom-profiles
	- Transitive dependencies are not yet affected by dependencyManagement
		- https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Management
		- https://www.davidjhay.com/maven-dependency-management/

- Hotswapping jvm code:
	- Test plugin and continue working on it
	- Set env variable or java property of the process that is being hotswapped and change it after every hotswap, so the process can reinitialize what is needed
	- WEMI DOES NOT UNBIND!!!

- Make jitpack builds work
	- https://jitpack.io/docs/#publishing-on-jitpack
	- https://jitpack.io/docs/BUILDING/#multi-module-projects

- Add system of automatic MANIFEST.MF generation
- In assembling, handle LIST files and signatures
- Manifest files seem to have weird wrapping rules

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
