# Things to do next

- BUG: `./wemi trace projectName` calls task `traceprojectName`

- Hotswapping jvm code:
	- Test plugin and continue working on it

- Consider removing sourcesBase and (re)sourceFiles keys. Are they worth the confusion?
	- Maybe introduce something like non-rebindable keys

- Add system of automatic MANIFEST.MF generation

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
