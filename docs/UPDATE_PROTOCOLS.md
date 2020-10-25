*Procedures to follow, when updating something.*

# Wemi Version update
1. Change versions in
	- [IDEA plugin](../ide-plugins/WemiForIntelliJ/resources/META-INF/plugin.xml)
2. Update changes in
    - [Changelog](../CHANGES.md)
    - [IDEA plugin changelog](../ide-plugins/WemiForIntelliJ/resources/META-INF/plugin.xml)
3. Create and push commit named "Version 0.0" + version tag "v0.0"
4. Create github release, with binary builds of IDEA plugin and wemi itself
    1. Delete `WemiForIntelliJ.zip`, if it exists
	2. Run `./build/publish-version.sh`
5. Create commit that changes versions to <NEXT_VERSION>-SNAPSHOT
6. Update wemi used by Wemi, update build script

# New Kotlin Version
1. Add it to [CompilerProjects in build script](../build/build.kt)
2. Create relevant [plugin interface project](../src/main-kotlinc)
3. Add it to [KotlinCompilerVersion enum](../src/main/kotlin/wemi/compile/KotlinCompiler.kt)

# New Java Version
- Check [BytecodeUtil](../plugins/jvm-hotswap/src/main/java/wemiplugin/jvmhotswap/agent/BytecodeUtil.java)
	and update `.class` parsing logic.
- Update `javadocUrl` in [KeyDefaults](../src/main/kotlin/wemi/KeyDefaults.kt)
