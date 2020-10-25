package wemiplugin.intellij.utils

import org.slf4j.LoggerFactory
import wemi.EvalScope
import wemi.KeyDefaults.inProjectDependencies
import wemi.Project
import wemi.dependency.internal.OS_FAMILY
import wemi.dependency.internal.OS_FAMILY_MAC
import wemi.util.LocatedPath
import wemi.util.absolutePath
import wemi.util.div
import wemi.util.exists
import wemi.util.jdkToolsJar
import wemi.util.matchingLocatedFiles
import wemi.util.name
import wemi.util.pathHasExtension
import wemiplugin.intellij.IntelliJ
import wemiplugin.intellij.dependency.IntellijIvyArtifact
import wemiplugin.intellij.dependency.PluginDependencyNotation
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.*
import java.util.function.Predicate
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import kotlin.collections.HashSet

/**
 *
 */
object Utils {

	private val LOG = LoggerFactory.getLogger(Utils.javaClass)

	val VERSION_PATTERN = Pattern.compile("^([A-Z]{2})-([0-9.A-z]+)\\s*$")

	fun createJarDependency(file: File, configuration:String, baseDir:File, classifier:String? = null):IvyArtifact {
		return createDependency(baseDir, file, configuration, "jar", "jar", classifier)
	}

	fun createDirectoryDependency(file:File, configuration:String, baseDir:File, classifier:String? = null):IvyArtifact {
		return createDependency(baseDir, file, configuration, "", "directory", classifier)
	}

	private fun createDependency(baseDir:File, file:File, configuration:String, extension:String, type:String, classifier:String):IvyArtifact {
		val relativePath = baseDir.toURI().relativize(file.toURI()).getPath()
		val name = extension ? relativePath - ".$extension" : relativePath
		val artifact = IntellijIvyArtifact(file, name, extension, type, classifier)
		artifact.conf = configuration
		return artifact
	}

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

	fun EvalScope.ideSdkDirectory():Path {
		val path = IntelliJ.alternativeIdePath?.let { ideaDir(it) }
		val usedPath = if (path == null || !path.exists()) {
			IntelliJ.ideaDependency.get().classes
		} else path
		if (path != null && usedPath != path) {
			LOG.warn("Cannot find alternate SDK path: {}. Default IDE will be used: {}", path, usedPath)
		}
		return usedPath
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

	fun collectJars(directory:File, filter: Predicate<File>):Collection<File> {
		return FileUtils.listFiles(directory, new AbstractFileFilter() {
			@Override
			boolean accept(File file) {
				return isJarFile(file) && filter.test(file)
			}
		}, FalseFileFilter.FALSE)
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

	fun unzip(zipFile:Path, cacheDirectory:Path, project:Project, isUpToDate:Predicate<File>?, markUpToDate:BiConsumer<File, File>?, targetDirName:String? = null):Path {
		def targetDirectory = new File(cacheDirectory, targetDirName ?: zipFile.name - ".zip")
		def markerFile = new File(targetDirectory, "markerFile")
		if (markerFile.exists() && (isUpToDate == null || isUpToDate.test(markerFile))) {
			return targetDirectory
		}

		if (targetDirectory.exists()) {
			targetDirectory.deleteDir()
		}
		targetDirectory.mkdir()

		debug(project, "Unzipping ${zipFile.name}")
		project.copy {
			it.from(project.zipTree(zipFile))
			it.into(targetDirectory)
		}
		debug(project, "Unzipped ${zipFile.name}")

		markerFile.createNewFile()
		if (markUpToDate != null) {
			markUpToDate.accept(targetDirectory, markerFile)
		}
		return targetDirectory
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

	fun createPlugin(artifact:File, validatePluginXml:Boolean):IdePlugin {
		val creationResult = IdePluginManager.createManager().createPlugin(artifact.toPath(), validatePluginXml, IdePluginManager.PLUGIN_XML)
		if (creationResult is PluginCreationSuccess) {
			return creationResult.plugin as IdePlugin
		} else if (creationResult instanceof PluginCreationFail) {
			val problems = creationResult.errorsAndWarnings.findAll { it.level == PluginProblem.Level.ERROR }.join(", ")
			warn(loggingContext, "Cannot create plugin from file ($artifact): $problems")
		} else {
			warn(loggingContext, "Cannot create plugin from file ($artifact). $creationResult")
		}
		return null
	}

}