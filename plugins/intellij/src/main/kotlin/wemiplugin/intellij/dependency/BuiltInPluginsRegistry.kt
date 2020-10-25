package wemiplugin.intellij.dependency

import org.slf4j.LoggerFactory
import wemi.util.div
import wemi.util.exists
import wemi.util.isDirectory
import java.io.IOException
import java.nio.file.Path

/**
 *
 */
class BuiltinPluginsRegistry (private val pluginsDirectory: Path) {

	private val plugins = HashMap<String, Plugin>()
	private val directoryNameMapping = HashMap<String, String>()


	private fun fillFromCache():Boolean {
		val cache = cacheFile()
		if (!cache.exists()) {
			return false
		}

		LOG.debug("Builtin registry cache is found. Loading from {}", cache)
		try {
			Utils.parseXml(cache).children().forEach { node ->
				def dependencies = node.dependencies.first().dependency*.text() as Collection<String>
				plugins.put(node.@id, new Plugin(node.@id, node.@directoryName, dependencies))
				directoryNameMapping.put(node.@directoryName, node.@id)
			}
			return true
		} catch (Throwable t) {
			Utils.warn(loggingContext, "Cannot read builtin registry cache", t)
			return false
		}
	}


	private fun fillFromDirectory() {
		try {
			for (path in Files.list(pluginsDirectory)) {
				if (path.isDirectory()) {
					add(path)
				}
			}
		} catch (e: IOException) {
			LOG.debug("Failed to read {}", pluginsDirectory, e)
		}
		LOG.debug("Builtin registry populated with {} plugins", plugins.size)
	}

	private fun dumpToCache() {
		LOG.debug("Dumping cache for builtin plugin")
		val cacheFile = cacheFile()
		def writer = null
		try {
			writer = new FileWriter(cacheFile)
			new MarkupBuilder(writer).plugins {
				for (p in plugins) {
					plugin(id: p.key, directoryName: p.value.directoryName) {
						dependencies {
							for (def d in p.value.dependencies) {
							dependency(d)
						}
						}
					}
				}
			}
		} catch (Throwable t) {
			Utils.warn(loggingContext, "Failed to dump cache for builtin plugin", t)
		} finally {
			if (writer != null) {
				writer.close()
			}
		}
	}

	private fun cacheFile():Path {
		return pluginsDirectory / "builtinRegistry.xml"
	}

	fun findPlugin(name:String):Path? {
		val plugin = plugins[name] ?: plugins[directoryNameMapping[name]]
		if (plugin != null) {
			val result = pluginsDirectory / plugin.directoryName
			return if (result.isDirectory()) result else null
		}
		return null
	}

	fun collectBuiltinDependencies(pluginIds:Collection<String>):Collection<String> {
		val idsToProcess = ArrayList(pluginIds)
		val result = HashSet<String>()
		while (idsToProcess.isNotEmpty()) {
			val id = idsToProcess.removeAt(0)
			val plugin = plugins[id] ?: plugins[directoryNameMapping[id]]
			if (plugin != null && result.add(id)) {
				idsToProcess.addAll(plugin.dependencies - result)
			}
		}
		return result
	}

	fun add(artifact:Path) {
		LOG.debug("Adding directory to plugins index: {})", artifact)
		val intellijPlugin = Utils.createPlugin(artifact, false, loggingContext)
		if (intellijPlugin != null) {
			def dependencies = intellijPlugin.dependencies
					.findAll { !it.optional }
					.collect { it.id }
			def plugin = new Plugin(intellijPlugin.pluginId, artifact.name, dependencies)
			plugins.put(intellijPlugin.pluginId, plugin)
			if (plugin.directoryName != plugin.id) {
				directoryNameMapping.put(plugin.directoryName, plugin.id)
			}
		}
	}

	companion object {
		private val LOG = LoggerFactory.getLogger(BuiltinPluginsRegistry::class.java)

		fun fromDirectory(pluginsDirectory: Path): BuiltinPluginsRegistry {
			val result = BuiltinPluginsRegistry(pluginsDirectory)
			if (!result.fillFromCache()) {
				LOG.debug("Builtin registry cache is missing")
				result.fillFromDirectory()
				result.dumpToCache()
			}
			return result
		}
	}

	private class Plugin(val id:String, val directoryName:String, val dependencies:Collection<String>)
}