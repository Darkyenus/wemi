package wemi.boot

import configuration
import org.slf4j.LoggerFactory
import wemi.Keys
import wemi.Project
import wemi.util.LocatedFile

/**
 * Internal data used for handling build script introspection data
 */
object BuildScriptIntrospection {

    private val LOG = LoggerFactory.getLogger(javaClass)

    private val _buildFileProjects = mutableMapOf<BuildScript, MutableList<Project>>()

    val buildScriptProjects: Map<BuildScript, List<Project>>
        get() = _buildFileProjects

    internal var currentlyInitializedBuildScript: BuildScript? = null
        set(value) {
            if (value != null) {
                _buildFileProjects.getOrPut(value){ mutableListOf() }
            }
            field = value
        }

    private fun throwIntrospectionInfoNotAvailable():Nothing {
        throw IllegalStateException("Introspection info not available")
    }

    @Suppress("MemberVisibilityCanPrivate")
    val wemiBuildScript by configuration("Setup with information about the build script " +
            "(but not actually used for building the build script)") {
        Keys.classpath set { throwIntrospectionInfoNotAvailable() }
        Keys.compilerOptions set { throwIntrospectionInfoNotAvailable() }
        Keys.compile set { throwIntrospectionInfoNotAvailable() }
        Keys.sourceFiles set { throwIntrospectionInfoNotAvailable() }
    }

    internal fun Project.initializeBuildScriptInfo() {
        val buildFile = currentlyInitializedBuildScript
        if (buildFile == null) {
            LOG.debug("Project {} is being initialized at unexpected time, introspection will not be available", this)
        } else {
            _buildFileProjects.getValue(buildFile).add(this)

            extend (wemiBuildScript) {
                Keys.repositories set { buildFile.buildScriptClasspathConfiguration.repositories }
                Keys.repositoryChain set { buildFile.buildScriptClasspathConfiguration.repositoryChain }
                Keys.libraryDependencies set { buildFile.buildScriptClasspathConfiguration.dependencies }
                Keys.classpath set { buildFile.classpath.map { LocatedFile(it) } }
                Keys.compilerOptions set { buildFile.buildFlags }
                Keys.compile set { buildFile.scriptJar }
                Keys.sourceFiles set { buildFile.sources }
            }
        }

    }
}




