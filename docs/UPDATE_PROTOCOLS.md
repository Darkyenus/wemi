*Procedures to follow, when updating something.*

# Wemi Version update
1. Change versions in
	- [BuildScript](../build/build.wemi.kt)
	- [Wemi](../src/main/kotlin/wemi/Wemi.kt)
	- [IDEA plugin](../ide-plugins/WemiForIntelliJ/resources/META-INF/plugin.xml)
2. Update changes in
    - [Changelog](../CHANGES.md)
    - [IDEA plugin changelog](../ide-plugins/WemiForIntelliJ/resources/META-INF/plugin.xml)
3. Create and push git commit
4. Create github release, with binary builds of IDEA plugin and wemi itself
5. Create and push commit that changes versions to <NEXT_VERSION>-SNAPSHOT

# New Kotlin Version
1. Add it to [CompilerProjects in build script](../build/build.wemi.kt)
2. Create relevant [plugin interface project](../src/main-kotlinc)
3. Add it to [KotlinCompilerVersion enum](../src/main/kotlin/wemi/compile/KotlinCompiler.kt)
4. Consider bumping up the [WemiKotlinVersion used for build scripts](../src/main/kotlin/wemi/Wemi.kt).
This propagates to `kotlinVersion` key, used to compile user projects.

# New Java Version
- Check [BytecodeUtil](../plugins/jvm-hotswap/src/main/kotlin/wemiplugin/jvmhotswap/agent/BytecodeUtil.java)
	and update `.class` parsing logic.