package wemi.boot

import com.esotericsoftware.jsonbeans.JsonValue
import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.LoggerFactory
import wemi.*
import wemi.collections.*
import wemi.compile.CompilerFlags
import wemi.compile.KotlinCompiler
import wemi.compile.KotlinCompilerFlags
import wemi.compile.KotlinJVMCompilerFlags
import wemi.dependency.*
import wemi.plugin.PluginEnvironment
import wemi.util.*
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

private val LOG = LoggerFactory.getLogger("BuildScript")

/**
 * Build file is a file with `.kt` extension, in [buildFolder].
 */
internal fun findBuildScriptSources(buildFolder: Path): List<Path> {
    var result: ArrayList<Path>? = null

    if (buildFolder.isDirectory()) {
        Files.newDirectoryStream(buildFolder).use { stream ->
            for (path in stream) {
                if (!path.isDirectory() && !path.isHidden() && !path.name.startsWith('.')
                        && path.name.pathHasExtension("kt")) {
                    var r = result
                    if (r == null) {
                        r = ArrayList()
                        result = r
                    }
                    r.add(path)
                }
            }
        }
    }

    return result ?: emptyList()
}

/**
 * Retrieve the compiled build script.
 *
 * Tries to use existing files before compiling.
 */
internal fun getBuildScript(cacheFolder: Path, buildScriptSources: List<Path>, forceCompile: Boolean): BuildScriptInfo? {
    if (cacheFolder.exists() && !cacheFolder.isDirectory()) {
        LOG.error("Build directory {} exists and is not a directory", cacheFolder)
        return null
    } else if (!cacheFolder.exists()) {
        try {
            Files.createDirectories(cacheFolder)
        } catch (e: IOException) {
            LOG.error("Could not create build directory {}", cacheFolder, e)
            return null
        }
    }

    val resultJar = cacheFolder / "build.jar"
    val buildScriptInfoFile = cacheFolder / "build-info.json"
    val buildScriptInfo = BuildScriptInfo(resultJar, buildScriptSources.map { LocatedPath(it) }.toWList())

    var recompileReason = ""

    val recompile:Boolean = run recompile@{
        if (forceCompile) {
            recompileReason = "Requested"
            LOG.debug("Rebuilding build scripts: Requested")
            return@recompile true
        }
        if (!resultJar.exists() || resultJar.isDirectory()) {
            recompileReason = "No cache"
            LOG.debug("Rebuilding build scripts: No cache at {}", resultJar)
            return@recompile true
        }
        val jarLastModified = resultJar.lastModified
        if (buildScriptSources.any { source -> source.lastModified > jarLastModified }) {
            recompileReason = "Script changed"
            LOG.debug("Rebuilding build scripts: Old cache")
            return@recompile true
        }
        if (!buildScriptInfoFile.isRegularFile()) {
            recompileReason = "Missing cache metadata"
            LOG.debug("Rebuilding build scripts: No classpath cache")
            return@recompile true
        }
        if (Magic.WemiLauncherFile.lastModified > resultJar.lastModified) {
            recompileReason = "Updated launcher"
            LOG.debug("Rebuilding build scripts: Launcher updated ({})", Magic.WemiLauncherFile)
            return@recompile true
        }

        // Load the build-script info json
        try {
            Files.newBufferedReader(buildScriptInfoFile, Charsets.UTF_8).use {
                it.readJsonTo(buildScriptInfo)
            }
        } catch (e:Exception) {
            recompileReason = "Can't read build-script info"
            LOG.debug("Failed to build script info from {}", buildScriptInfoFile, e)
            return@recompile true
        }

        // Validate loaded data
        if (buildScriptInfo.unmanagedDependencies.isEmpty()) {
            recompileReason = "Corrupted build-script info"
            LOG.debug("Rebuilding build scripts: Missing Wemi launcher entry in classpath")
            return@recompile true
        }

        for (classpathEntry in buildScriptInfo.unmanagedDependencies) {
            if (!classpathEntry.exists()) {
                recompileReason = "Corrupted build-script info: classpath entries missing"
                LOG.debug("Rebuilding build scripts: Classpath cache corrupted ({} does not exist)", classpathEntry)
                return@recompile true
            }
        }

        // All is fine
        return@recompile false
    }

    // Do preparation of build-script info
    if (recompile) {
        LOG.info("Compiling build script: {}", recompileReason)
        resultJar.deleteRecursively()
        buildScriptInfo.resolve(cacheFolder)

        try {
            Files.newBufferedWriter(buildScriptInfoFile, Charsets.UTF_8).use {
                it.writeJson(buildScriptInfo, BuildScriptInfo::class.java)
            }
        } catch (e:Exception) {
            LOG.warn("Failed to save build-script info, next run will have to construct it again", e)
        }
        // Compilation is handled lazily later
    }

    buildScriptInfo.recompilationNeeded = recompile
    return buildScriptInfo
}

private fun wemiLauncherFileWithJarExtension(cacheFolder: Path): Path {
    val wemiLauncherFile = Magic.WemiLauncherFile
    if (wemiLauncherFile.name.endsWith(".jar", ignoreCase = true) || wemiLauncherFile.isDirectory()) {
        LOG.debug("wemiLauncherFileWithJarExtension used unchanged {}", wemiLauncherFile)
        return wemiLauncherFile
    }
    // We have create a link to/copy of the launcher file somewhere and name it with .jar
    val linked = cacheFolder / "wemi.jar"

    if (Files.exists(linked) && Files.exists(wemiLauncherFile.toRealPath())
            && (Files.isSameFile(linked, wemiLauncherFile) // If link
            || Files.getLastModifiedTime(wemiLauncherFile) < Files.getLastModifiedTime(linked) // If copy
                    )) {
        // Already exists and is fresh
        LOG.debug("wemiLauncherFileWithJarExtension is existing {}", linked)
        return linked
    }

    Files.deleteIfExists(linked)

    try {
        val result = Files.createSymbolicLink(linked, wemiLauncherFile)
        LOG.debug("wemiLauncherFileWithJarExtension is soft-linked {}", result)
        return result
    } catch (e: Exception) {
        LOG.debug("Failed to soft-link {} to {}", wemiLauncherFile, linked, e)
    }
    try {
        val result = Files.createLink(linked, wemiLauncherFile)
        LOG.debug("wemiLauncherFileWithJarExtension is hard-linked {}", result)
        return result
    } catch (e: Exception) {
        LOG.debug("Failed to hard-link {} to {}", wemiLauncherFile, linked, e)
    }

    try {
        Files.copy(wemiLauncherFile, linked)
        LOG.debug("WemiLauncherFileWithJar is copied {}", linked)
        return linked
    } catch (e: Exception) {
        LOG.warn("Failed to link or copy {} to {}, operations requiring Wemi launcher as jar will probably fail", wemiLauncherFile, linked, e)
        return wemiLauncherFile
    }
}

internal fun createProjectFromBuildScriptInfo(buildScriptInfo:BuildScriptInfo?): Project {
    return Project(WemiBuildScriptProjectName, WemiBuildFolder, arrayOf(BuildScript)).apply {
        Keys.projectName set { WemiBuildScriptProjectName }
        Keys.projectRoot set { WemiBuildFolder }

        if (buildScriptInfo != null) {
            Keys.repositories set { WMutableSet(buildScriptInfo.repositories) }
            Keys.libraryDependencies set { WMutableSet(buildScriptInfo.dependencies) }
            Keys.unmanagedDependencies set {
                val dependencies = WMutableList<LocatedPath>()
                for (unmanagedDependency in buildScriptInfo.unmanagedDependencies) {
                    dependencies.add(LocatedPath(unmanagedDependency))
                }
                dependencies
            }
            Keys.sourceFiles set { buildScriptInfo.sources }

            Keys.compile set {
                val resultJar = buildScriptInfo.scriptJar
                if (!buildScriptInfo.recompilationNeeded) {
                    return@set resultJar
                }

                val sources = Keys.sourceFiles.get()
                val externalClasspath = Keys.externalClasspath.get()
                LOG.debug("Compiling sources: {} classpath: {} resultJar: {}", sources, externalClasspath, resultJar)

                val status = WemiKotlinVersion.compilerInstance().compileJVM(sources, externalClasspath.map { it.classpathEntry }, resultJar, null, Keys.compilerOptions.get(), LoggerFactory.getLogger("BuildScriptCompilation"), null)
                if (status != KotlinCompiler.CompileExitStatus.OK) {
                    LOG.warn("Compilation failed for {}: {}", sources, status)
                    throw WemiException.CompilationException("Build script failed to compile: $status")
                }

                buildScriptInfo.recompilationNeeded = false
                return@set resultJar
            }
        } else {
            Keys.internalClasspath set { wEmptyList() }
            Keys.run set { 0 }
        }

        locked = true
    }
}

/**
 * Metadata about build script, needed to compile it.
 *
 * Obtained initially by resolving from source, then by loading from json.
 *
 * Also handles it's compilation, internally.
 */
class BuildScriptInfo internal constructor(
        /** jar to which the build script has been compiled */
        val scriptJar: Path,
        /** source files, from which the build script is compiled */
        val sources: WList<LocatedPath>) : JsonReadable, JsonWritable {

    private val _repositories = HashSet<Repository>()
    private val _dependencies = HashSet<Dependency>()
    // WemiLauncherFile is the first entry of this list
    private val _unmanagedDependencies = ArrayList<Path>()

    val repositories:Set<Repository>
        get() = _repositories
    val dependencies:Set<Dependency>
        get() = _dependencies
    val unmanagedDependencies:List<Path>
        get() = _unmanagedDependencies

    var recompilationNeeded:Boolean = true

    private fun consumeDirective(annotation:Class<out Annotation>, fields:Array<String>) {
        when (annotation) {
            BuildDependency::class.java -> {
                val (groupOrFull, name, version) = fields
                if (name.isEmpty() && version.isEmpty()) {
                    _dependencies.add(Dependency(wemi.dependencyId(groupOrFull, null), WemiBundledLibrariesExclude))
                } else {
                    _dependencies.add(Dependency(DependencyId(groupOrFull, name, version), WemiBundledLibrariesExclude))
                }
            }
            BuildDependencyRepository::class.java -> {
                val (name, url) = fields

                _repositories.add(Repository.M2(name, URL(url), LocalM2Repository))
            }
            BuildClasspathDependency::class.java -> {
                val (file) = fields

                var path = Paths.get(file)
                if (!path.isAbsolute) {
                    path = WemiRootFolder.resolve(path)
                }

                try {
                    path = path.toRealPath()
                } catch (e:IOException) {
                    LOG.warn("BuildClasspathDependency - file not found and ignored {}", path)
                    return
                }

                _unmanagedDependencies.add(path)
            }
            else -> {
                throw AssertionError(annotation)//Not possible
            }
        }
    }

    internal fun resolve(cacheFolder:Path):Boolean {
        _repositories.clear()
        _dependencies.clear()
        _unmanagedDependencies.clear()

        // Wemi also contains Kotlin runtime
        _unmanagedDependencies.add(wemiLauncherFileWithJarExtension(cacheFolder))
        _repositories.addAll(DefaultRepositories)

        for (sourceFile in sources) {
            val success = try {
                Files.newBufferedReader(sourceFile.file, Charsets.UTF_8).use {
                    parseFileDirectives(it, SupportedDirectives, ::consumeDirective)
                }
            } catch (e:Exception) {
                LOG.warn("Failed to read directives from {}", sourceFile, e)
                false
            }

            if (!success) {
                return false
            }
        }

        return true
    }

    override fun JsonWriter.write() {
        writeObject {
            fieldCollection("repositories", _repositories)
            fieldCollection("dependencies", _dependencies)
            fieldCollection("unmanagedDependencies", _unmanagedDependencies)
        }
    }

    override fun read(value: JsonValue) {
        value.fieldToCollection("repositories", _repositories)
        value.fieldToCollection("dependencies", _dependencies)
        value.fieldToCollection("unmanagedDependencies", _unmanagedDependencies)
    }

    override fun toString(): String {
        return "BuildScriptInfo(scriptJar=$scriptJar, sources=$sources, _repositories=$_repositories, _dependencies=$_dependencies, _unmanagedDependencies=$_unmanagedDependencies)"
    }
}

/**
 * Special archetype only for build-script meta-project.
 */
internal val BuildScript by archetype(Archetypes::Base) {

    Keys.compilerOptions set {
        CompilerFlags().also {
            it[KotlinCompilerFlags.moduleName] = WemiBuildScriptProjectName
            it[KotlinJVMCompilerFlags.jvmTarget] = "1.8"
        }
    }

    Keys.run set {
        // Load build script configuration
        val internal = Keys.internalClasspath.get()
        val external = Keys.externalClasspath.get()
        val urls = arrayOfNulls<URL>(internal.size + external.size)
        var i = 0
        for (path in internal) {
            urls[i++] = path.classpathEntry.toUri().toURL()
        }
        for (path in external) {
            urls[i++] = path.classpathEntry.toUri().toURL()
        }

        val buildScriptClassloader = URLClassLoader(urls, Magic.WemiDefaultClassLoader)

        LOG.debug("Loading plugins...")
        val pluginServiceLoader = ServiceLoader.load(PluginEnvironment::class.java, buildScriptClassloader)
        for (pluginService in pluginServiceLoader) {
            LOG.debug("Loading plugin service {}", pluginService)

            pluginService.initialize()
        }

        LOG.debug("Loading build...")
        var result = 0

        // Main classes of the build script, that should be initialized to load the build script.
        val initClasses = Keys.sourceFiles.get().map { Magic.transformFileNameToKotlinClassName(it.file.name.pathWithoutExtension()) }

        for (initClass in initClasses) {
            try {
                Class.forName(initClass, true, buildScriptClassloader)
            } catch (e: Exception) {
                LOG.warn("Failed to load build file class {} from {}", initClass, urls, e)
                result++
            }
        }

        LOG.debug("Build script loaded")
        result
    }

}
