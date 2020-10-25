package wemiplugin.intellij.dependency

import com.darkyen.dave.WebbException
import org.slf4j.LoggerFactory
import wemi.boot.WemiCacheFolder
import wemi.dependency.Dependency
import wemi.dependency.Repository
import wemi.dependency.resolveDependencyArtifacts
import wemi.util.div
import wemi.util.httpGet
import wemi.util.httpGetFile
import wemi.util.toSafeFileName
import wemiplugin.intellij.PluginDependencyNotation
import wemiplugin.intellij.utils.forEachElement
import wemiplugin.intellij.utils.getFirstElement
import wemiplugin.intellij.utils.parseXml
import java.net.MalformedURLException
import java.net.URL
import java.nio.file.Path

/**
 *
 */
sealed class PluginsRepository {
	/** A maven repository with plugins */
	class Maven(val repo: Repository):PluginsRepository() {
		override fun resolve(dep: PluginDependencyNotation.PluginDependencyNotation.External):List<Path>? {
			return resolveDependencyArtifacts(listOf(Dependency(dep.toDependency())), listOf(repo), null)
		}
	}
	/** A custom repository with plugins. The URL should point to the `plugins.xml` or `updatePlugins.xml` file.
	 * See: https://jetbrains.org/intellij/sdk/docs/basics/getting_started/update_plugins_format.html */
	class Custom(val url: String):PluginsRepository() {
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
				parseXml(response)
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

		override fun resolve(dep: PluginDependencyNotation.PluginDependencyNotation.External):List<Path>? {
			val downloadUrl = (plugins ?: return null)
					.find { it.id.equals(dep.pluginId, ignoreCase = true) && it.version.equals(dep.version, ignoreCase = true) }
					?.url ?: return null
			val cacheZip = WemiCacheFolder / "-intellij-plugin-custom-repository-cache" / repoUrl.host.toSafeFileName('_') / (dep.channel?.toSafeFileName('_') ?: "default") / "${dep.pluginId}-${dep.version}.zip"
			if (httpGetFile(downloadUrl, cacheZip)) {
				return listOf(cacheZip)
			} else return null
		}
	}

	abstract fun resolve(dep: PluginDependencyNotation.PluginDependencyNotation.External):List<Path>?

	private companion object {
		private val LOG = LoggerFactory.getLogger(PluginsRepository::class.java)
	}
}