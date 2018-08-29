# Things to do next

- Explicit cache system

- System for static (non-lazy) values

- Do not keep around the data content of downloaded libraries after their caching, creates a memory leak

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
