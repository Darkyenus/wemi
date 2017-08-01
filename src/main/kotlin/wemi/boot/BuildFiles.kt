package wemi.boot

import org.slf4j.LoggerFactory
import WemiKotlinVersion
import wemi.compile.KotlinCompiler
import wemi.dependency.*
import java.io.File
import java.net.URL

private val LOG = LoggerFactory.getLogger("BuildFiles")

val BuildFileStdLib = ProjectDependency(ProjectId("org.jetbrains.kotlin", "kotlin-stdlib", WemiKotlinVersion))

/**
 * Build file is a file with .wemi extension, anywhere in current or parent directory.
 */
fun findBuildFile(from: File): List<File> {
    var currentDirectory: File = from
    var result: MutableList<File>? = null

    while (true) {
        val files = currentDirectory.listFiles()
        if (files != null) {
            for (file in files) {
                if (file.isFile && !file.isHidden && (file.name.endsWith(".wemi", ignoreCase = true) || file.name.endsWith(".wemi.kt", ignoreCase = true))) {
                    if (result == null) {
                        result = ArrayList<File>()
                    }
                    result.add(file)
                }
            }

            if (result != null) {
                return result
            }
        }

        val parent = currentDirectory.parentFile
        if (parent == null || parent == currentDirectory) {
            break
        }
        currentDirectory = parent
    }

    return emptyList()
}

fun prepareBuildFileCacheFolder(buildFile: File): File? {
    val parentFile = buildFile.parentFile
    if (parentFile == null) {
        LOG.error("Failed to retrieve parent file of {}", buildFile)
        return null
    }
    val folder = File(parentFile, "build")
    if (folder.exists() && !folder.isDirectory) {
        LOG.error("Build directory {} exists and is not a directory", folder)
        return null
    } else if (!folder.exists()) {
        if (!folder.mkdirs()) {
            LOG.error("Could not create build directory {}", folder)
            return null
        }
    }

    return folder
}

private val M2RepositoryRegex = "///\\s*m2-repository\\s+(\\w+)\\s+at\\s+(\\w+:\\w+)\\s*".toRegex()

private val LibraryRegex = "///\\s*build-library\\s+(\\w+)\\s*:\\s*(\\w+)\\s*:\\s*(\\w+)\\s*".toRegex()

fun getCompiledBuildFile(buildFile: File, forceCompile: Boolean): BuildFile? {
    val buildFolder = prepareBuildFileCacheFolder(buildFile)
    val resultJar = File(buildFolder, buildFile.name + "-cache.jar")
    val classpathFile = File(buildFolder, buildFile.name + ".cp")
    val resultClasspath = mutableListOf<File>()

    var recompile = forceCompile || !resultJar.exists() || resultJar.isDirectory || resultJar.lastModified() < buildFile.lastModified()
    recompile = recompile || !classpathFile.exists() || classpathFile.isDirectory

    if (!recompile) {
        // All seems good, try to load the result classpath
        classpathFile.forEachLine { line ->
            if (line.isNotBlank()) {
                resultClasspath.add(File(line))
            }
        }
        for (file in resultClasspath) {
            if (!file.exists()) {
                recompile = true
                resultClasspath.clear()
                break
            }
        }
    }

    if (recompile) {
        // Recompile
        val classpath = ArrayList<File>()

        val repositories = mutableListOf<Repository>()
        repositories.addAll(DefaultRepositories)
        val buildDependencyLibraries = mutableListOf(BuildFileStdLib)

        buildFile.forEachLine { line ->
            val m2resolver = M2RepositoryRegex.matchEntire(line)
            if (m2resolver != null) {
                val (name, url) = m2resolver.destructured
                repositories.add(Repository.M2(name, URL(url), LocalM2Repository))
                return@forEachLine
            }

            val buildLibrary = LibraryRegex.matchEntire(line)
            if (buildLibrary != null) {
                val (group, name, version) = buildLibrary.destructured
                buildDependencyLibraries.add(ProjectDependency(ProjectId(group, name, version)))
                return@forEachLine
            }
        }

        val repositoryChain = createRepositoryChain(repositories)

        classpath.add(WemiClasspathFile)
        val artifacts = DependencyResolver.resolveArtifacts(buildDependencyLibraries, repositoryChain)
        if (artifacts == null) {
            LOG.warn("Failed to retrieve all build file dependencies")
            return null
        }
        classpath.addAll(artifacts)

        val success = KotlinCompiler.compile(listOf(buildFile), resultJar, classpath, emptyArray(), LoggerFactory.getLogger("BuildScriptCompilation"), null)
        if (!success) {
            return null
        }

        resultClasspath.addAll(artifacts)
        classpathFile.writeText(artifacts.joinToString(separator = "\n") { file -> file.absolutePath })
    }

    // Figure out the init class
    return BuildFile(resultJar, resultClasspath, transformFileNameToKotlinClassName(buildFile.nameWithoutExtension))
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

data class BuildFile(val scriptJar:File, val extraClasspath:List<File>, val initClass:String)


