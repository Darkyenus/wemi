package wemiplugin.intellij

import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory
import wemi.ActivityListener
import wemi.ExtendableBindingHolder
import wemi.WemiException
import wemi.boot.WemiBuildFolder
import wemi.boot.WemiRootFolder
import wemi.boot.WemiSystemCacheFolder
import wemi.configurations.offline
import wemi.dependency
import wemi.dependency.Repository
import wemi.dependency.resolveDependencyArtifacts
import wemi.key
import wemi.keys.javaHome
import wemi.run.prepareJavaProcess
import wemi.run.runForegroundProcess
import wemi.util.JavaHome
import wemi.util.absolutePath
import wemi.util.div
import wemi.util.exists
import wemi.util.httpGetFile
import wemi.util.httpGetJson
import wemi.util.withAdditionalQueryParameters
import wemiplugin.intellij.utils.unTar
import java.net.HttpURLConnection
import java.net.URL

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

private val BINTRAY_API_VERIFIER_VERSION_LATEST = URL("https://api.bintray.com/packages/jetbrains/intellij-plugin-service/intellij-plugin-verifier/versions/_latest")
private const val KNOWN_LATEST_INTELLIJ_VERIFIER_VERSION = "1.253"
private val IDE_DOWNLOAD_URL = URL("https://data.services.jetbrains.com/products/download")
const val CACHE_REDIRECTOR = "https://cache-redirector.jetbrains.com"

enum class FailureLevel(val testValue:String) {
	COMPATIBILITY_WARNINGS("Compatibility warnings"),
	COMPATIBILITY_PROBLEMS("Compatibility problems"),
	DEPRECATED_API_USAGES("Deprecated API usages"),
	EXPERIMENTAL_API_USAGES("Experimental API usages"),
	INTERNAL_API_USAGES("Internal API usages"),
	OVERRIDE_ONLY_API_USAGES("Override-only API usages"),
	NON_EXTENDABLE_API_USAGES("Non-extendable API usages"),
	PLUGIN_STRUCTURE_WARNINGS("Plugin structure warnings"),
	MISSING_DEPENDENCIES("Missing dependencies"),
	INVALID_PLUGIN("The following files specified for the verification are not valid plugins"),
	NOT_DYNAMIC("Plugin cannot be loaded/unloaded without IDE restart");

	companion object {
		val ALL: EnumSet<FailureLevel> = EnumSet.allOf(FailureLevel::class.java)
		val NONE: EnumSet<FailureLevel> = EnumSet.noneOf(FailureLevel::class.java)
	}
}

val intellijVerifyPluginOptions by key<VerificationOptions>("Options for the IntelliJ Plugin Verifier")
val intellijVerifyPlugin by key<Unit>("Runs the IntelliJ Plugin Verifier tool to check the binary compatibility with specified IntelliJ IDE builds.")

fun ExtendableBindingHolder.init() {
	intellijVerifyPluginOptions set {
		// Retrieve the Plugin Verifier home directory used for storing downloaded IDEs.
		// Following home directory resolving method is taken directly from the Plugin Verifier to keep the compatibility.
		val verifierHomeDir = System.getProperty("plugin.verifier.home.dir")
		val homeDir = if (verifierHomeDir != null) {
			Paths.get(verifierHomeDir)
		} else {
			val userHome = System.getProperty("user.home")
			if (userHome != null) {
				Paths.get(userHome, ".pluginVerifier")
			} else {
				FileUtils.getTempDirectory().toPath().resolve(".pluginVerifier")
			}
		}

		// Provides target directory used for storing downloaded IDEs.
		// Path is compatible with the Plugin Verifier approach.
		val ideDownloadDirectory = homeDir / "ides"

		// Since VerifyPlugin will download the IDEs separately, we don't provide version but directly the IDE
		val compilationIde = IntelliJ.intellijResolvedIdeDependency.get()

		VerificationOptions(
				verificationReportsDirectory = WemiBuildFolder / "reports/pluginVerifier",
				downloadDirectory = ideDownloadDirectory,
				localPaths = listOf(compilationIde.homeDir)
		)
	}
	extend(offline) {
		intellijVerifyPluginOptions modify { it.copy(offline = true) }
	}
	intellijVerifyPlugin set {
		val options = intellijVerifyPluginOptions.get()
		val archive = IntelliJ.intellijPluginArchive.get()
		val javaHome = javaHome.get()
		runPluginVerifier(archive, options, javaHome, progressListener)
	}
}

data class VerificationOptions(
		/** [FailureLevel] values used for failing the task if any reported issue will match. */
		val failureLevel:Set<FailureLevel> = EnumSet.of(FailureLevel.INVALID_PLUGIN),
		/** Returns a list of the specified IDE versions used for the verification. */
		val ideVersions:List<String> = emptyList(),
		/** List of the paths to locally installed IDE distributions that should be used for verification
		 * in addition to those specified in [ideVersions]. */
		val localPaths:List<Path> = emptyList(),
		/** The version of the IntelliJ Plugin Verifier that will be used. `null` means use latest. */
		val verifierVersion:String? = null,
		/** Path to the local IntelliJ Plugin Verifier that will be used.
		 * If provided, [verifierVersion] is ignored. */
		val verifierPath:Path? = null,
		/** Returns the path to directory where verification reports will be saved. */
		val verificationReportsDirectory:Path,
		/** Returns the path to directory where IDEs used for the verification will be downloaded. */
		val downloadDirectory:Path = WemiSystemCacheFolder / "intellij-plugin-verifier-ide-cache",
		/** Prefixes of classes from external libraries.
		 * The Plugin Verifier will not report 'No such class' for classes of these packages. */
		val externalPrefixes:List<String> = emptyList(),
		/** Returns a flag that controls the output format - if set to `true`,
		 * the TeamCity compatible output will be returned to stdout. */
		val teamCityOutputFormat:Boolean = false,
		/**
		 * Specifies which subsystems of IDE should be checked.
		 * Available options: `all` (default), `android-only`, `without-android`.
		 */
		val subsystemsToCheck:String = "",
		/** Do not attempt to connect to the Internet and work only from local resources. */
		val offline:Boolean = false
)

private val LOG = LoggerFactory.getLogger("VerifyPlugin")

/**
 * Run the IntelliJ Plugin Verifier against the plugin artifact.
 * See https://github.com/JetBrains/intellij-plugin-verifier for more info.
 *
 * @param pluginDistribution the plugin file, ready for distribution, typically obtained through [IntelliJ.intellijPluginArchive]
 * @param options verification options
 */
fun runPluginVerifier(pluginDistribution:Path, options:VerificationOptions, javaHome: JavaHome, activityListener:ActivityListener?) {
	if (!pluginDistribution.exists()) {
		throw IllegalArgumentException("Plugin file does not exist: $pluginDistribution")
	}

	val ides = options.ideVersions.distinct().map { resolveIdePath(it, options, activityListener) }
	val paths = options.localPaths
	if (ides.isEmpty() && paths.isEmpty()) {
		throw IllegalArgumentException("`ideVersions` and `localPaths` properties should not be empty")
	}

	val verifierArgs = ArrayList<String>()
	verifierArgs.add("check-plugin")
	verifierArgs.add("-verification-reports-dir")
	verifierArgs.add(options.verificationReportsDirectory.absolutePath)
	verifierArgs.add("-runtime-dir")
	verifierArgs.add(javaHome.home.absolutePath)
	if (options.externalPrefixes.isNotEmpty()) {
		verifierArgs.add("-external-prefixes")
		verifierArgs.add(options.externalPrefixes.joinToString(":") { it.trim() })
	}
	if (options.teamCityOutputFormat) {
		verifierArgs.add("-team-city")
	}
	if (options.subsystemsToCheck.isNotBlank()) {
		verifierArgs.add("-subsystems-to-check")
		verifierArgs.add(options.subsystemsToCheck)
	}
	if (options.offline) {
		verifierArgs.add("-offline")
	}
	verifierArgs.add(pluginDistribution.absolutePath)
	for (ide in ides) {
		verifierArgs.add(ide.absolutePath)
	}
	for (path in paths) {
		verifierArgs.add(path.absolutePath)
	}

	LOG.debug("Verifier arguments: {}", verifierArgs)

	val verifierPath = resolveVerifierPath(options, activityListener)
	LOG.debug("Verifier path: {}", verifierPath)

	val process = prepareJavaProcess(javaHome.javaExecutable, WemiRootFolder, verifierPath, "com.jetbrains.pluginverifier.PluginVerifierMain", emptyList(), verifierArgs)

	val allFailureLevels = FailureLevel.values()
	val caughtFailureLevels = EnumSet.noneOf(FailureLevel::class.java)

	val result = runForegroundProcess(process, separateOutputByNewlines = false, logStdOutLine = { line ->
		for (level in allFailureLevels) {
			if (line.contains(level.testValue, ignoreCase = true)) {
				caughtFailureLevels.add(level)
			}
		}

		if (options.teamCityOutputFormat) {
			// Put it on stdout manually, not sure if this is the correct way to do it
			println(line)
			false
		} else {
			true
		}
	})

	val problematic = options.failureLevel.intersect(caughtFailureLevels)
	if (problematic.isNotEmpty() || result != 0) {
		if (result != 0) {
			LOG.warn("IntelliJ Plugin Verifier exited with exit code {}", result)
		}

		for (problem in problematic) {
			LOG.warn("IntelliJ Plugin Verifier detected: {}", problem.testValue)
		}

		throw WemiException("Verification failed", showStacktrace = false)
	}
	LOG.info("Plugin verification was successful")
}


/**
 * Resolves path to the IntelliJ Plugin Verifier file.
 * At first, checks if it was provided with [VerificationOptions.verifierPath].
 * Fetches IntelliJ Plugin Verifier artifact from the {@link IntelliJPlugin#DEFAULT_INTELLIJ_PLUGIN_SERVICE}
 * repository and resolves the path to verifier-cli jar file.
 */
fun resolveVerifierPath(options:VerificationOptions, activityListener: ActivityListener?):List<Path> {
	val path = options.verifierPath
	if (path != null) {
		if (path.exists()) {
			return listOf(path)
		}
		LOG.warn("Provided Plugin Verifier path doesn't exist: '$path'. Downloading Plugin Verifier: ${options.verifierVersion}")
	}

	if (options.offline) {
		throw WemiException("Cannot resolve Plugin Verifier in offline mode. Provide pre-downloaded Plugin Verifier jar file through VerificationOptions.verifierPath. ")
	}

	val repository = Repository("intellij-plugin-service", "https://cache-redirector.jetbrains.com/jetbrains.bintray.com/intellij-plugin-service")

	// Get the IntelliJ Plugin Verifier version. If latest is requested, ask Bintray API
	val verifierVersion = options.verifierVersion ?: run {
		// Check what is latest and get it
		val url = BINTRAY_API_VERIFIER_VERSION_LATEST
		LOG.debug("Resolving latest IntelliJ Plugin Verifier version through {}", url)
		val version = httpGetJson(url, activityListener)?.getString("name", null)
		if (version == null) {
			LOG.warn("Failed to determine latest version of IntelliJ Plugin Verifier, falling back to {}", KNOWN_LATEST_INTELLIJ_VERIFIER_VERSION)
			KNOWN_LATEST_INTELLIJ_VERIFIER_VERSION
		} else {
			LOG.debug("Latest IntelliJ Plugin Verifier version found to be {}", version)
			version
		}
	}

	val dependency = dependency("org.jetbrains.intellij.plugins", "verifier-cli", verifierVersion, classifier = "all", type = "jar")
	val artifacts = resolveDependencyArtifacts(listOf(dependency), listOf(repository), activityListener)
			?: emptyList()
	if (artifacts.isEmpty()) {
		throw WemiException("Failed to retrieve IntelliJ Plugin Verifier version $verifierVersion from $repository")
	}

	return artifacts
}

/**
 * Resolves the IDE type and version. If just version is provided, type is set to "IC".
 *
 * @param ideVersion IDE version. Can be "2020.2", "IC-2020.2", "202.1234.56"
 * @return path to the resolved IDE
 */
fun resolveIdePath(ideVersion:String, options:VerificationOptions, listener:ActivityListener?):Path {
	LOG.debug("Resolving IDE path for {}", ideVersion)
	val ideVersionParts = ideVersion.trim().split('-', limit = 2)
	var type = ideVersionParts[0]
	var version = ideVersionParts.getOrNull(1)

	if (version == null) {
		LOG.debug("IDE type not specified, setting type to IC")
		version = type
		type = "IC"
	}

	val downloadDirectory = options.downloadDirectory
	Files.createDirectories(downloadDirectory)

	for (buildType in arrayOf("release", "rc", "eap", "beta")) {
		LOG.debug("Downloading IDE '{}-{}' from {} channel to {}", type, version, buildType, downloadDirectory)
		try {
			val dir = downloadIde(type, version, buildType, downloadDirectory, options.offline, listener)
			if (dir == null) {
				LOG.debug("Cannot download IDE '{}-{}' from {} channel. Trying another channel...", type, version)
			} else {
				LOG.debug("Resolved IDE '{}-{}' to {}", type, version, dir)
				return dir
			}
		} catch (e: Exception) {
			LOG.debug("Cannot download IDE '{}-{}' from {} channel. Trying another channel...", type, version, e)
		}
	}

	throw WemiException("IDE '$ideVersion' cannot be downloaded. " +
			"Please verify the specified IDE version against the products available for testing: " +
			"https://jb.gg/intellij-platform-builds-list")
}

/**
 * Downloads IDE from the {@link #IDE_DOWNLOAD_URL} service by the given parameters.
 *
 * @param type IDE type, i.e. IC, PS
 * @param version IDE version, i.e. 2020.2 or 203.1234.56
 * @param buildType release, rc, eap, beta
 * @return {@link File} instance pointing to the IDE directory
 */
private fun downloadIde(type:String, version:String, buildType:String, downloadDirectory:Path, offline:Boolean, listener:ActivityListener?):Path? {
	val name = "$type-$version"
	val ideDir = downloadDirectory / name

	if (ideDir.exists()) {
		LOG.debug("IDE already available in {}", ideDir)
	} else if (offline) {
		throw WemiException("Cannot download IDE: $name. Offline mode was requested. Provide pre-downloaded IDEs stored in `downloadDirectory` or use `localPaths` instead.")
	} else {
		LOG.info("Downloading IDE: {}", name)
		val ideArchive = downloadDirectory / "${name}.tar.gz"
		val url = resolveIdeUrl(type, version, buildType)

		LOG.debug("Downloading IDE from {}", url)
		if (!httpGetFile(url, ideArchive, listener)) {
			LOG.debug("Download of {} failed", url)
			return null
		}

		LOG.debug("IDE downloaded, extracting...")
		unTar(ideArchive, ideDir)
		// TODO(jp): Do we need to extra extract?
		/*
			def container = ideDir.listFiles().first()
			container.listFiles().each { it.renameTo("$ideDir/$it.name") }
			container.deleteDir()
		 */

		Files.deleteIfExists(ideArchive)
		LOG.debug("IDE extracted to {}, archive removed", ideDir)
	}

	return ideDir
}

/**
 * Resolves direct IDE download URL provided by the JetBrains Data Services.
 * The URL created with [IDE_DOWNLOAD_URL] contains HTTP redirection, which is supposed to be resolved.
 * Direct download URL is prepended with [CACHE_REDIRECTOR] host for providing caching mechanism.
 *
 * @param type IDE type, i.e. IC, PS
 * @param version IDE version, i.e. 2020.2 or 203.1234.56
 * @param buildType release, rc, eap, beta
 * @return direct download URL prepended with [CACHE_REDIRECTOR] host
 */
private fun resolveIdeUrl(type:String, version:String, buildType:String):URL {
	/* Obtains version parameter name used for downloading IDE artifact.
	 * Examples:
	 * - 202.7660.26 -> build
	 * - 2020.1, 2020.2.3 -> version
	 */
	val versionParameterName = if (version.matches(Regex("\\d{3}(\\.\\d+)+"))) "build" else "version"

	var url = IDE_DOWNLOAD_URL.withAdditionalQueryParameters(
			"code" to type,
			"platform" to "linux",
			"type" to buildType,
			versionParameterName to version
	)
	LOG.debug("Resolving direct IDE download URL for: {}", url)

	var connection:HttpURLConnection? = null

	try {
		connection = url.openConnection() as HttpURLConnection
		connection.instanceFollowRedirects = false
		connection.inputStream

		if (connection.responseCode == HttpURLConnection.HTTP_MOVED_PERM || connection.responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
			val redirectUrl = URL(connection.getHeaderField("Location"))
			url = URL("$CACHE_REDIRECTOR/${redirectUrl.host}${redirectUrl.file}")
			LOG.debug("Resolved IDE download URL: {}", url)
		} else {
			LOG.debug("IDE download URL has no redirection provided, skipping.")
		}
	} catch (e:Exception) {
		LOG.warn("Cannot resolve direct download URL for: {}", url, e)
	} finally {
		connection?.disconnect()
	}

	return url
}
