package wemiplugin.intellij.tasks

import Configurations
import org.jetbrains.intellij.pluginRepository.PluginRepositoryFactory
import org.slf4j.LoggerFactory
import wemi.BindingHolder
import wemi.Key
import wemi.WemiException
import wemi.key
import wemiplugin.intellij.IntelliJ
import wemiplugin.intellij.utils.Utils
import java.nio.file.Path

/**
 *
 */
object PublishTask {

	private val LOG = LoggerFactory.getLogger(PublishTask::class.java)

	val publishPluginToRepository: Key<Unit> by key("Publish plugin distribution on plugins.jetbrains.com")
	val publishPluginRepository: Key<String> by key("Repository to which the IntelliJ plugins are published to", "https://plugins.jetbrains.com")
	val publishPluginToken: Key<String> by key("Plugin publishing token")
	val publishPluginChannels: Key<List<String>> by key("Channels to which the plugin is published", listOf("default"))

	internal fun BindingHolder.setupPublishTask() {
		publishPluginToRepository set {
			if (!IntelliJ.verifyPlugin.get()) {
				throw WemiException("Can't publish when the verification fails")
			}

			val distributionFile: Path = using(Configurations.publishing) { IntelliJ.intellijPluginArchive.get() }
			val host = publishPluginRepository.get()
			val token:String = publishPluginToken.get()
			val channels = publishPluginChannels.get()

			if (channels.isEmpty()) {
				throw WemiException("Publish channels are empty", false)
			}

			val plugin = Utils.createPlugin(distributionFile, validatePluginXml = true, logProblems = true)
					?: throw WemiException("Cannot upload plugin, archive is invalid", false)

			val pluginId = plugin.pluginId!!
			for (channel in channels) {
				LOG.info("Uploading plugin {} from {} to {}, channel: {}", pluginId, distributionFile, host, channel)
				try {
					val repoClient = PluginRepositoryFactory.create(host, token)
					repoClient.uploader.uploadPlugin(pluginId, distributionFile.toFile(), if (channel == "default") null else channel, null)
					LOG.info("Uploaded successfully")
				} catch (e:Exception) {
					throw WemiException("Failed to upload plugin", e)
				}
			}
		}
	}
}
