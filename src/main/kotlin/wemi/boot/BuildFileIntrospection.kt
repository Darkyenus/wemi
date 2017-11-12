package wemi.boot

import configuration
import org.slf4j.LoggerFactory
import wemi.Keys
import wemi.Project
import wemi.util.LocatedFile

/**
 * Internal data used for handling build script introspection data
 */
object BuildFileIntrospection {

    private val LOG = LoggerFactory.getLogger(javaClass)

    private val _buildFileProjects = mutableMapOf<BuildFile, MutableList<Project>>()

    val buildFileProjects : Map<BuildFile, List<Project>>
        get() = _buildFileProjects

    internal var currentlyInitializedBuildFile:BuildFile? = null
        set(value) {
            if (value != null) {
                _buildFileProjects.getOrPut(value){ mutableListOf() }
            }
            field = value
        }

    @Suppress("MemberVisibilityCanPrivate")
    val wemiBuildScript by configuration("Setup with information about the build script " +
            "(but not actually used for building the build script)") {
        Keys.classpath set { throw IllegalStateException("Introspection info not available") }
        Keys.mainClass set { throw IllegalStateException("Introspection info not available") }
        Keys.compilerOptions set { throw IllegalStateException("Introspection info not available") }
        Keys.compile set { throw IllegalStateException("Introspection info not available") }
        Keys.sourceFiles set { throw IllegalStateException("Introspection info not available") }
    }

    internal fun Project.initializeBuildScriptInfo() {
        val buildFile = currentlyInitializedBuildFile
        if (buildFile == null) {
            LOG.debug("Project {} is being initialized at unexpected time, introspection will not be available", this)
        } else {
            _buildFileProjects.getValue(buildFile).add(this)

            wemiBuildScript extend {
                Keys.repositories set { buildFile.buildFileClasspathConfiguration.repositories }
                Keys.repositoryChain set { buildFile.buildFileClasspathConfiguration.repositoryChain }
                Keys.libraryDependencies set { buildFile.buildFileClasspathConfiguration.dependencies }
                Keys.classpath set { buildFile.classpath.map { LocatedFile(it) } }
                Keys.mainClass set { buildFile.initClass }
                Keys.compilerOptions set { buildFile.buildFlags }
                Keys.compile set { buildFile.scriptJar }
                Keys.sourceFiles set { buildFile.sources }
            }
        }

    }
}




