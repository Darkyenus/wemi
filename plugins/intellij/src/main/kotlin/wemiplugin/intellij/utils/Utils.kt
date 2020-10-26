package wemiplugin.intellij.utils

import Files
import Keys
import com.jetbrains.plugin.structure.base.plugin.PluginCreationFail
import com.jetbrains.plugin.structure.base.plugin.PluginCreationSuccess
import com.jetbrains.plugin.structure.base.plugin.PluginProblem
import com.jetbrains.plugin.structure.intellij.plugin.IdePlugin
import com.jetbrains.plugin.structure.intellij.plugin.IdePluginManager
import org.slf4j.LoggerFactory
import wemi.EvalScope
import wemi.KeyDefaults.inProjectDependencies
import wemi.dependency.internal.OS_FAMILY
import wemi.dependency.internal.OS_FAMILY_MAC
import wemi.util.LocatedPath
import wemi.util.absolutePath
import wemi.util.div
import wemi.util.exists
import wemi.util.isRegularFile
import wemi.util.jdkToolsJar
import wemi.util.name
import wemi.util.pathHasExtension
import wemiplugin.intellij.IntelliJ
import java.io.IOException
import java.nio.file.Path
import java.util.*
import java.util.regex.Pattern
import java.util.stream.Stream
import kotlin.collections.ArrayList

/**
 *
 */
object Utils {

	private val LOG = LoggerFactory.getLogger(Utils.javaClass)

	val VERSION_PATTERN = Pattern.compile("^([A-Z]{2})-([0-9.A-z]+)\\s*$")

	fun EvalScope.sourcePluginXmlFiles(validate:Boolean = true):List<LocatedPath> {
		return Keys.resources.getLocatedPaths().filter {
			it.path == "META-INF/plugin.xml" && (!validate || run {
				// Validate this file, that it really contains idea-plugin
				val xml = parseXml(it.file) ?: return@run false
				xml.documentElement.getFirstElement("idea-plugin") != null
			})
		}
	}

	fun getIdeaSystemProperties(configDirectory:Path, systemDirectory:Path, pluginsDirectory:Path, requirePluginIds:List<String>):Map<String, String> {
		val result = HashMap<String, String>()
		result["idea.config.path"] = configDirectory.absolutePath
		result["idea.system.path"] = systemDirectory.absolutePath
		result["idea.plugins.path"] = pluginsDirectory.absolutePath
		if (requirePluginIds.isNotEmpty()) {
			result["idea.required.plugins.id"] = requirePluginIds.joinToString(",")
		}
		return result
	}

	// TODO(jp): This should be built-int into ideaDependency resolution!
	fun EvalScope.ideSdkDirectory():Path {
		return IntelliJ.resolvedIntellijIdeDependency.get().classes
	}

	fun ideBuildNumber(ideDirectory: Path):String {
		if (OS_FAMILY == OS_FAMILY_MAC) {
			val file = ideDirectory.resolve("Resources/build.txt")
			if (file.exists()) {
				return String(Files.readAllBytes(file), Charsets.UTF_8).trim()
			}
		}
		return String(Files.readAllBytes(ideDirectory.resolve("build.txt")), Charsets.UTF_8).trim()
	}

	fun ideaDir(path:Path):Path {
		if (path.name.pathHasExtension("app")) {
			return path.resolve("Contents")
		} else return path
	}

	fun EvalScope.getPluginIds():List<String> {
		val result = ArrayList<String>()
		getProjectPluginIds(result)
		inProjectDependencies(null) {
			getProjectPluginIds(result)
		}
		return result
	}

	private fun EvalScope.getProjectPluginIds(to:MutableList<String>) {
		for (pluginXml in sourcePluginXmlFiles(false)) {
			val id = parseXml(pluginXml.file)?.documentElement?.getFirstElement("idea-plugin")?.getFirstElement("id")?.textContent
			if (id != null) {
				to.add(id)
			}
		}
	}

	fun isJarFile(file:Path):Boolean = file.name.pathHasExtension("jar")

	fun isZipFile(file:Path):Boolean = file.name.pathHasExtension("zip")

	fun collectJars(directory:Path): Stream<Path> {
		return Files.list(directory).filter { it.isRegularFile() && it.name.pathHasExtension("jar") }
	}

	// TODO(jp): Make it take javaHome and remove it, if possible
	fun resolveToolsJar(javaExec:Path):Path? {
		val bin = javaExec.parent
		val home = if (OS_FAMILY == OS_FAMILY_MAC) bin.parent.parent else bin.parent
		return jdkToolsJar(home)
	}

	fun getBuiltinJbrVersion(ideDirectory:Path):String? {
		val dependenciesFile = ideDirectory / "dependencies.txt"
		if (dependenciesFile.exists()) {
			val properties = Properties()
			try {
				Files.newBufferedReader(dependenciesFile, Charsets.UTF_8).use { reader ->
					properties.load(reader)
					return properties.getProperty("jdkBuild")
				}
			} catch (ignore: IOException) {}
		}
		return null
	}

	private val MAJOR_VERSION_PATTERN = Pattern.compile("(RIDER-)?\\d{4}\\.\\d-SNAPSHOT")

	fun releaseType(version:String):String {
		if (version.endsWith("-EAP-SNAPSHOT") || version.endsWith("-EAP-CANDIDATE-SNAPSHOT") || version.endsWith("-CUSTOM-SNAPSHOT") || MAJOR_VERSION_PATTERN.matcher(version).matches()) {
			return "snapshots"
		}
		if (version.endsWith("-SNAPSHOT")) {
			return "nightly"
		}
		return "releases"
	}

	fun createPlugin(artifact:Path, validatePluginXml:Boolean, logProblems:Boolean): IdePlugin? {
		if (!artifact.exists()) {
			if (logProblems) {
				LOG.warn("Cannot create plugin from {}: file does not exist", artifact)
			}
			return null
		}
		return when (val creationResult = IdePluginManager.createManager().createPlugin(artifact, validatePluginXml)) {
			is PluginCreationSuccess -> {
				val warnings = creationResult.warnings
				if (logProblems && warnings.isNotEmpty()) {
					LOG.warn("{} warning(s) on plugin from {}", warnings.size, artifact)
					for (warning in warnings) {
						LOG.warn("{}", warning)
					}
				}
				creationResult.plugin
			}
			is PluginCreationFail -> {
				if (logProblems) {
					val warningCount = creationResult.errorsAndWarnings.count { it.level == PluginProblem.Level.WARNING }
					val errorCount = creationResult.errorsAndWarnings.size - warningCount
					LOG.warn("Cannot create plugin from {}: {} error(s) and {} warning(s)", artifact, errorCount, warningCount)
					for (problem in creationResult.errorsAndWarnings) {
						LOG.warn("{}", problem)
					}
				}
				null
			}
		}
	}

}