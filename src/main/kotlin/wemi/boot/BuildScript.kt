package wemi.boot

import org.slf4j.LoggerFactory
import wemi.WemiKotlinVersion
import wemi.compile.*
import wemi.dependency.*
import wemi.dependency.DependencyResolver.artifacts
import wemi.util.*
import java.io.File
import java.net.URL

private val LOG = LoggerFactory.getLogger("BuildFiles")

val BuildFileStdLib = Dependency(DependencyId("org.jetbrains.kotlin", "kotlin-stdlib", WemiKotlinVersion.string))

val WemiBuildFileExtensions = listOf("wemi", "wemi.kt")

/**
 * Build file is a file with .wemi extension, anywhere in current or parent directory.
 */
fun findBuildScriptSources(buildFolder: File): List<File> {
    var result: MutableList<File>? = null

    val files = buildFolder.listFiles() ?: return emptyList()

    for (file in files) {
        if (file.isFile && !file.isHidden && !file.name.startsWith('.') && file.nameHasExtension(WemiBuildFileExtensions)) {
            if (result == null) {
                result = ArrayList()
            }
            result.add(file)
        }
    }

    return result ?: emptyList()
}

private fun prepareBuildFileCacheFolder(buildFolder: File): File? {
    val folder = File(buildFolder, "cache")
    if (folder.exists() && !folder.isDirectory) {
        LOG.error("Build directory {} exists and is not a directory", folder)
        return null
    } else if (!folder.exists()) {
        if (!folder.mkdirs() && !folder.isDirectory) {
            LOG.error("Could not create build directory {}", folder)
            return null
        }
    }

    return folder
}

fun getCompiledBuildScript(rootFolder:File, buildFolder: File, buildScriptSources:List<File>, forceCompile: Boolean): BuildScript? {
    val cacheFolder = prepareBuildFileCacheFolder(buildFolder) ?: return null
    val combinedBuildFileName = buildScriptSources.joinToString("-") { it.nameWithoutExtension }

    val resultJar = File(cacheFolder, combinedBuildFileName + "-cache.jar")
    val classpathFile = File(cacheFolder, combinedBuildFileName + ".classpath")
    val classpath = mutableListOf<File>()

    var recompileReason = ""

    val recompile = if (forceCompile) {
        recompileReason = "Requested"
        LOG.debug("Rebuilding build scripts: Requested")
        true
    } else if (!resultJar.exists() || resultJar.isDirectory) {
        recompileReason = "No cache"
        LOG.debug("Rebuilding build scripts: No cache at {}", resultJar)
        true
    } else if (resultJar.lastModified().let { jarLastModified -> buildScriptSources.any { source -> jarLastModified < source.lastModified()} }) {
        recompileReason = "Script changed"
        LOG.debug("Rebuilding build scripts: Old cache")
        true
    } else if (!classpathFile.exists() || classpathFile.isDirectory) {
        recompileReason = "Missing cache metadata"
        LOG.debug("Rebuilding build scripts: No classpath cache")
        true
    } else if (WemiLauncherFile.lastModified() > resultJar.lastModified()) {
        recompileReason = "Updated launcher"
        LOG.debug("Rebuilding build scripts: Launcher updated ({})", WemiLauncherFile)
        true
    } else recompile@{
        // All seems good, try to load the result classpath
        classpathFile.forEachLine { line ->
            if (line.isNotBlank()) {
                classpath.add(File(line))
            }
        }
        // Compiler bug workaround
        var result = false
        for (file in classpath) {
            if (!file.exists()) {
                recompileReason = "Corrupted cache metadata"
                LOG.debug("Rebuilding build scripts: Classpath cache corrupted ({} does not exist)", file)
                classpath.clear()
                result = true
                break
                //return@recompile true
            }
        }
        result
    }

    val buildFlags = CompilerFlags()
    buildFlags[KotlinJVMCompilerFlags.compilingWemiBuildFiles] = true
    buildFlags[KotlinJVMCompilerFlags.moduleName] = combinedBuildFileName
    // Wemi launcher has kotlin runtime bundled, which is fine
    buildFlags[KotlinJVMCompilerFlags.skipRuntimeVersionCheck] = true

    val sources = buildScriptSources.map { LocatedFile(it) }

    val classpathConfiguration = BuildScriptClasspathConfiguration(buildScriptSources)

    if (recompile) {
        // Recompile
        LOG.info("Compiling build script: {}", recompileReason)
        resultJar.deleteRecursively()

        val wemiLauncherJar = wemiLauncherFileWithJarExtension(cacheFolder)

        classpath.add(wemiLauncherJar)
        val resolved = HashMap<DependencyId, ResolvedDependency>()
        val resolutionOk = DependencyResolver.resolve(resolved, classpathConfiguration.dependencies, classpathConfiguration.repositoryChain)
        if (!resolutionOk) {
            LOG.warn("Failed to retrieve all build file dependencies for {}:\n{}", buildScriptSources, resolved.prettyPrint(classpathConfiguration.dependencies))
            return null
        }
        classpath.addAll(resolved.artifacts())

        LOG.debug("Compiling sources: {} classpath: {} resultJar: {} buildFlags: {}", sources, classpath, resultJar, buildFlags)

        val status = kotlinCompiler(WemiKotlinVersion).compile(sources, classpath, resultJar, buildFlags, LoggerFactory.getLogger("BuildScriptCompilation"), null)
        if (status != KotlinCompiler.CompileExitStatus.OK) {
            LOG.warn("Compilation failed for {}: {}", buildScriptSources, status)
            return null
        }

        classpathFile.writeText(classpath.joinToString(separator = "\n") { file -> file.absolutePath })
    }

    // Figure out the init class
    return BuildScript(rootFolder, resultJar, buildFolder, cacheFolder,
            classpath, buildScriptSources.map { transformFileNameToKotlinClassName(it.nameWithoutExtension) },
            classpathConfiguration, sources, buildFlags)
}

private fun transformFileNameToKotlinClassName(fileNameWithoutExtension:String):String {
    val sb = StringBuilder()
    // If file name starts with digit, _ is prepended
    if (fileNameWithoutExtension.isNotEmpty() && fileNameWithoutExtension[0] in '0'..'9') {
        sb.append('_')
    }
    // Everything is valid java identifier
    for (c in fileNameWithoutExtension) {
        if (c.isJavaIdentifierPart()) {
            sb.append(c)
        } else {
            sb.append("_")
        }
    }
    // First letter is capitalized
    if (sb.isNotEmpty()) {
        sb[0] = sb[0].toUpperCase()
    }
    // Kt is appended
    sb.append("Kt")
    return sb.toString()
}

private val DirectiveRegex = "///\\s*([a-zA-Z0-9\\-]+)\\s+(.*?)\\s*".toRegex()

private val M2RepositoryDirectiveRegex = "(\\S+)\\s+at\\s+(\\S+:\\S+)".toRegex()

private val LibraryDirectiveRegex = "(\\S+)\\s*:\\s*(\\S+)\\s*:\\s*(\\S+)".toRegex()

class BuildScriptClasspathConfiguration(private val buildScriptSources:List<File>) {
    private var _repositories : List<Repository>? = null
    private var _repositoryChain : RepositoryChain? = null
    private var _dependencies : List<Dependency>? = null

    private fun resolve() {
        val repositories = mutableListOf<Repository>()
        repositories.addAll(DefaultRepositories)
        val buildDependencyLibraries = mutableListOf(BuildFileStdLib)

        for (source in buildScriptSources) {
            var lineNumber = 0
            source.forEachLine { line ->
                lineNumber++
                val directiveMatch = DirectiveRegex.matchEntire(line) ?: return@forEachLine
                val directive = (directiveMatch.groups[1]?.value ?: "").toLowerCase()
                val value = directiveMatch.groups[2]?.value ?: ""

                when (directive) {
                    "dependency" -> {
                        val match = LibraryDirectiveRegex.matchEntire(value)
                        if (match == null) {
                            LOG.warn("{}:{} Invalid dependency directive \"{}\". (Example: 'com.example:my-project:1.0')", buildScriptSources, lineNumber, value)
                        } else {
                            val (group, name, version) = match.destructured
                            buildDependencyLibraries.add(Dependency(DependencyId(group, name, version)))
                        }
                    }
                    "repository" -> {
                        val match = M2RepositoryDirectiveRegex.matchEntire(value)
                        if (match == null) {
                            LOG.warn("{}:{} Invalid repository directive \"{}\". (Example: 'my-repo at https://example.com')", buildScriptSources, lineNumber, value)
                        } else {
                            val (name, url) = match.destructured
                            repositories.add(Repository.M2(name, URL(url), LocalM2Repository))
                        }
                    }
                    else -> {
                        LOG.warn("{}:{} Invalid directive \"{}\". Supported directives are 'dependency' and 'repository'.", buildScriptSources, lineNumber, directive)
                    }
                }
            }
        }

        _repositories = repositories
        _repositoryChain = createRepositoryChain(repositories)
        _dependencies = buildDependencyLibraries
    }

    val repositories : List<Repository>
        get() {
            if (_repositories == null) {
                resolve()
            }
            return _repositories!!
        }

    val repositoryChain : RepositoryChain
        get() {
            if (_repositoryChain == null) {
                resolve()
            }
            return _repositoryChain!!
        }

    val dependencies : List<Dependency>
        get() {
            if (_dependencies == null) {
                resolve()
            }
            return _dependencies!!
        }
}

/**
 * @property wemiRoot directory in which wemi executable is (./)
 * @property scriptJar jar to which the build script has been compiled
 * @property buildFolder ./build folder
 * @property cacheFolder ./build/cache folder
 * @property classpath used to compile and to run the scriptJar
 * @property initClasses main classes of the [scriptJar]
 */
data class BuildScript(val wemiRoot:File,
                       val scriptJar:File,
                       val buildFolder:File, val cacheFolder:File,
                       val classpath:List<File>, val initClasses:List<String>,
                       val buildScriptClasspathConfiguration: BuildScriptClasspathConfiguration,
                       val sources:List<LocatedFile>, val buildFlags: CompilerFlags)
