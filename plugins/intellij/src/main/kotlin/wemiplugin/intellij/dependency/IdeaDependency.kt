package wemiplugin.intellij.dependency

import wemi.util.div
import wemi.util.isDirectory
import wemi.util.name
import wemi.util.pathWithoutExtension
import wemiplugin.intellij.isKotlinRuntime
import wemiplugin.intellij.utils.Utils
import java.nio.file.Path
import java.util.stream.Collectors

/**
 *
 */
// TODO(jp): implements Serializable
open class IdeaDependency(
		val name:String,
		val version:String,
		val buildNumber:String,
		val classes: Path,
		val sources:List<Path>,
		val withKotlin:Boolean,
		@Transient
		val pluginsRegistry : BuiltinPluginsRegistry,
		val extraDependencies:Collection<IdeaExtraDependency>
) {

	val jarFiles:Collection<Path> = collectJarFiles()

	// TODO(jp): ???
	open val ivyRepositoryDirectory:Path?
		get() = classes

	protected open fun collectJarFiles():Collection<Path> {
		val lib = classes / "lib"
		if (lib.isDirectory()) {
			return Utils.collectJars(lib).filter {
				val name = it.name
				(withKotlin || !isKotlinRuntime(name.pathWithoutExtension())) &&
						name != "junit.jar" && name != "annotations.jar"
			}.sorted().collect(Collectors.toList())
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
		result = 31 * result + sources.hashCode()
		result = 31 * result + withKotlin.hashCode()
		result = 31 * result + pluginsRegistry.hashCode()
		result = 31 * result + extraDependencies.hashCode()
		result = 31 * result + jarFiles.hashCode()
		return result
	}

	override fun toString(): String {
		return "IdeaDependency(name='$name', version='$version', buildNumber='$buildNumber', classes=$classes, sources=$sources)"
	}
}


/**
 *
 */
class JpsIdeaDependency(
		version: String,
		buildNumber: String,
		classes: Path,
		sources: List<Path>,
		withKotlin: Boolean)
	: IdeaDependency("ideaJPS", version, buildNumber, classes, sources, withKotlin, BuiltinPluginsRegistry(classes), emptyList()) {

	override fun collectJarFiles(): Collection<Path> {
		return super.collectJarFiles().filter { ALLOWED_JAR_NAMES.contains(it.name) }
	}

	companion object {
		val ALLOWED_JAR_NAMES = setOf("jps-builders.jar", "jps-model.jar", "util.jar")
	}
}


/**
 *
 */
class LocalIdeaDependency(
		name: String, version: String, buildNumber: String,
		classes: Path, sources: List<Path>,
		withKotlin: Boolean,
		pluginsRegistry: BuiltinPluginsRegistry,
		extraDependencies: Collection<IdeaExtraDependency>)
	: IdeaDependency(name, version, buildNumber, classes, sources, withKotlin, pluginsRegistry, extraDependencies) {

	override val ivyRepositoryDirectory: Path?
		get() = if (version.endsWith(".SNAPSHOT")) null else super.ivyRepositoryDirectory
}