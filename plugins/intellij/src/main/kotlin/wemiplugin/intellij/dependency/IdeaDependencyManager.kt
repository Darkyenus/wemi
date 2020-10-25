package wemiplugin.intellij.dependency

import org.slf4j.LoggerFactory
import wemi.Configurations
import wemi.Project
import wemi.WemiException
import wemi.dependency.internal.OS_FAMILY
import wemi.dependency.internal.OS_FAMILY_WINDOWS
import wemi.util.div
import wemi.util.executable
import wemi.util.exists
import wemi.util.isDirectory
import wemiplugin.intellij.utils.Utils
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 *
 */
class IdeaDependencyManager(val repoUrl:String) {


	fun resolveRemote(project: Project, version:String, type:String, sources_:Boolean, extraDependencies:Array<String>):IdeaDependency {
		val releaseType = Utils.releaseType(version)
		LOG.debug("Adding IDE repository: {}/{}", repoUrl, releaseType)
		project.repositories.maven { it.url = "${repoUrl}/$releaseType" }

		LOG.debug("Adding IDE dependency")
		var dependencyGroup = "com.jetbrains.intellij.idea"
		var dependencyName = "ideaIC"
		var sources = sources_
		if (type == "IU") {
			dependencyName = "ideaIU"
		} else if (type == "CL") {
			dependencyGroup = "com.jetbrains.intellij.clion"
			dependencyName = "clion"
		} else if (type == "PY" || type == "PC") {
			dependencyGroup = "com.jetbrains.intellij.pycharm"
			dependencyName = "pycharm$type"
		} else if (type == "RD") {
			dependencyGroup = "com.jetbrains.intellij.rider"
			dependencyName = "riderRD"
			if (sources && releaseType == "snapshots") {
				LOG.warn("IDE sources are not available for Rider SNAPSHOTS")
				sources = false
			}
		}
		val dependency = project.dependencies.create("$dependencyGroup:$dependencyName:$version")

		val configuration = project.configurations.detachedConfiguration(dependency)

		val classesDirectory = extractClassesFromRemoteDependency(project, configuration, type, version)
		LOG.info("IDE dependency cache directory: {}", classesDirectory)
		val buildNumber = Utils.ideBuildNumber(classesDirectory)
		val sourcesDirectory = if (sources) resolveSources(project, version) else null
		val resolvedExtraDependencies = resolveExtraDependencies(project, version, extraDependencies)
		return createDependency(dependencyName, type, version, buildNumber, classesDirectory, sourcesDirectory, project, resolvedExtraDependencies)
	}

	fun resolveLocal(project:Project, localPath: Path, localPathSources:Path?):IdeaDependency {
		LOG.debug("Adding local IDE dependency")
		val ideaDir = Utils.ideaDir(localPath)
		if (!ideaDir.exists() || !ideaDir.isDirectory()) {
			throw WemiException("Specified localPath '$localPath' doesn't exist or is not a directory", false)
		}
		val buildNumber = Utils.ideBuildNumber(ideaDir)
		return createDependency("ideaLocal", null, buildNumber, buildNumber, ideaDir, localPathSources, project, emptyList())
	}

	companion object {
		private val LOG = LoggerFactory.getLogger(IdeaDependencyManager::class.java)

		private val mainDependencies = arrayOf("ideaIC", "ideaIU", "riderRD", "riderRS")

		fun register(project:Project, dependency:IdeaDependency, dependencies:DependencySet) {
			val ivyFile = getOrCreateIvyXml(dependency)
			val ivyFileSuffix = ivyFile.name.substring("${dependency.name}-${dependency.version}".length()) - ".xml"
			project.repositories.ivy { repo ->
				repo.url = dependency.classes
				repo.ivyPattern("$ivyFile.parent/[module]-[revision]$ivyFileSuffix.[ext]") // ivy xml
				repo.artifactPattern("$dependency.classes.path/[artifact].[ext]") // idea libs
				if (dependency.sources) {
					repo.artifactPattern("$dependency.sources.parent/[artifact]-[revision]-[classifier].[ext]")
				}
			}
			dependencies.add(project.dependencies.create([
				group: "com.jetbrains", name: dependency.name, version: dependency.version, configuration: "compile"
			]))
		}

		fun isKotlinRuntime(name:String):Boolean {
			return "kotlin-runtime" == name || "kotlin-reflect" == name || name.startsWith("kotlin-stdlib")
		}

		private fun createDependency(name:String, type:String?, version:String, buildNumber:String,
		                             classesDirectory:Path, sourcesDirectory:Path, project:Project,
		                             extraDependencies:Collection<IdeaExtraDependency>):IdeaDependency {
			if (type == "JPS") {
				return JpsIdeaDependency(version, buildNumber, classesDirectory, sourcesDirectory, !hasKotlinDependency(project))
			} else if (type == null) {
				val pluginsRegistry = BuiltinPluginsRegistry.fromDirectory(classesDirectory / "plugins")
				return LocalIdeaDependency(name, version, buildNumber, classesDirectory, sourcesDirectory, !hasKotlinDependency(project), pluginsRegistry, extraDependencies)
			}
			val pluginsRegistry = BuiltinPluginsRegistry.fromDirectory(classesDirectory / "plugins")
			return IdeaDependency(name, version, buildNumber, classesDirectory, sourcesDirectory, !hasKotlinDependency(project), pluginsRegistry, extraDependencies)
		}

		private fun resolveSources(project:Project, version:String): Path? {
			LOG.info("Adding IDE sources repository")
			try {
				val dependency = project.dependencies.create("com.jetbrains.intellij.idea:ideaIC:$version:sources@jar")
				val sourcesConfiguration = project.configurations.detachedConfiguration(dependency)
				val sourcesFiles = sourcesConfiguration.files
				if (sourcesFiles.size() == 1) {
					val sourcesDirectory = sourcesFiles.first()
					LOG.debug("IDE sources jar: {}", sourcesDirectory)
					return sourcesDirectory
				} else {
					LOG.warn("Cannot attach IDE sources. Found files: {}", sourcesFiles)
				}
			} catch (e:ResolveException) {
				LOG.warn("Cannot resolve IDE sources dependency", e)
			}
			return null
		}

		private fun extractClassesFromRemoteDependency(project:Project, configuration:Configuration, type:String, version:String):Path {
			val zipFile = configuration.singleFile
			LOG.debug("IDE zip: {}", zipFile.path)
			return unzipDependencyFile(getZipCacheDirectory(zipFile, project, type), project, zipFile, type, version.endsWith("-SNAPSHOT"))
		}

		private fun getZipCacheDirectory(zipFile:Path, project:Project, type:String):Path {
			val intellijExtension = project.extensions.findByType(IntelliJPluginExtension::class.java)
			if (intellijExtension && intellijExtension.ideaDependencyCachePath) {
				val customCacheParent = Paths.get(intellijExtension.ideaDependencyCachePath)
				if (customCacheParent.exists()) {
					return customCacheParent.toAbsolutePath()
				}
			} else if (type == "RD" && OS_FAMILY == OS_FAMILY_WINDOWS) {
				return project.buildDir
			}
			return zipFile.parent
		}

		private fun resolveExtraDependencies(project:Project, version:String, extraDependencies:Array<String>):Collection<IdeaExtraDependency> {
			if (extraDependencies.size == 0) {
				return emptyList()
			}
			LOG.info("Configuring IDE extra dependencies {}", extraDependencies)
			val mainInExtraDeps = extraDependencies.filter { dep -> mainDependencies.any { it == dep } }
			if (mainInExtraDeps.isNotEmpty()) {
				throw WemiException("The items $mainInExtraDeps cannot be used as extra dependencies", false)
			}
			val resolvedExtraDependencies = ArrayList<IdeaExtraDependency>()
			for (name in extraDependencies) {
				val dependencyFile = resolveExtraDependency(project, version, name)
				val extraDependency = IdeaExtraDependency(name, dependencyFile)
				LOG.debug("IDE extra dependency $name in $dependencyFile files: ${extraDependency.jarFiles}")
				resolvedExtraDependencies.add(extraDependency)
			}
			return resolvedExtraDependencies
		}

		private fun resolveExtraDependency(project:Project, version:String, name:String) :Path? {
			try {
				val dependency = project.dependencies.create("com.jetbrains.intellij.idea:$name:$version")
				val extraDepConfiguration = project.configurations.detachedConfiguration(dependency)
				val files = extraDepConfiguration.files
				if (files.size() == 1) {
					val depFile = files.first()
					if (depFile.name.endsWith(".zip")) {
						val cacheDirectory = getZipCacheDirectory(depFile, project, "IC")
						LOG.debug("IDE extra dependency {}: {}", name, cacheDirectory)
						return unzipDependencyFile(cacheDirectory, project, depFile, "IC", version.endsWith("-SNAPSHOT"))
					} else {
						LOG.debug("IDE extra dependency {}: {}", name, depFile)
						return depFile
					}
				} else {
					LOG.warn("Cannot attach IDE extra dependency {}. Found files: {}", name, files)
				}
			} catch (e:ResolveException) {
				LOG.warn("Cannot resolve IDE extra dependency {}", name, e)
			}
			return null
		}

		private fun unzipDependencyFile(cacheDirectory:Path, project:Project, zipFile:Path, type:String, checkVersionChange:Boolean):Path {
			return Utils.unzip(zipFile, cacheDirectory, project, {
				markerFile -> isCacheUpToDate(zipFile, markerFile, checkVersionChange)
			}, { unzippedDirectory, markerFile ->
				resetExecutablePermissions(project, unzippedDirectory, type)
				storeCache(unzippedDirectory, markerFile)
			})
		}

		private fun isCacheUpToDate(zipFile:Path, markerFile:Path, checkVersion:Boolean):Boolean {
			if (!checkVersion) {
				return markerFile.exists()
			}
			if (!markerFile.exists()) {
				return false
			}
			ZipFile(zipFile).use { zip ->
				val entry = zip.getEntry("build.txt")
				if (entry != null && zip.getInputStream(entry).text.trim() != markerFile.text.trim()) {
					return false
				}
			}

			return true
		}

		private fun storeCache(directoryToCache:Path, markerFile:Path) {
			val buildTxt = directoryToCache / "build.txt"
			if (buildTxt.exists()) {
				Files.write(markerFile, String(Files.readAllBytes(buildTxt)).trim().toByteArray())
			}
		}

		private fun resetExecutablePermissions(project:Project, cacheDirectory:Path, type:String) {
			if (type == "RD") {
				if (OS_FAMILY != OS_FAMILY_WINDOWS) {
					setExecutable(project, cacheDirectory, "lib/ReSharperHost/dupfinder.sh")
					setExecutable(project, cacheDirectory, "lib/ReSharperHost/inspectcode.sh")
					setExecutable(project, cacheDirectory, "lib/ReSharperHost/JetBrains.ReSharper.Host.sh")
					setExecutable(project, cacheDirectory, "lib/ReSharperHost/runtime.sh")
					setExecutable(project, cacheDirectory, "lib/ReSharperHost/macos-x64/mono/bin/env-wrapper")
					setExecutable(project, cacheDirectory, "lib/ReSharperHost/macos-x64/mono/bin/mono-sgen")
					setExecutable(project, cacheDirectory, "lib/ReSharperHost/macos-x64/mono/bin/mono-sgen-gdb.py")
					setExecutable(project, cacheDirectory, "lib/ReSharperHost/linux-x64/mono/bin/mono-sgen")
					setExecutable(project, cacheDirectory, "lib/ReSharperHost/linux-x64/mono/bin/mono-sgen-gdb.py")
				}
			}
		}

		// TODO(jp): Make private or move
		fun setExecutable(project:Project?, parent:Path, child:String) {
			val file = parent / child
			LOG.debug("Resetting executable permissions for {}", file)
			file.executable = true
		}

		private fun getOrCreateIvyXml(dependency:IdeaDependency):Path {
			val directory = dependency.ivyRepositoryDirectory
			val ivyFile = if (directory != null) directory / "${dependency.fqn}.xml" else Files.createTempFile(dependency.fqn, ".xml")
			if (directory == null || !ivyFile.exists()) {
				val identity = DefaultIvyPublicationIdentity("com.jetbrains", dependency.name, dependency.version)
				val generator = IntelliJIvyDescriptorFileGenerator(identity)
				generator.addConfiguration(DefaultIvyConfiguration("default"))
				generator.addConfiguration(DefaultIvyConfiguration("compile"))
				generator.addConfiguration(DefaultIvyConfiguration("sources"))
				for (it in dependency.jarFiles) {
					generator.addArtifact(Utils.createJarDependency(it, "compile", dependency.classes))
				}
				if (dependency.sources != null) {
					val artifact = IntellijIvyArtifact(dependency.sources, "ideaIC", "jar", "sources", "sources")
					artifact.conf = "sources"
					generator.addArtifact(artifact)
				}
				generator.writeTo(ivyFile)
			}
			return ivyFile
		}

		private fun hasKotlinDependency(project:Project):Boolean {
			return project.evaluate(null, Configurations.running) {
				Keys.libraryDependencies.get().any { it.dependencyId.group == "org.jetbrains.kotlin" && isKotlinRuntime(it.dependencyId.name) }
			}
		}
	}
}