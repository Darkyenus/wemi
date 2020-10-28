package wemiplugin.intellij

import com.darkyen.dave.WebbException
import com.jetbrains.plugin.structure.intellij.version.IdeVersion
import org.slf4j.LoggerFactory
import wemi.ActivityListener
import wemi.EvalScope
import wemi.Value
import wemi.WemiException
import wemi.boot.WemiCacheFolder
import wemi.boot.WemiSystemCacheFolder
import wemi.dependency
import wemi.dependency.ProjectDependency
import wemi.dependency.Repository
import wemi.dependency.TypeChooseByPackaging
import wemi.dependency.resolveDependencyArtifacts
import wemi.util.absolutePath
import wemi.util.div
import wemi.util.exists
import wemi.util.httpGet
import wemi.util.httpGetFile
import wemi.util.isDirectory
import wemi.util.name
import wemi.util.pathHasExtension
import wemi.util.toSafeFileName
import wemiplugin.intellij.utils.Utils
import wemiplugin.intellij.utils.Utils.sourcePluginXmlFiles
import wemiplugin.intellij.utils.forEachElement
import wemiplugin.intellij.utils.getFirstElement
import wemiplugin.intellij.utils.namedElements
import wemiplugin.intellij.utils.parseXml
import wemiplugin.intellij.utils.unZipIfNew
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.MalformedURLException
import java.net.URL
import java.nio.file.Path

private val LOG = LoggerFactory.getLogger("ResolvePlugins")

/** Represents a dependency on an IntelliJ plugin. */
sealed class IntelliJPluginDependency {
	/** Dependency on a bundled plugin */
	data class Bundled(val name: String):IntelliJPluginDependency()

	/** Dependency on a plugin from the Jetbrains Plugin Repository */
	data class External(val pluginId: String, val version: String, val channel: String? = null):IntelliJPluginDependency()

	/** Locally downloaded plugin */
	data class Local(val zipPath: Path):IntelliJPluginDependency()

	/** A dependency on a local project which is also an IntelliJ plugin */
	data class Project(val projectDependency: ProjectDependency):IntelliJPluginDependency()
}

/** A resolved dependency on an IDE plugin. */
class ResolvedIntelliJPluginDependency(val dependency:IntelliJPluginDependency, val artifact:Path,
                                       val sinceIdeVersion:IdeVersion? = null, val untilIdeVersion:IdeVersion? = null) {

	val isBuiltin:Boolean
		get() = dependency is IntelliJPluginDependency.Bundled

	fun isCompatible(ideVersion: IdeVersion):Boolean {
		if (sinceIdeVersion != null && ideVersion < sinceIdeVersion) return false
		if (untilIdeVersion != null && untilIdeVersion < ideVersion) return false
		return true
	}

	fun classpath():List<Path> {
		val classpath = ArrayList<Path>()

		if (Utils.isJarFile(artifact)) {
			classpath.add(artifact)
		} else if (artifact.isDirectory()) {
			val lib = artifact / "lib"
			for (jar in Utils.collectJars(lib)) {
				classpath.add(jar)
			}
			val classes = artifact / "classes"
			if (classes.isDirectory()) {
				classpath.add(classes)
			}
		}

		return classpath
	}

	override fun toString(): String {
		return "ResolvedIntelliJPluginDependency(dependency=$dependency, artifact=$artifact, sinceIdeVersion=$sinceIdeVersion, untilIdeVersion=$untilIdeVersion)"
	}
}

/** A repository for IntelliJ Platform plugins. */
sealed class IntelliJPluginRepository {

	/** A maven repository with plugins */
	data class Maven(val repo: Repository):IntelliJPluginRepository() {
		override fun resolve(dep: IntelliJPluginDependency.External, progressListener: ActivityListener?):Path? {
			val dependency = dependency(
					(if (dep.channel != null) "${dep.channel}." else "") + "com.jetbrains.plugins",
					dep.pluginId,
					dep.version,
					type = TypeChooseByPackaging
			)

			return resolveDependencyArtifacts(listOf(dependency), listOf(repo), progressListener)?.firstOrNull()
		}
	}

	/** A custom repository with plugins. The URL should point to the `plugins.xml` or `updatePlugins.xml` file.
	 * See: https://jetbrains.org/intellij/sdk/docs/basics/getting_started/update_plugins_format.html */
	class Custom(url: String):IntelliJPluginRepository() {
		val repoUrl: URL
		val pluginsXmlUrl: URL

		init {
			val rawUrl = URL(url)
			var xmlPath = rawUrl.path
			var repoPath = xmlPath
			if (xmlPath.endsWith(".xml", ignoreCase = true)) {
				repoPath = xmlPath.dropLastWhile { it != '/' }
			} else {
				xmlPath = "$repoPath/updatePlugins.xml"
			}
			if (!repoPath.endsWith('/')) {
				repoPath += '/'
			}
			repoUrl = URL(rawUrl.protocol, rawUrl.host, rawUrl.port, repoPath)
			pluginsXmlUrl = URL(rawUrl.protocol, rawUrl.host, rawUrl.port, xmlPath)
		}

		private class Plugin(val id:String, val version:String, val url: URL)

		private fun pluginUrl(url:String): URL? {
			try {
				return URL(repoUrl, url)
			} catch (e: MalformedURLException) {
				LOG.warn("Plugin download url {} is malformed", url, e)
				return null
			}
		}

		private val plugins:List<Plugin>? by lazy {
			val response = try {
				httpGet(pluginsXmlUrl).ensureSuccess().executeBytes().body
			} catch (e: WebbException) {
				LOG.warn("Failed to retrieve {}", pluginsXmlUrl, e)
				return@lazy null
			}
			val document = try {
				parseXml(InputStreamReader(ByteArrayInputStream(response), Charsets.UTF_8))
			} catch (e:Exception) {
				LOG.warn("Failed to parse {}", pluginsXmlUrl, e)
				return@lazy null
			}

			val rootNode = document.documentElement
			if (rootNode.tagName.equals("plugins", ignoreCase = true)) {
				// https://jetbrains.org/intellij/sdk/docs/basics/getting_started/update_plugins_format.html
				val result = ArrayList<Plugin>()
				rootNode.forEachElement("plugin") { item ->
					val id = item.getAttribute("id") ?: return@forEachElement
					val version = item.getAttribute("version") ?: return@forEachElement
					val downloadUrl = item.getAttribute("url")?.let(::pluginUrl) ?: return@forEachElement
					result.add(Plugin(id, version, downloadUrl))
				}
				return@lazy result
			} else if (rootNode.tagName.equals("plugin-repository", ignoreCase = true)) {
				// I couldn't find any official documentation of this schema, but an example of this seems to be here:
				// https://github.com/LiveRamp/liveramp-idea-inspections-plugin/blob/af86fb9166d1f7d76c18eae1945404866d4852b5/plugins.xml
				// And original implementation suggests that this is all there is to it.
				val result = ArrayList<Plugin>()
				rootNode.forEachElement("category") {
					it.forEachElement("idea-plugin") p@{ plugin ->
						val id = plugin.getFirstElement("id")?.textContent ?: return@p
						val version = plugin.getFirstElement("version")?.textContent ?: return@p
						val downloadUrl = plugin.getFirstElement("download-url")?.textContent?.let(::pluginUrl) ?: return@p
						result.add(Plugin(id, version, downloadUrl))
					}
				}
				return@lazy result
			} else {
				LOG.warn("Unrecognized structure of {}: {}", pluginsXmlUrl, rootNode)
				return@lazy null
			}
		}

		override fun resolve(dep: IntelliJPluginDependency.External, progressListener: ActivityListener?):Path? {
			val downloadUrl = (plugins ?: return null)
					.find { it.id.equals(dep.pluginId, ignoreCase = true) && it.version.equals(dep.version, ignoreCase = true) }
					?.url ?: return null
			val cacheZip = WemiCacheFolder / "-intellij-plugin-custom-repository-cache" / repoUrl.host.toSafeFileName('_') / (dep.channel?.toSafeFileName('_') ?: "default") / "${dep.pluginId}-${dep.version}.zip"
			if (httpGetFile(downloadUrl, cacheZip, progressListener)) {
				return cacheZip
			} else return null
		}

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is Custom) return false

			if (pluginsXmlUrl != other.pluginsXmlUrl) return false

			return true
		}

		override fun hashCode(): Int {
			return pluginsXmlUrl.hashCode()
		}

		override fun toString(): String {
			return "Custom(pluginsXmlUrl=$pluginsXmlUrl)"
		}
	}

	abstract fun resolve(dep: IntelliJPluginDependency.External, progressListener: ActivityListener?):Path?
}

private val cacheDirectoryPath: Path = WemiSystemCacheFolder / "intellij-plugin-cache"

val DefaultResolvedIntellijPluginDependencies : Value<List<ResolvedIntelliJPluginDependency>> = {
	val ideaDependency = IntelliJ.resolvedIntellijIdeDependency.get()
	val ideVersion = ideaDependency.version
	val repositories = IntelliJ.intellijPluginRepositories.get()

	val pluginDependencyIds = HashSet(IntelliJ.intellijPluginDependencies.get())
	// Collect transitive dependencies of built-in plugins and add them as well
	pluginDependencyIds.addAll(ideaDependency.pluginsRegistry.collectBuiltinDependencies(
			pluginDependencyIds.mapNotNull { if (it is IntelliJPluginDependency.Bundled) it.name else null }
	).map { IntelliJPluginDependency.Bundled(it) })

	val pluginDependencies = ArrayList<ResolvedIntelliJPluginDependency>()
	for (it in pluginDependencyIds) {
		val resolved:ResolvedIntelliJPluginDependency = resolveIntelliJPlugin(it, ideaDependency, repositories)
		if (!resolved.isCompatible(ideVersion)) {
			throw WemiException("Plugin $it is not compatible to ${ideVersion.asString()}", false)
		}
		pluginDependencies.add(resolved)
	}

	// TODO(jp): Add all Maven plugin repositories which were used to resolve something to Keys.repositories (original plugin does this, for some reason)

	// TODO(jp): Maybe there is a better way to do this? I.e. managing dependencies from Wemi directly and patching xml?
	if (!pluginDependencyIds.any { it is IntelliJPluginDependency.Bundled && it.name == "java" } && (ideaDependency.homeDir / "plugins/java").exists()) {
		for (file in sourcePluginXmlFiles(false)) {
			val pluginXml = parseXml(file.file) ?: continue
			val depends = pluginXml.documentElement?.namedElements("depends") ?: continue
			for (depend in depends) {
				if (depend.textContent == "com.intellij.modules.java") {
					throw WemiException("The project depends on `com.intellij.modules.java` module but doesn't declare a compile dependency on it.\n" +
							"Please delete `depends` tag from ${file.file.absolutePath} or add `java` plugin to Wemi dependencies")// TODO(jp): How it should look
				}
			}
		}
	}

	pluginDependencies
}

fun EvalScope.resolveIntelliJPlugin(dependency: IntelliJPluginDependency, resolvedIntelliJIDE: ResolvedIntelliJIDE, repositories: List<IntelliJPluginRepository>) : ResolvedIntelliJPluginDependency {
	return when (dependency) {
		is IntelliJPluginDependency.Bundled -> {
			LOG.info("Looking for builtin {} in {}", dependency.name, resolvedIntelliJIDE.homeDir)
			val pluginDirectory = resolvedIntelliJIDE.pluginsRegistry.findPlugin(dependency.name)
			if (pluginDirectory != null) {
				ResolvedIntelliJPluginDependency(dependency, pluginDirectory)
			} else {
				throw WemiException("Cannot find builtin plugin ${dependency.name} for IDE: ${resolvedIntelliJIDE.homeDir}", false)
			}
		}
		is IntelliJPluginDependency.External -> {
			for (repo in repositories) {
				val pluginFile = repo.resolve(dependency, progressListener)
				if (pluginFile != null) {
					if (pluginFile.name.pathHasExtension("zip")) {
						return zippedPluginDependency(pluginFile, dependency)
					} else if (Utils.isJarFile(pluginFile)) {
						return externalPluginDependency(pluginFile, dependency)
					}
					throw WemiException("Invalid type of downloaded plugin: ${pluginFile.name}", false)
				}
			}
			throw WemiException("Cannot resolve plugin $dependency.id version $dependency.version ${if (dependency.channel != null) "from channel ${dependency.channel}" else ""}", false)
		}
		is IntelliJPluginDependency.Local -> {
			externalPluginDependency(dependency.zipPath, null)
		}
		is IntelliJPluginDependency.Project -> {
			using(dependency.projectDependency.project, *dependency.projectDependency.configurations) {
				val archive = IntelliJ.intellijPluginArchive.get()
				val intellijPlugin = Utils.createPlugin(archive, validatePluginXml = false, logProblems = true)
						?: throw WemiException("Cannot use ${dependency.projectDependency} as a plugin dependency - it is invalid", false)
				ResolvedIntelliJPluginDependency(dependency, archive, intellijPlugin.sinceBuild, intellijPlugin.untilBuild)
			}
		}
	}
}

private fun zippedPluginDependency(pluginFile: Path, dependency: IntelliJPluginDependency.External):ResolvedIntelliJPluginDependency {
	val pluginDir = cacheDirectoryPath / "${dependency.pluginId}-${dependency.version}${if (dependency.channel != null) "-${dependency.channel}" else ""}"
	unZipIfNew(pluginFile, pluginDir, existenceIsEnough = true)
	return externalPluginDependency(pluginDir, dependency)
}

private fun externalPluginDependency(artifact: Path, dependency:IntelliJPluginDependency.External?): ResolvedIntelliJPluginDependency {
	val intellijPlugin = Utils.createPlugin(artifact, validatePluginXml = false, logProblems = true)
			?: throw WemiException("Can't create plugin from $artifact")
	return ResolvedIntelliJPluginDependency(IntelliJPluginDependency.External(
			intellijPlugin.pluginId ?: dependency?.pluginId!!,
			intellijPlugin.pluginVersion ?: dependency?.version!!, dependency?.channel),
			artifact, intellijPlugin.sinceBuild, intellijPlugin.untilBuild)
}