package wemiplugin.intellij.dependency

import java.nio.file.Path

/**
 *
 */
class LocalIdeaDependency(
		name: String,
		version: String,
		buildNumber: String,
		classes: Path,
		sources: Path?,
		withKotlin: Boolean,
		pluginsRegistry: BuiltinPluginsRegistry,
		extraDependencies: Collection<IdeaExtraDependency>)
	: IdeaDependency(name, version, buildNumber, classes, sources, withKotlin, pluginsRegistry, extraDependencies) {

	override val ivyRepositoryDirectory: Path?
		get() = if (version.endsWith(".SNAPSHOT")) null else super.ivyRepositoryDirectory
}