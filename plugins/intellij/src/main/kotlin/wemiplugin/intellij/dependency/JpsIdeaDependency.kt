package wemiplugin.intellij.dependency

import wemi.util.name
import java.nio.file.Path

/**
 *
 */
class JpsIdeaDependency(
		version: String,
		buildNumber: String,
		classes: Path,
		sources: Path?,
		withKotlin: Boolean)
	: IdeaDependency("ideaJPS", version, buildNumber, classes, sources, withKotlin, BuiltinPluginsRegistry(classes), emptyList()) {

	override fun collectJarFiles(): Collection<Path> {
		return super.collectJarFiles().filter { ALLOWED_JAR_NAMES.contains(it.name) }
	}

	companion object {
		val ALLOWED_JAR_NAMES = setOf("jps-builders.jar", "jps-model.jar", "util.jar")
	}
}