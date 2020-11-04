package wemiplugin.intellij.utils

import Files
import org.slf4j.LoggerFactory
import wemi.util.div
import wemi.util.isDirectory
import wemi.util.name
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 *
 */
class BuiltinPluginsRegistry (private val pluginsDirectory: Path) {

	private val pluginsById = HashMap<String, Plugin>()
	private val pluginsByFileName = HashMap<String, Plugin>()

	private fun cacheFile():Path {
		return pluginsDirectory / "builtinRegistry.bin"
	}

	private fun fillFromCache():Boolean {
		val cache = cacheFile()
		try {
			DataInputStream(BufferedInputStream(Files.newInputStream(cache))).use { inp ->
				LOG.debug("Builtin registry cache is found. Loading from {}", cache)

				for (i in 0 until inp.readInt()) {
					val id = inp.readUTF()
					val directoryName = inp.readUTF()
					val dependenciesSize = inp.readInt()
					val dependencies = ArrayList<String>(dependenciesSize)
					for (d in 0 until dependenciesSize) {
						dependencies.add(inp.readUTF())
					}
					val plugin = Plugin(id, directoryName, dependencies)
					pluginsById[plugin.id] = plugin
					if (plugin.directoryName != plugin.id) {
						pluginsByFileName[plugin.directoryName] = plugin
					}
				}
			}
			return true
		} catch (e:FileNotFoundException) {
			return false
		} catch (e:NoSuchFileException) {
			return false
		} catch (e:Exception) {
			LOG.warn("Failed to read builtin plugin cache from {}", cache, e)
			return false
		}
	}

	private fun dumpToCache() {
		LOG.debug("Dumping cache for builtin plugin")
		val cacheFile = cacheFile()
		try {
			DataOutputStream(BufferedOutputStream(Files.newOutputStream(cacheFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))).use { out ->
				out.writeInt(pluginsById.size)
				for (plugin in pluginsById.values) {
					out.writeUTF(plugin.id)
					out.writeUTF(plugin.directoryName)
					out.writeInt(plugin.dependencies.size)
					for (dependency in plugin.dependencies) {
						out.writeUTF(dependency)
					}
				}
			}
		} catch (e:Exception) {
			LOG.warn("Failed to dump cache to {} for builtin plugin", cacheFile, e)
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
		LOG.debug("Builtin registry populated with {} plugins", pluginsById.size)
	}

	fun findPlugin(name:String):Path? {
		val plugin = pluginsById[name] ?: pluginsByFileName[name]
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
			val plugin = pluginsById[id] ?: pluginsById[id]
			if (plugin != null && result.add(id)) {
				idsToProcess.addAll(plugin.dependencies - result)
			}
		}
		return result
	}

	fun add(artifact:Path) {
		LOG.debug("Adding directory to plugins index: {}", artifact)
		val intellijPlugin = Utils.createPlugin(artifact, validatePluginXml = false, logProblems = false) ?: return
		val dependencies = intellijPlugin.dependencies.filterNot { it.isOptional }.map { it.id }
		val plugin = Plugin(intellijPlugin.pluginId!!, artifact.name, dependencies)
		pluginsById[plugin.id] = plugin
		if (plugin.directoryName != plugin.id) {
			pluginsByFileName[plugin.directoryName] = plugin
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