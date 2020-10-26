package wemiplugin.intellij

import Configurations
import org.jetbrains.intellij.pluginRepository.PluginRepositoryFactory
import org.slf4j.LoggerFactory
import wemi.Value
import wemi.WemiException
import wemiplugin.intellij.utils.Utils
import java.nio.file.Path

private val LOG = LoggerFactory.getLogger("PublishPlugin")

val DefaultIntellijPublishPluginToRepository: Value<Unit> = {
	val distributionFile: Path = using(Configurations.publishing) { IntelliJ.intellijPluginArchive.get() }
	val host = IntelliJ.intellijPublishPluginRepository.get()
	val token: String = IntelliJ.intellijPublishPluginToken.get()
	val channels = IntelliJ.intellijPublishPluginChannels.get()

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
		} catch (e: Exception) {
			throw WemiException("Failed to upload plugin", e)
		}
	}
}