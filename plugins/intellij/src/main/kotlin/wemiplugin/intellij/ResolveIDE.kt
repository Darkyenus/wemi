package wemiplugin.intellij

import Files
import com.jetbrains.plugin.structure.intellij.version.IdeVersion
import com.jetbrains.plugin.structure.intellij.version.IdeVersionImpl
import org.slf4j.LoggerFactory
import wemi.ActivityListener
import wemi.Value
import wemi.WemiException
import wemi.boot.WemiCacheFolder
import wemi.dependency
import wemi.dependency.Repository
import wemi.dependency.TypeChooseByPackaging
import wemi.dependency.resolveDependencyArtifacts
import wemi.keys.libraryDependencies
import wemi.util.SystemInfo
import wemi.util.div
import wemi.util.executable
import wemi.util.exists
import wemi.util.isDirectory
import wemi.util.name
import wemi.util.pathHasExtension
import wemi.util.pathWithoutExtension
import wemi.util.toSafeFileName
import wemiplugin.intellij.utils.BuiltinPluginsRegistry
import wemiplugin.intellij.utils.Utils
import wemiplugin.intellij.utils.unZipIfNew
import java.net.URL
import java.nio.file.Path

private val LOG = LoggerFactory.getLogger("ResolveIDE")

val IntelliJIDERepo = URL("https://cache-redirector.jetbrains.com/www.jetbrains.com/intellij-repository")
const val DEFAULT_INTELLIJ_IDE_VERSION =  "LATEST-EAP-SNAPSHOT"

/** A specification of an IntelliJ Platform IDE dependency. */
sealed class IntelliJIDE {
	/**
	 * A dependency on a locally installed IDE.
	 * @param path to the IDE's home directory
	 * @param sources folders or jars of IDE's sources
	 */
	data class Local(val path:Path, val sources:List<Path>) : IntelliJIDE()

	/**
	 * A dependency on an IDE from a remote repository.
	 * @param version of the IDE, see [version documentation](https://www.jetbrains.org/intellij/sdk/docs/basics/getting_started/plugin_compatibility.html)
	 * @param type of IDE distribution (IC, IU, CL, GO, PY, PC, RD or JPS)
	 */
	// List of versions can also be found here: https://jb.gg/intellij-platform-builds-list
	data class External(val type:String = "IC", val version:String = DEFAULT_INTELLIJ_IDE_VERSION) : IntelliJIDE()
}

class ResolvedIntelliJIDE(
		buildNumber:String,
		val homeDir: Path,
		val pluginsRegistry : BuiltinPluginsRegistry,
		val jarFiles:Collection<Path>) {

	val version = try {
		IdeVersion.createIdeVersion(buildNumber)
	} catch (e:IllegalArgumentException) {
		LOG.warn("IDE version at {}, {}, is not valid", homeDir, buildNumber, e)
		IdeVersionImpl("", intArrayOf(999), isSnapshot = true)
	}

	override fun toString(): String {
		return "ResolvedIntelliJIDE($homeDir)"
	}
}

val ResolveIdeDependency: Value<ResolvedIntelliJIDE> = {
	val withKotlin = !libraryDependencies.get().any { it.dependencyId.group == "org.jetbrains.kotlin" && isKotlinRuntime(it.dependencyId.name) }

	val result = when (val dependency = IntelliJ.intellijIdeDependency.get()) {
		is IntelliJIDE.Local -> resolveLocalIDE(dependency.path, withKotlin)
		is IntelliJIDE.External -> {
			resolveRemoteIDE(IntelliJ.intellijIdeRepository.get(), dependency.version, dependency.type, withKotlin, progressListener)
		}
	}

	result
}

val ResolveIdeDependencySources: Value<List<Path>> = {
	when (val dependency = IntelliJ.intellijIdeDependency.get()) {
		is IntelliJIDE.Local -> dependency.sources
		is IntelliJIDE.External -> {
			// Original code checked here whether this is a dependency.type == "RD" (Rider)
			// and Utils.relaseType(version) == "snapshots", and refused to download sources with warning:
			// "IDE sources are not available for Rider SNAPSHOTS"
			// However, this is not necessary:
			// 1. The code after it does not even differentiate between IDE types and always downloads ideaIC sources
			// 2. We should not care whether something is or is not available, we can just check and return nothing if it isn't
			// Therefore, that code is not reproduced here

			val repoUrl = IntelliJ.intellijIdeRepository.get()
			resolveSources(dependency.version, intellijIDERepository(repoUrl, dependency.version), progressListener) ?: emptyList()
		}
	}
}

fun intellijIDERepository(baseRepoUrl:URL, version:String?):Repository {
	val releaseType = Utils.releaseType(version ?: "")
	val name = if (baseRepoUrl.host == "cache-redirector.jetbrains.com") {
		"idea-${baseRepoUrl.path.removePrefix("/").toSafeFileName()}-$releaseType"
	} else {
		"idea-${baseRepoUrl.host}-${baseRepoUrl.path.removePrefix("/").toSafeFileName()}-$releaseType"
	}
	return Repository(name, baseRepoUrl / releaseType)
}

/** Resolve IDE from remote repository. */
fun resolveRemoteIDE(repoUrl: URL, version: String, type: String, withKotlin: Boolean, progressListener: ActivityListener?): ResolvedIntelliJIDE {
	var dependencyGroup = "com.jetbrains.intellij.idea"
	var dependencyName = "ideaIC"
	if (type == "IU") {
		dependencyName = "ideaIU"
	} else if (type == "CL") {
		dependencyGroup = "com.jetbrains.intellij.clion"
		dependencyName = "clion"
	} else if (type == "PY" || type == "PC") {
		dependencyGroup = "com.jetbrains.intellij.pycharm"
		dependencyName = "pycharm$type"
	} else if (type == "RD") {
		dependencyGroup = "com.jetbrains.intellij.rider"
		dependencyName = "riderRD"
	} else if (type == "GO") {
		dependencyGroup = "com.jetbrains.intellij.goland"
		dependencyName = "goland"
	}

	val repository = intellijIDERepository(repoUrl, version)
	val dependency = dependency(dependencyGroup, dependencyName, version, type = TypeChooseByPackaging)
	val ideZipFile = resolveDependencyArtifacts(listOf(dependency), listOf(repository), progressListener)
			?.single()
			?: throw WemiException("Failed to resolve IDE dependency $dependency from $repository")
	LOG.debug("Resolved IDE zip: {}", ideZipFile)

	val homeDir = unzipDependencyFile(getZipCacheDirectory(ideZipFile, type), ideZipFile, type, version.endsWith("-SNAPSHOT"))
	LOG.debug("IDE dependency cache directory: {}", homeDir)
	return createDependency(type, homeDir, withKotlin)
}

/**
 * Resolve IDE from local installation.
 */
fun resolveLocalIDE(localPath: Path, withKotlin: Boolean): ResolvedIntelliJIDE {
	LOG.debug("Adding local IDE dependency")
	val homeDir = if (localPath.name.pathHasExtension("app")) {
		localPath.resolve("Contents")
	} else localPath

	if (!homeDir.exists() || !homeDir.isDirectory()) {
		throw WemiException("Specified localPath '$localPath' doesn't exist or is not a directory", false)
	}
	return createDependency(null, homeDir, withKotlin)
}

private val mainDependencies = arrayOf("ideaIC", "ideaIU", "riderRD", "riderRS")

fun isKotlinRuntime(name:String):Boolean {
	return "kotlin-runtime" == name ||
			"kotlin-reflect" == name || name.startsWith("kotlin-reflect-") ||
			"kotlin-stdlib" == name || name.startsWith("kotlin-stdlib-") ||
			"kotlin-test" == name || name.startsWith("kotlin-test-")
}

private val ALLOWED_JPS_JAR_NAMES = arrayOf("jps-builders.jar", "jps-model.jar", "util.jar")

private fun createDependency(type: String?, homeDir: Path, withKotlin: Boolean): ResolvedIntelliJIDE {
	val lib = homeDir / "lib"
	val jars = ArrayList<Path>()
	for (it in Utils.collectJars(lib)) {
		val libName = it.name
		if ((withKotlin || !isKotlinRuntime(libName.pathWithoutExtension())) && libName != "junit.jar" && libName != "annotations.jar") {
			jars.add(it)
		}
	}
	for (it in Utils.collectJars(lib / "ant/lib")) {
		jars.add(it)
	}

	val pluginsRegistry: BuiltinPluginsRegistry
	if (type == "JPS") {
		// What is JPS? I don't know. Probably this?
		// https://intellij-support.jetbrains.com/hc/en-us/community/posts/360008114139-IDEA-independent-building-
		jars.removeIf { it.name !in ALLOWED_JPS_JAR_NAMES }
		pluginsRegistry = BuiltinPluginsRegistry(homeDir)
	} else {
		pluginsRegistry = BuiltinPluginsRegistry.fromDirectory(homeDir / "plugins")
	}

	val buildTxt = Utils.run {
		if (SystemInfo.IS_MAC_OS) {
			val file = homeDir.resolve("Resources/build.txt")
			if (file.exists()) {
				return@run file
			}
		}
		homeDir.resolve("build.txt")
	}
	val buildNumber = String(Files.readAllBytes(buildTxt), Charsets.UTF_8).trim()

	jars.sort()
	return ResolvedIntelliJIDE(buildNumber, homeDir, pluginsRegistry, jars)
}

private fun resolveSources(version: String, repository: Repository, progressListener: ActivityListener?): List<Path>? {
	val dependency = dependency("com.jetbrains.intellij.idea", "ideaIC", version, classifier = "sources", type = "jar")
	val artifacts = resolveDependencyArtifacts(listOf(dependency), listOf(repository), progressListener) ?: return null
	LOG.debug("IDE sources: {}", artifacts)
	return artifacts
}

internal fun getZipCacheDirectory(zipFile: Path, type: String): Path {
	if (type == "RD" && SystemInfo.IS_WINDOWS) {
		// TODO(jp): Not sure why is this needed
		return WemiCacheFolder
	}
	return zipFile.parent
}

internal fun unzipDependencyFile(cacheDirectory: Path, zipFile: Path, type: String, checkVersionChange: Boolean): Path {
	val targetDir = cacheDirectory / zipFile.name.pathWithoutExtension()
	if (unZipIfNew(zipFile, targetDir, existenceIsEnough = !checkVersionChange)) {
		if (type == "RD" && !SystemInfo.IS_WINDOWS) {
			setExecutable(targetDir, "lib/ReSharperHost/dupfinder.sh")
			setExecutable(targetDir, "lib/ReSharperHost/inspectcode.sh")
			setExecutable(targetDir, "lib/ReSharperHost/JetBrains.ReSharper.Host.sh")
			setExecutable(targetDir, "lib/ReSharperHost/runtime.sh")
			setExecutable(targetDir, "lib/ReSharperHost/macos-x64/mono/bin/env-wrapper")
			setExecutable(targetDir, "lib/ReSharperHost/macos-x64/mono/bin/mono-sgen")
			setExecutable(targetDir, "lib/ReSharperHost/macos-x64/mono/bin/mono-sgen-gdb.py")
			setExecutable(targetDir, "lib/ReSharperHost/linux-x64/mono/bin/mono-sgen")
			setExecutable(targetDir, "lib/ReSharperHost/linux-x64/mono/bin/mono-sgen-gdb.py")
		}
	}
	return targetDir
}

private fun setExecutable(parent: Path, child: String) {
	val file = parent / child
	LOG.debug("Resetting executable permissions for {}", file)
	try {
		file.executable = true
	} catch (ignored:Exception) {}
}