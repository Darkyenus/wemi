package wemiplugin.intellij.tasks

import org.slf4j.LoggerFactory
import wemi.BindingHolder
import wemi.Key
import wemi.WemiException
import wemi.key
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
			if (!VerifyPluginTask.verifyPlugin.get()) {
				throw WemiException("Can't publish when the verification fails")
			}

			val distributionFile: Path = using(Configurations.publishing) { Keys.archive.get() }
					?: throw WemiException("publishing/archive can't be null when publishing plugin")
			val host = publishPluginRepository.get()
			val token:String = publishPluginToken.get()
			val channels = publishPluginChannels.get()

			if (channels.isEmpty()) {
				throw WemiException("Publish channels are empty", false)
			}

			val creationResult = IdePluginManager.createManager().createPlugin(distributionFile)
			if (creationResult is PluginCreationSuccess) {
				val pluginId = creationResult.plugin.pluginId
				for (channel in channels) {
					LOG.info("Uploading plugin {} from {} to {}, channel: {}", pluginId, distributionFile, host, channel)
					try {
						val repoClient = PluginRepositoryFactory.create(host, token)
						repoClient.uploader.uploadPlugin(pluginId, distributionFile, if (channel == "default") null else channel, null)
						LOG.info("Uploaded successfully")
					} catch (e:Exception) {
						throw WemiException("Failed to upload plugin", e)
					}
				}
			} else if (creationResult is PluginCreationFail) {
				val problems = creationResult.errorsAndWarnings.filter { it.level == PluginProblem.Level.ERROR }.join(", ")
				throw WemiException("Cannot upload plugin. $problems", false)
			} else {
				throw WemiException("Cannot upload plugin. $creationResult", false)
			}

		}
	}
}
