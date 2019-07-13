package wemi.boot

import com.esotericsoftware.jsonbeans.JsonValue
import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.LoggerFactory
import wemi.*
import wemi.collections.WMutableList
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
 * Retrieve the compiled build script.
 *
 * Tries to use existing files before compiling.
 */
internal fun getBuildScript(cacheFolder: Path, buildScriptSourceSet:FileSet, buildScriptSources: List<Path>, forceCompile: Boolean): BuildScriptInfo? {
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
    val buildScriptInfo = BuildScriptInfo(resultJar, buildScriptSourceSet, buildScriptSources, WemiRuntimeClasspath)

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
                recompileReason = "Corrupted build-script info: unmanaged classpath entries missing"
                LOG.debug("Rebuilding build scripts: Classpath cache corrupted ({} does not exist)", classpathEntry)
                return@recompile true
            }
        }

        for (classpathEntry in buildScriptInfo.managedDependencies) {
            if (!classpathEntry.exists()) {
                recompileReason = "Corrupted build-script info: managed classpath entries missing"
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
        if (!buildScriptInfo.resolve()) {
            // Dependency resolution failed
            return null
        }

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

internal fun createProjectFromBuildScriptInfo(buildScriptInfo:BuildScriptInfo?): Project {
    return Project(WemiBuildScriptProjectName, WemiBuildFolder, emptyArray()).apply {
        Keys.projectName set Static(WemiBuildScriptProjectName)
        Keys.projectRoot set Static(WemiBuildFolder)
        Keys.cacheDirectory set Static (WemiCacheFolder)
        Keys.compilerOptions set Static (BuildScriptInfo.compilerOptions)

        if (buildScriptInfo != null) {
            Keys.repositories set Static(buildScriptInfo.repositories)
            Keys.libraryDependencies set Static(buildScriptInfo.dependencies)
            Keys.unmanagedDependencies set LazyStatic {
                val dependencies = WMutableList<LocatedPath>()
                for (unmanagedDependency in buildScriptInfo.unmanagedDependencies) {
                    dependencies.add(LocatedPath(unmanagedDependency))
                }
                dependencies
            }
            Keys.sources set Static(buildScriptInfo.sourceSet)
            Keys.externalClasspath set LazyStatic {
                val result = ArrayList<LocatedPath>(buildScriptInfo.unmanagedDependencies.size + buildScriptInfo.managedDependencies.size)
                for (dependency in buildScriptInfo.unmanagedDependencies) {
                    result.add(LocatedPath(dependency))
                }
                for (dependency in buildScriptInfo.managedDependencies) {
                    result.add(LocatedPath(dependency))
                }
                result
            }
            Keys.internalClasspath set Static(listOf(LocatedPath(buildScriptInfo.scriptJar)))
        } else {
            Keys.internalClasspath set Static(emptyList())
            Keys.run set Static(0)
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
        /** Source set from which [sources] were generated */
        val sourceSet:FileSet,
        /** source files, from which the build script is compiled */
        val sources: List<Path>,
        /** Jars with wemi, kotlin runtime, etc. */
        private val runtimeClasspath: List<Path>) : JsonReadable, JsonWritable {

    // NOTE: Only these fields are (de)serialized to/from json
    private val _repositories = HashSet<Repository>()
    private val _dependencies = HashSet<Dependency>()
    // WemiLauncherFile is the first entry of this list
    private val _unmanagedDependencies = ArrayList<Path>()
    private val _managedDependencies = ArrayList<Path>()

    val repositories:Set<Repository>
        get() = _repositories
    val dependencies:Set<Dependency>
        get() = _dependencies
    val unmanagedDependencies:List<Path>
        get() = _unmanagedDependencies
    /** Resolved [dependencies] retrieved from [repositories]. */
    val managedDependencies:List<Path>
        get() = _managedDependencies

    var recompilationNeeded:Boolean = true

    private fun consumeDirective(annotation:Class<out Annotation>, fields:Array<String>) {
        when (annotation) {
            BuildDependency::class.java -> {
                val (groupOrFull, name, version) = fields
                if (name.isEmpty() && version.isEmpty()) {
                    _dependencies.add(dependency(groupOrFull, exclusions = WemiBundledLibrariesExclude))
                } else {
                    _dependencies.add(Dependency(DependencyId(groupOrFull, name, version), exclusions = WemiBundledLibrariesExclude))
                }
            }
            BuildDependencyRepository::class.java -> {
                val (name, url) = fields

                _repositories.add(Repository(name, URL(url)))
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

    /** Resolve dependencies declared in source files.
     * This can take a long time, as it may download dependencies from the web. */
    internal fun resolve():Boolean {
        _repositories.clear()
        _dependencies.clear()
        _unmanagedDependencies.clear()
        _managedDependencies.clear()

        _unmanagedDependencies.addAll(runtimeClasspath)
        _repositories.addAll(DefaultRepositories)

        for (sourceFile in sources) {
            val success = try {
                Files.newBufferedReader(sourceFile, Charsets.UTF_8).use {
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

        val (resolved, resolvedComplete) = resolveDependencies(dependencies, repositories)
        if (!resolvedComplete) {
            // Dependencies failed to resolve
            // TODO Is this logged properly & nicely?
            return false
        }
        for ((_, r) in resolved) {
            _managedDependencies.add(r.artifact?.path ?: continue)
        }

        return true
    }

    override fun JsonWriter.write() {
        writeObject {
            fieldCollection("repositories", _repositories)
            fieldCollection("dependencies", _dependencies)
            fieldCollection("unmanagedDependencies", _unmanagedDependencies)
            fieldCollection("managedDependencies", _managedDependencies)
        }
    }

    override fun read(value: JsonValue) {
        value.fieldToCollection("repositories", _repositories)
        value.fieldToCollection("dependencies", _dependencies)
        value.fieldToCollection("unmanagedDependencies", _unmanagedDependencies)
        value.fieldToCollection("managedDependencies", _managedDependencies)
    }

    override fun toString(): String {
        return "BuildScriptInfo(scriptJar=$scriptJar, sources=$sources, _repositories=$_repositories, _dependencies=$_dependencies, _unmanagedDependencies=$_unmanagedDependencies)"
    }

    companion object {
        val compilerOptions = CompilerFlags().also {
            it[KotlinCompilerFlags.moduleName] = WemiBuildScriptProjectName
            it[KotlinJVMCompilerFlags.jvmTarget] = "1.8"
        }
    }
}

@Throws(WemiException.CompilationException::class)
internal fun compileBuildScript(buildScriptInfo:BuildScriptInfo) {
    val resultJar = buildScriptInfo.scriptJar
    if (!buildScriptInfo.recompilationNeeded) {
        return
    }

    val sources = buildScriptInfo.sources

    val externalClasspath = buildScriptInfo.unmanagedDependencies + buildScriptInfo.managedDependencies
    LOG.debug("Compiling sources: {} classpath: {} resultJar: {}", sources, externalClasspath, resultJar)

    val status = WemiKotlinVersion.compilerInstance(null).compileJVM(sources.map { LocatedPath(it) }, externalClasspath, resultJar,
            null, BuildScriptInfo.compilerOptions, LoggerFactory.getLogger("BuildScriptCompilation"), null)
    if (status != KotlinCompiler.CompileExitStatus.OK) {
        LOG.warn("Compilation failed for {}: {}", sources, status)
        throw WemiException.CompilationException("Build script failed to compile: $status")
    }

    buildScriptInfo.recompilationNeeded = false
    return
}

/** Load the classes of the compiled [buildScriptInfo].
 * @return amount of classes which failed to load (= 0 means all successful) */
internal fun loadBuildScript(buildScriptInfo:BuildScriptInfo):Int {
    // Load build script configuration
    val internal = buildScriptInfo.unmanagedDependencies
    val external = buildScriptInfo.managedDependencies
    val urls = arrayOfNulls<URL>(1 + internal.size + external.size)
    var i = 0
    urls[i++] = buildScriptInfo.scriptJar.toUri().toURL()
    for (path in internal) {
        urls[i++] = path.toUri().toURL()
    }
    for (path in external) {
        urls[i++] = path.toUri().toURL()
    }

    val buildScriptClassloader = URLClassLoader(urls, Magic.WemiDefaultClassLoader)

    LOG.debug("Loading plugins...")
    val pluginServiceLoader = ServiceLoader.load(PluginEnvironment::class.java, buildScriptClassloader)
    for (pluginService in pluginServiceLoader) {
        LOG.debug("Loading plugin service {}", pluginService)

        pluginService.initialize()
    }

    LOG.debug("Loading build...")
    var errors = 0

    // Main classes of the build script, that should be initialized to load the build script.
    val initClasses = buildScriptInfo.sources.map { Magic.transformFileNameToKotlinClassName(it.name.pathWithoutExtension()) }

    for (initClass in initClasses) {
        try {
            Class.forName(initClass, true, buildScriptClassloader)
        } catch (e: Exception) {
            LOG.warn("Failed to load build file class {} from {}", initClass, urls, e)
            errors++
        }
    }

    LOG.debug("Build script loaded")
    return errors
}