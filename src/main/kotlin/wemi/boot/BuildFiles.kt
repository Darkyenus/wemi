package wemi.boot

import org.slf4j.LoggerFactory
import wemi.WemiKotlinVersion
import wemi.compile.KotlinCompiler
import wemi.dependency.*
import wemi.util.toFile
import java.io.File
import java.net.URL

private val LOG = LoggerFactory.getLogger("BuildFiles")

val BuildFileStdLib = ProjectDependency(ProjectId("org.jetbrains.kotlin", "kotlin-stdlib", WemiKotlinVersion))

private object __ResourceHook

internal val WemiClasspathFile: File = __ResourceHook.javaClass.getResource("BuildFilesKt.class").let { dotResource ->
    val result: File?
    if (dotResource.protocol == "file") {
        result = File(dotResource.path.removeSuffix("wemi/boot/BuildFilesKt.class"))
    } else {
        result = dotResource.toFile()
    }
    if (result == null) {
        throw IllegalStateException("Wemi must be launched from filesystem (current URL: $dotResource)")
    }
    LOG.debug("WemiClasspathFile found at {}", result)
    result
}

/**
 * Build file is a file with .wemi extension, anywhere in current or parent directory.
 */
fun findBuildFile(from: File): List<File>? {
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

    return null
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

fun getCompiledBuildFile(buildFile: File, forceCompile: Boolean): File? {
    val buildFolder = prepareBuildFileCacheFolder(buildFile)
    val buildJar = File(buildFolder, buildFile.name + "-cache.jar")

    if (forceCompile || !buildJar.exists() || buildJar.lastModified() < buildFile.lastModified()) {
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

        val success = KotlinCompiler.compile(listOf(buildFile), buildJar, classpath, emptyArray(), LoggerFactory.getLogger(buildFile.name + " Build"), null)
        if (!success) {
            return null
        }
    }

    return buildJar
}



