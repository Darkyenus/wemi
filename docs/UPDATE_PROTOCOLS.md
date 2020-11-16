*Procedures to follow, when updating something.*

# Wemi Version update
0. Update dependencies
1. Update changes in
    - [Changelog](../CHANGES.md)
    - [IDEA plugin changelog](../ide-plugins/intellij/src/main/plugin.xml)
2. Create and push commit named "Version 0.0" + version tag "0.0"
3. Run `./build/publish-version.sh`
4. Create github release, with binary builds of IDEA plugin and wemi itself
5. Update wemi used by Wemi, update build script

# New Kotlin Version
1. Add it to [CompilerProjects in build script](../build/build.kt)
2. Create relevant [plugin interface project](../src/main-kotlinc)
3. Add it to [KotlinCompilerVersion enum](../src/main/kotlin/wemi/compile/KotlinCompiler.kt)
4. Update Dokka, if necessary

# New Java Version
- Check [BytecodeUtil](../plugins/jvm-hotswap/src/main/java/wemiplugin/jvmhotswap/agent/BytecodeUtil.java)
	and update `.class` parsing logic.
- Update `javadocUrl` in [KeyDefaults](../src/main/kotlin/wemi/KeyDefaults.kt)
