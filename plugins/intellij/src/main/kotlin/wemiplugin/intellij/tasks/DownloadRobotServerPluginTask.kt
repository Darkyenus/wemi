package wemiplugin.intellij.tasks

import wemi.BindingHolder
import wemi.WemiException
import wemi.boot.WemiCacheFolder
import wemi.dependency
import wemi.dependency.Repository
import wemi.dependency.resolveDependencyArtifacts
import wemi.key
import wemi.util.div
import wemi.util.isDirectory
import wemiplugin.intellij.utils.unZip
import java.nio.file.Path

object DownloadRobotServerPluginTask {

	private val ROBOT_SERVER_REPO = Repository("intellij-third-party-dependencies", "https://jetbrains.bintray.com/intellij-third-party-dependencies")
	private const val ROBOT_SERVER_DEPENDENCY = "org.jetbrains.test:robot-server-plugin"
	const val DEFAULT_ROBOT_SERVER_PLUGIN_VERSION = "0.9.35"

	val intellijRobotServerVersion by key("JetBrains robot-server plugin for UI testing version", DEFAULT_ROBOT_SERVER_PLUGIN_VERSION)

	val intellijRobotServerPlugin by key<Path>("Directory with JetBrains robot-server plugin for UI testing")

	internal fun BindingHolder.setupDownloadRobotServerPluginTask() {
		intellijRobotServerPlugin set {
			val outputDir = WemiCacheFolder / "intellijRobotServerPlugin"
			if (!outputDir.isDirectory()) {
				val artifacts = resolveDependencyArtifacts(listOf(dependency(ROBOT_SERVER_DEPENDENCY+":"+intellijRobotServerVersion.get())), listOf(ROBOT_SERVER_REPO), null)
						?: throw WemiException("Failed to obtain robot-server dependency", false)
				val artifactZip = artifacts.singleOrNull()
						?: throw WemiException("Failed to obtain robot-server dependency - single artifact expected, but got $artifacts", false)
				unZip(artifactZip, outputDir)
			}
			outputDir
		}
	}
}