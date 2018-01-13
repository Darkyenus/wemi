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

    /**
     * Internal mutable [buildScriptProjects].
     */
    private val buildFileProjectsMutable = HashMap<BuildScript, MutableList<Project>>()

    /**
     * Which [Project]s belong to which BuildScript
     */
    val buildScriptProjects: Map<BuildScript, List<Project>>
        get() = buildFileProjectsMutable

    private val CURRENTLY_INITIALIZED_BUILD_SCRIPT_LOCK = Object()
    private var currentlyInitializedBuildScript: BuildScript? = null
        set(value) {
            if (value != null) {
                buildFileProjectsMutable.getOrPut(value) { mutableListOf() }
            }
            field = value
        }

    internal inline fun <T> initializingBuildScript(buildScript: BuildScript, action: () -> T): T =
            synchronized(CURRENTLY_INITIALIZED_BUILD_SCRIPT_LOCK) {
                currentlyInitializedBuildScript = buildScript
                val result = action()
                currentlyInitializedBuildScript = null
                result
            }

    @Suppress("unused")
    val wemiBuildScript by configuration("Setup with information about the build script " +
            "(but not actually used for building the build script)") {
        Keys.repositories set { Keys.buildScript.get().buildScriptClasspathConfiguration.repositories }
        Keys.repositoryChain set { Keys.buildScript.get().buildScriptClasspathConfiguration.repositoryChain }
        Keys.libraryDependencies set { Keys.buildScript.get().buildScriptClasspathConfiguration.dependencies }
        Keys.externalClasspath set { Keys.buildScript.get().classpath.map { LocatedFile(it) } }
        Keys.compilerOptions set { Keys.buildScript.get().buildFlags }
        Keys.compile set { Keys.buildScript.get().scriptJar }
        Keys.resourceFiles set { emptyList() }
        Keys.sourceFiles set { Keys.buildScript.get().sources }
    }

    internal fun Project.initializeBuildScriptInfo() {
        val buildScript = currentlyInitializedBuildScript
        if (buildScript == null) {
            LOG.debug("Project {} is being initialized at unexpected time, introspection will not be available", this)
        } else {
            buildFileProjectsMutable.getValue(buildScript).add(this)
            Keys.buildScript set { buildScript }
        }

    }
}




