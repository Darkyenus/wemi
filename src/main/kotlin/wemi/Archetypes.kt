@file:Suppress("MemberVisibilityCanBePrivate", "unused", "ObjectPropertyName")

package wemi

import wemi.boot.WemiRunningInInteractiveMode
import wemi.compile.CompilerFlags
import wemi.dependency.DefaultRepositories
import wemi.dependency.createRepositoryChain
import wemi.run.javaExecutable
import wemi.util.div
import javax.tools.ToolProvider

/**
 * Default Wemi project [Archetype]s.
 *
 * There are two kinds of archetypes. Primary and secondary.
 * Project can have only one primary archetype, as those setup things like compiler, source folders,
 * and generally dictate what language project will use and what will be the result of [Keys.compile].
 *
 * Secondary archetypes, on the other hand, add additional functionality on top of existing project.
 * There may be zero or more secondary archetypes, although this depends on the functionality provided.
 *
 * Base archetypes whose names are surrounded with underscores (_) are not meant for direct use.
 */
object Archetypes {

    /**
     * Archetypal base. All primary archetypes must use this as a parent archetype.
     * Don't use this directly in [Project], this is meant for [Archetype] creators.
     *
     * Implements basic key implementations that don't usually change.
     */
    val _Base_ by archetype {
        Keys.input set { InputBase(WemiRunningInInteractiveMode) }

        Keys.buildDirectory set { Keys.projectRoot.get() / "build" }
        Keys.sourceBases set { listOf(Keys.projectRoot.get() / "src/main") }
        Keys.sourceFiles set KeyDefaults.SourceFiles
        Keys.resourceRoots set KeyDefaults.ResourceRoots
        Keys.resourceFiles set KeyDefaults.ResourceFiles

        Keys.repositories set { DefaultRepositories }
        Keys.repositoryChain set { createRepositoryChain(Keys.repositories.get()) }
        Keys.resolvedLibraryDependencies set KeyDefaults.ResolvedLibraryDependencies
        Keys.internalClasspath set KeyDefaults.InternalClasspath
        Keys.externalClasspath set KeyDefaults.ExternalClasspath

        Keys.clean set KeyDefaults.Clean

        Keys.javaHome set { wemi.run.JavaHome }
        Keys.javaExecutable set { javaExecutable(Keys.javaHome.get()) }
        Keys.javaCompiler set { ToolProvider.getSystemJavaCompiler() }
        Keys.outputClassesDirectory set KeyDefaults.outputClassesDirectory("classes")
        Keys.outputSourcesDirectory set KeyDefaults.outputClassesDirectory("sources")
        Keys.outputHeadersDirectory set KeyDefaults.outputClassesDirectory("headers")
        Keys.compilerOptions set { CompilerFlags() }

        Keys.runDirectory set { Keys.projectRoot.get() }
    }

    /**
     * Archetypal base for projects that run on JVM.
     * Don't use this directly in [Project], this is meant for [Archetype] creators.
     *
     * Implements basic key implementations for JVM that don't usually change.
     */
    val _JVMBase_ by archetype(::_Base_) {
        //Keys.mainClass TODO Detect main class?
        Keys.runOptions set KeyDefaults.RunOptions
        Keys.run set KeyDefaults.Run
        Keys.runMain set KeyDefaults.RunMain

        Keys.testParameters set KeyDefaults.TestParameters
        Keys.test set KeyDefaults.Test

        Keys.assemblyMergeStrategy set KeyDefaults.AssemblyMergeStrategy
        Keys.assemblyRenameFunction set KeyDefaults.AssemblyRenameFunction
        Keys.assemblyOutputFile set { Keys.buildDirectory.get() / (Keys.projectName.get() + "-" + Keys.projectVersion.get() + "-assembly.jar") }
        Keys.assembly set KeyDefaults.Assembly
    }

    //region Primary Archetypes

    /**
     * Primary archetype for projects that use pure Java.
     */
    val JavaProject by archetype(::_JVMBase_) {
        Keys.compile set KeyDefaults.CompileJava
    }

    /**
     * Primary archetype for projects that use Java and Kotlin.
     */
    val JavaKotlinProject by archetype(::_JVMBase_) {
        Keys.libraryDependencies set { listOf(kotlinDependency("stdlib")) }
        Keys.compile set KeyDefaults.CompileJavaKotlin
    }

    //endregion

}