package wemiplugin.intellij.dependency

import wemi.dependency.DependencyId
import wemi.dependency.ProjectDependency
import java.nio.file.Path

/**
 *
 */
sealed class PluginDependencyNotation {// TODO(jp): Rename to IntelliJPluginDependency
	/** Dependency on a bundled plugin */
	class Bundled(val name: String):PluginDependencyNotation() {
		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is Bundled) return false

			if (name != other.name) return false

			return true
		}

		override fun hashCode(): Int {
			return name.hashCode()
		}
	}
	/** Dependency on a plugin from the Jetbrains Plugin Repository */
	class External(val pluginId: String, val version: String, val channel: String? = null):PluginDependencyNotation() {
		fun toDependency(): DependencyId = DependencyId((if (channel != null) "$channel." else "") + "com.jetbrains.plugins", pluginId, version)
		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is External) return false

			if (pluginId != other.pluginId) return false
			if (version != other.version) return false
			if (channel != other.channel) return false

			return true
		}

		override fun hashCode(): Int {
			var result = pluginId.hashCode()
			result = 31 * result + version.hashCode()
			result = 31 * result + (channel?.hashCode() ?: 0)
			return result
		}


	}
	/** Locally downloaded plugin */
	class Local(val zipPath: Path):PluginDependencyNotation() {
		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is Local) return false

			if (zipPath != other.zipPath) return false

			return true
		}

		override fun hashCode(): Int {
			return zipPath.hashCode()
		}
	}
	/** A dependency on a local project which is also an IntelliJ plugin */
	class Project(val projectDependency: ProjectDependency):PluginDependencyNotation() {
		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is Project) return false

			if (projectDependency != other.projectDependency) return false

			return true
		}

		override fun hashCode(): Int {
			return projectDependency.hashCode()
		}
	}
}