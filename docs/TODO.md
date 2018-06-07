# Things to do next

- `wemiBuildScript` configuration is used for downloading docs and sources in ide plugin
- `#buildScript` machine command is used to access stuff, don't allow that

- Implement https://maven.apache.org/pom.html#Dependency_Management (Guava needs it)
	- Also look into <profiles>???

- When importing, as much keys as possible should be optional.
	- allow import without any build scripts
	- allow import without any modules
	- don't require projectGroup!!!

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

- Allow to build IntelliJ plugins: https://github.com/JetBrains/gradle-intellij-plugin

- New project wizard (IDE)

- Kotlin Multiplatform
	- Kotlin JS

- Android/MOE/RoboVM/TeaVM plugins

- Source code change detection and caching (Check if worth it)

- Maven parallel repository resolution

- PGP signatures

- Kotlin Native
