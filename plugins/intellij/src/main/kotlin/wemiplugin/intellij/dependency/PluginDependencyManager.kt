package wemiplugin.intellij.dependency

import wemi.Project
import wemi.WemiException
import wemi.boot.WemiCacheFolder
import wemi.util.div
import wemi.util.exists
import wemi.util.isDirectory
import wemi.util.isHidden
import wemiplugin.intellij.IntelliJPluginRepository
import java.nio.file.Path
import java.util.stream.Collectors

/**
 *
 */
class PluginDependencyManager {

	private val cacheDirectoryPath:Path
	private val mavenCacheDirectoryPath: Path
	private val ideaDependency:IdeaDependency?
	private val pluginRepositories:List<PluginsRepository>

	private var pluginSources = HashSet<String>()
	private var ivyArtifactRepository:IvyArtifactRepository? = null

	constructor(ideaDependency: IdeaDependency?, pluginRepositories: List<PluginsRepository>) {
		this.ideaDependency = ideaDependency
		this.pluginRepositories = pluginRepositories
		// old todo: a better way to define cache directory
		mavenCacheDirectoryPath = WemiCacheFolder / "-intellij-plugin-dependency"
		cacheDirectoryPath = mavenCacheDirectoryPath / "com.jetbrains.intellij.idea"
	}

	fun resolve(project: Project, dependency:PluginDependencyNotation) : PluginDependency {
		when (dependency) {
			is PluginDependencyNotation.Bundled -> {
				if (ideaDependency != null) {
					Utils.info(project, "Looking for builtin $dependency.id in $ideaDependency.classes.absolutePath")
					def pluginDirectory = ideaDependency.pluginsRegistry.findPlugin(dependency.id)
					if (pluginDirectory != null) {
						def builtinPluginVersion = "$ideaDependency.name-$ideaDependency.buildNumber${ideaDependency.sources ? '-withSources' : ''}"
						return new PluginDependencyImpl(pluginDirectory.name, builtinPluginVersion, pluginDirectory, true)
					}
				}
				throw WemiException("Cannot find builtin plugin ${dependency.id} for IDE: ${ideaDependency.classes.absolutePath}", false)
			}
			is PluginDependencyNotation.External -> {
				for (repo in pluginRepositories) {
					val pluginFile = repo.resolve(dependency)
					if (pluginFile != null) {
						if (Utils.isZipFile(pluginFile)) {
							return zippedPluginDependency(project, pluginFile, dependency)
						} else if (Utils.isJarFile(pluginFile)) {
							return externalPluginDependency(project, pluginFile, dependency.channel, true)
						}
						throw WemiException("Invalid type of downloaded plugin: $pluginFile.name", false)
					}
				}
			}
			is PluginDependencyNotation.Local -> {
				return externalPluginDependency(project, new File(dependency.id), null)
			}
			is PluginDependencyNotation.Project -> TODO()
		}

		throw WemiException("Cannot resolve plugin $dependency.id version $dependency.version ${dependency.channel != null ? "from channel $dependency.channel" : ""}", false)
	}

	fun register(project:Project, plugin:PluginDependency, dependencies:DependencySet) {
		if (plugin.maven && Utils.isJarFile(plugin.artifact)) {
			dependencies.add(plugin.notation.toDependency(project))
			return
		}
		registerRepoIfNeeded(project, plugin)
		generateIvyFile(plugin)
		dependencies.add(project.dependencies.create([
			group: groupId(plugin.channel), name: plugin.id, version: plugin.version, configuration: 'compile'
		]))
	}

	private fun zippedPluginDependency(project:Project, pluginFile:Path, dependency:PluginDependencyNotation):PluginDependency {
		def pluginDir = findSingleDirectory(Utils.unzip(
				pluginFile, new File(cacheDirectoryPath, groupId(dependency.channel)),
		project, null, null,
		"$dependency.id-$dependency.version"))
		return externalPluginDependency(project, pluginDir, dependency.channel, true)
	}

	private fun registerRepoIfNeeded(project:Project, plugin:PluginDependency) {
		if (ivyArtifactRepository == null) {
			ivyArtifactRepository = project.repositories.ivy { IvyArtifactRepository repo ->
				repo.ivyPattern("$cacheDirectoryPath/[organisation]/[module]-[revision].[ext]") // ivy xml
				repo.artifactPattern("$ideaDependency.classes/plugins/[module]/[artifact](.[ext])") // builtin plugins
				repo.artifactPattern("$cacheDirectoryPath(/[classifier])/[module]-[revision]/[artifact](.[ext])") // external zip plugins
				if (ideaDependency.sources) {
					repo.artifactPattern("$ideaDependency.sources.parent/[artifact]-$ideaDependency.version(-[classifier]).[ext]")
				}
			}
		}
		if (!plugin.builtin && !plugin.maven) {
			def artifactParent = plugin.artifact.parentFile
					def pluginSource = artifactParent.absolutePath
					if (artifactParent?.parentFile?.absolutePath != cacheDirectoryPath && pluginSources.add(pluginSource)) {
						ivyArtifactRepository.artifactPattern("$pluginSource/[artifact](.[ext])")  // local plugins
					}
		}
	}

	private fun generateIvyFile(plugin:PluginDependency) {
		val baseDir = if (plugin.isBuiltin) plugin.artifact else plugin.artifact.parent
		val pluginFqn = "${plugin.id}-${plugin.version}"
		val groupId = groupId(plugin.channel)
		val ivyFile = cacheDirectoryPath / groupId / "${pluginFqn}.xml"
		if (!ivyFile.exists()) {
			val identity = DefaultIvyPublicationIdentity(groupId, plugin.id, plugin.version)
			val generator = IntelliJIvyDescriptorFileGenerator(identity)
			val configuration = DefaultIvyConfiguration("compile")
			generator.addConfiguration(configuration)
			generator.addConfiguration(DefaultIvyConfiguration("sources"))
			generator.addConfiguration(DefaultIvyConfiguration("default"))
			for (jarFile in plugin.jarFiles) {
				generator.addArtifact(Utils.createJarDependency(jarFile, configuration.name, baseDir, groupId))
			}
			if (plugin.classesDirectory != null) {
				generator.addArtifact(Utils.createDirectoryDependency(plugin.classesDirectory, configuration.name, baseDir, groupId))
			}
			if (plugin.metaInfDirectory != null) {
				generator.addArtifact(Utils.createDirectoryDependency(plugin.metaInfDirectory, configuration.name, baseDir, groupId))
			}
			if (plugin.isBuiltin && ideaDependency?.sources != null) {
				val artifact = IntellijIvyArtifact(ideaDependency.sources, "ideaIC", "jar", "sources", "sources")
				artifact.conf = "sources"
				generator.addArtifact(artifact)
			}
			generator.writeTo(ivyFile)
		}
	}

	companion object {

		private fun groupId(channel: String?): String {
			return if (channel != null) "unzipped.${channel}.com.jetbrains.plugins" else "unzipped.com.jetbrains.plugins"
		}

		private fun findSingleDirectory(dir:Path):Path {
			val files = Files.list(dir)
					.filter { it.isDirectory() && !it.isHidden() }
					.limit(2)
					.collect(Collectors.toList())
			return files.singleOrNull() ?: throw WemiException("Single directory expected in $dir", false)
		}

		private fun externalPluginDependency(project:Project, artifact:Path, channel:String?, maven:Boolean = false): PluginDependencyImpl? {
			if (!Utils.isJarFile(artifact) && !artifact.isDirectory()) {
				Utils.warn(project, "Cannot create plugin from file ($artifact): only directories or jars are supported")
			}
			val intellijPlugin = Utils.createPlugin(artifact, true, project)
			if (intellijPlugin != null) {
				val pluginDependency = PluginDependencyImpl(intellijPlugin.pluginId, intellijPlugin.pluginVersion, artifact, false, maven)
				pluginDependency.channel = channel
				pluginDependency.sinceBuild = intellijPlugin.sinceBuild?.asStringWithoutProductCode()
				pluginDependency.untilBuild = intellijPlugin.untilBuild?.asStringWithoutProductCode()
				return pluginDependency
			}
			return null
		}
	}

}