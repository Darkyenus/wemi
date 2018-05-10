# Things to do next

- Write [UPDATE_PROTOCOLS.md](UPDATE_PROTOCOLS.md)

- Cleanup LocatedFile

- Refactor BuildScriptClasspathConfiguration to store info with classpath in json, this lazy class is weird
	- Change warnings in parsing to errors

- Hotswapping jvm code:
	- Test plugin and continue working on it

- Consider removing sourcesBase and (re)sourceFiles keys. Are they worth the confusion?
	- Maybe introduce something like non-rebindable keys

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
