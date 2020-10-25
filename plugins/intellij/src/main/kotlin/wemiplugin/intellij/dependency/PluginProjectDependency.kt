package wemiplugin.intellij.dependency

import wemi.Project
import wemi.dependency.ProjectDependency
import wemiplugin.intellij.IntelliJ
import wemiplugin.intellij.PluginDependencyNotation
import java.nio.file.Path

/**
 *
 */
class PluginProjectDependency(private var project: Project) : PluginDependency {

	private val pluginDirectory: Path by lazy {
		def prepareSandboxTask = project?.tasks?.findByName(IntelliJPlugin.PREPARE_SANDBOX_TASK_NAME)
		return prepareSandboxTask instanceof PrepareSandboxTask ?
		new File(prepareSandboxTask.getDestinationDir(), prepareSandboxTask.getPluginName()) : null
	}

	@Lazy
	private transient PluginDependencyImpl pluginDependency = {
		if (pluginDirectory.exists()) {
			def creationResult = IdePluginManager.createManager().createPlugin(pluginDirectory.toPath())
			if (creationResult instanceof PluginCreationSuccess) {
				def intellijPlugin = creationResult.getPlugin()
				if (intellijPlugin instanceof IdePlugin) {
					def pluginDependency = new PluginDependencyImpl(intellijPlugin.pluginId, intellijPlugin.pluginVersion, pluginDirectory)
					pluginDependency.sinceBuild = intellijPlugin.getSinceBuild()?.asStringWithoutProductCode()
					pluginDependency.untilBuild = intellijPlugin.getUntilBuild()?.asStringWithoutProductCode()
					return pluginDependency
				}
			}
			Utils.error(project, "Cannot use $pluginDirectory as a plugin dependency. " + creationResult)
		}
		return null
	}()

	override val id: String
		get() = project.evaluate(null) { IntelliJ.pluginName.getOrElse("<unknown plugin id>") }

	override val version: String
		get() = project.evaluate(null) { Keys.projectVersion.getOrElse("<unknown plugin version>") }

	override val channel: String?
		get() = pluginDependency?.channel

	override val artifact: Path
		get() = this.pluginDirectory

	override val jarFiles: Collection<Path>
		get() = if (pluginDependency != null) pluginDependency.jarFiles else emptyList()

	override val classesDirectory: Path?
		get() = pluginDependency?.classesDirectory
	override val metaInfDirectory: Path?
		get() = pluginDependency?.metaInfDirectory
	override val sourcesDirectory: Path?
		get() = pluginDependency?.sourcesDirectory

	override val isBuiltin: Boolean
		get() = false

	override val isMaven: Boolean
		get() = false

	override fun isCompatible(ideVersion: IdeVersion): Boolean = true

	override val notation: PluginDependencyNotation
		get() = PluginDependencyNotation.Project(ProjectDependency(project, false))

	// TODO(jp): Regenerate after compile error free
	override fun toString(): String {
		return "PluginProjectDependency(project=$project)"
	}
}