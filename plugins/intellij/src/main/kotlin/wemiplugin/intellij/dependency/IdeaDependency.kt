package wemiplugin.intellij.dependency

import wemi.util.div
import wemi.util.isDirectory
import java.nio.file.Path

/**
 *
 */
// TODO(jp): implements Serializable
open class IdeaDependency(
		val name:String,
		val version:String,
		val buildNumber:String,
		val classes: Path,
		val sources:Path?,
		val withKotlin:Boolean,
		@Transient
		val pluginsRegistry : BuiltinPluginsRegistry,
		val extraDependencies:Collection<IdeaExtraDependency>
) {

	val jarFiles:Collection<Path> = collectJarFiles()

	// TODO(jp): ???
	open val ivyRepositoryDirectory:Path?
		get() = classes

	val fqn:String
		get() {
			val fqn = StringBuilder()
			fqn.append(name).append('-').append(version)
			if (withKotlin) {
				fqn.append("-withKotlin")
			}
			if (sources != null) {
				fqn.append("-withSources")
			}
			fqn.append("-withoutAnnotations")
			return fqn.toString()
		}

	protected open fun collectJarFiles():Collection<Path> {
		if (classes.isDirectory()) {
			val lib = classes / "lib"
			if (lib.isDirectory()) {
				return Utils.collectJars(lib, { file ->
					return (withKotlin || !IdeaDependencyManager.isKotlinRuntime(file.name - '.jar')) &&
							file.name != 'junit.jar' &&
							file.name != 'annotations.jar'
				}).sort()
			}
		}
		return emptySet()
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is IdeaDependency) return false

		if (name != other.name) return false
		if (version != other.version) return false
		if (buildNumber != other.buildNumber) return false
		if (classes != other.classes) return false
		if (sources != other.sources) return false
		if (withKotlin != other.withKotlin) return false
		if (pluginsRegistry != other.pluginsRegistry) return false
		if (extraDependencies != other.extraDependencies) return false
		if (jarFiles != other.jarFiles) return false

		return true
	}

	override fun hashCode(): Int {
		var result = name.hashCode()
		result = 31 * result + version.hashCode()
		result = 31 * result + buildNumber.hashCode()
		result = 31 * result + classes.hashCode()
		result = 31 * result + (sources?.hashCode() ?: 0)
		result = 31 * result + withKotlin.hashCode()
		result = 31 * result + pluginsRegistry.hashCode()
		result = 31 * result + extraDependencies.hashCode()
		result = 31 * result + jarFiles.hashCode()
		return result
	}

	override fun toString(): String {
		return "IdeaDependency(name='$name', version='$version', buildNumber='$buildNumber', classes=$classes, sources=$sources, withKotlin=$withKotlin, pluginsRegistry=$pluginsRegistry, extraDependencies=$extraDependencies, jarFiles=$jarFiles)"
	}
}