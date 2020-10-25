package wemiplugin.intellij

import org.slf4j.LoggerFactory
import wemi.WemiException
import wemi.archetype
import wemi.collections.toMutable
import wemi.configuration
import wemi.dependency.Dependency
import wemi.dependency.DependencyId
import wemi.dependency.Repository
import wemi.dependency.ScopeProvided
import wemi.dependency.internal.OS_FAMILY
import wemi.dependency.internal.OS_FAMILY_MAC
import wemi.dependency.internal.OS_FAMILY_UNIX
import wemi.key
import wemi.run.javaExecutable
import wemi.util.LocatedPath
import wemi.util.Version
import wemi.util.absolutePath
import wemi.util.div
import wemi.util.exists
import wemi.util.jdkToolsJar
import wemiplugin.intellij.IntelliJ.PLUGIN_PATH
import wemiplugin.intellij.dependency.IdeaDependency
import wemiplugin.intellij.dependency.IdeaDependencyManager
import wemiplugin.intellij.dependency.PluginDependency
import wemiplugin.intellij.dependency.PluginDependencyManager
import wemiplugin.intellij.dependency.PluginDependencyNotation
import wemiplugin.intellij.dependency.PluginProjectDependency
import wemiplugin.intellij.dependency.PluginsRepository
import wemiplugin.intellij.tasks.DownloadRobotServerPluginTask.intellijRobotServerPlugin
import wemiplugin.intellij.tasks.DownloadRobotServerPluginTask.setupDownloadRobotServerPluginTask
import wemiplugin.intellij.tasks.PatchPluginXmlTask.setupPatchPluginXmlTask
import wemiplugin.intellij.tasks.PrepareSandboxTask.prepareSandboxTask
import wemiplugin.intellij.tasks.VerifyPluginTask.setupVerifyPluginTask
import wemiplugin.intellij.utils.Utils
import wemiplugin.intellij.utils.Utils.getPluginIds
import wemiplugin.intellij.utils.Utils.ideSdkDirectory
import wemiplugin.intellij.utils.Utils.sourcePluginXmlFiles
import wemiplugin.intellij.utils.getFirstElement
import wemiplugin.intellij.utils.namedElements
import wemiplugin.intellij.utils.parseXml
import java.io.File
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * All related keys.
 * Formerly IntelliJPluginExtension.
 */
object IntelliJ {

	// TODO(jp): Remove unused elements
	const val GROUP_NAME = "intellij"
	const val EXTENSION_NAME = "intellij"
	const val DEFAULT_SANDBOX = "idea-sandbox"
	const val PATCH_PLUGIN_XML_TASK_NAME = "patchPluginXml"
	const val PLUGIN_XML_DIR_NAME = "patchedPluginXmlFiles"
	const val PREPARE_SANDBOX_TASK_NAME = "prepareSandbox"
	const val PREPARE_TESTING_SANDBOX_TASK_NAME = "prepareTestingSandbox"
	const val PREPARE_UI_TESTING_SANDBOX_TASK_NAME = "prepareUiTestingSandbox"
	const val DOWNLOAD_ROBOT_SERVER_PLUGIN_TASK_NAME = "downloadRobotServerPlugin"
	const val VERIFY_PLUGIN_TASK_NAME = "verifyPlugin"
	const val RUN_IDE_TASK_NAME = "runIde"
	const val RUN_IDE_FOR_UI_TESTS_TASK_NAME = "runIdeForUiTests"
	const val BUILD_SEARCHABLE_OPTIONS_TASK_NAME = "buildSearchableOptions"
	const val SEARCHABLE_OPTIONS_DIR_NAME = "searchableOptions"
	const val JAR_SEARCHABLE_OPTIONS_TASK_NAME = "jarSearchableOptions"
	const val BUILD_PLUGIN_TASK_NAME = "buildPlugin"
	const val PUBLISH_PLUGIN_TASK_NAME = "publishPlugin"

	const val IDEA_CONFIGURATION_NAME = "idea"
	const val IDEA_PLUGINS_CONFIGURATION_NAME = "ideaPlugins"

	const val DEFAULT_IDEA_VERSION = "LATEST-EAP-SNAPSHOT"
	const val DEFAULT_INTELLIJ_REPO = "https://cache-redirector.jetbrains.com/www.jetbrains.com/intellij-repository"
	const val DEFAULT_JBR_REPO = "https://cache-redirector.jetbrains.com/jetbrains.bintray.com/intellij-jdk"
	const val DEFAULT_NEW_JBR_REPO = "https://cache-redirector.jetbrains.com/jetbrains.bintray.com/intellij-jbr"
	const val DEFAULT_INTELLIJ_PLUGINS_REPO = "https://cache-redirector.jetbrains.com/plugins.jetbrains.com/maven"
	const val PLUGIN_PATH = "plugin.path"

	internal val PREFIXES = mapOf(
			"IU" to null,
			"IC" to "Idea",
			"RM" to "Ruby",
			"PY" to "Python",
			"PC" to "PyCharmCore",
			"PE" to "PyCharmEdu",
			"PS" to "PhpStorm",
			"WS" to "WebStorm",
			"OC" to "AppCode",
			"CL" to "CLion",
			"DB" to "DataGrip",
			"AI" to "AndroidStudio",
			"GO" to "GoLand",
			"RD" to "Rider",
			"RS" to "Rider")

	val pluginDependencies by key<List<PluginDependencyNotation>>("Dependencies on another plugins")

	val pluginRepositories by key<List<PluginsRepository>>("Repositories in which plugin dependencies can be found",
			listOf(PluginsRepository.Maven(Repository("intellij-plugins-repo", DEFAULT_INTELLIJ_PLUGINS_REPO))))

	val intellijResolvedPluginDependencies by key<List<PluginDependency>>("")//TODO

	// TODO(jp): Convert to Either
	val localPath by key<Path>("The path to locally installed IDE distribution that should be used as a dependency")

	// TODO(jp): Required?
	val localSourcesPath by key<Path>("The path to local archive with IDE sources")

	val intellijVersion by key<String>("The version of the IntelliJ Platform IDE that will be used to build the plugin, see https://www.jetbrains.org/intellij/sdk/docs/basics/getting_started/plugin_compatibility.html (without type prefix, use intellijType instead)")

	val intellijType by key<String>("The type of IDE distribution (IC, IU, CL, PY, PC, RD or JPS)", "IC")

	val pluginName by key<String>("Name of the plugin")

	// TODO(jp): Remove
	val updateSinceUntilBuild by key<Boolean>("Patch plugin.xml with since and until build values inferred from IDE version.", true)

	// TODO(jp): Remove
	val sameSinceUntilBuild by key("Patch plugin.xml with an until build value that is just an \"open\" since build.", false)

	val instrumentCode by key("Instrument Java classes with nullability assertions and compile forms created by IntelliJ GUI Designer.", true)

	/**
	 * The absolute path to the locally installed JetBrains IDE, which is used for running.
	 * <p/>
	 * @deprecated use `ideDirectory` option in `runIde` and `buildSearchableOptions` task instead.
	 */
	val alternativeIdePath:Path? = null

	val sandboxDirectory by key<SandboxDirectory>("The path of sandbox directory that is used for running IDE with developing plugin")
	class SandboxDirectory(val base:Path, val config:Path, val plugins:Path, val system:Path)

	val intellijRepo by key<String>("Url of repository for downloading IDE distributions.", DEFAULT_INTELLIJ_REPO)

	val intellijJreRepo by key<String>("Url of repository for downloading JetBrains Java Runtime")

	// TODO(jp): intellijDependencyPath
	val ideaDependencyPath by key<Path>("The absolute path to the local directory that should be used for storing IDE distributions")

	val downloadSources by key<Boolean>("Should download IntelliJ dependency sources?", false)

	/**
	 * Turning it off disables configuring dependencies to intellij sdk jars automatically,
	 * instead the intellij, intellijPlugin and intellijPlugins functions could be used for an explicit configuration
	 */
	val configureDefaultDependencies = true

	/**
	 * configure extra dependency artifacts from intellij repo
	 *  the dependencies on them could be configured only explicitly using intellijExtra function in the dependencies block
	 */
	val extraDependencies by key<Array<String>>("")

	val ideaDependency by key<IdeaDependency>("IDE dependency to use for compilation and running")

	val jbrVersion by key<String>("")
}

private val LOG = LoggerFactory.getLogger("IntelliJPlugin")

val JetBrainsAnnotations = Dependency(DependencyId("org.jetbrains", "annotations", "20.1.0"), scope = ScopeProvided, optional = true)

/** A layer over [wemi.Archetypes.JVMBase] which turns the project into an IntelliJ platform plugin. */
val IntelliJPluginLayer by archetype {

	IntelliJ.pluginName set { Keys.projectName.get() }

	IntelliJ.sandboxDirectory set {
		val dir = Keys.cacheDirectory.get() / "idea-sandbox"
		prepareSandboxTask(dir, "")
	}

	Keys.runSystemProperties modify {
		val systemProperties = it.toMutableMap()
		val ideDirectory = ideSdkDirectory()
		val sandboxDir = IntelliJ.sandboxDirectory.get()
		val ideBuildNumber = Utils.ideBuildNumber(ideDirectory)

		val configDirectory = sandboxDir.config
		val pluginsDirectory = sandboxDir.plugins
		val systemDirectory = sandboxDir.system
		val requiredPluginIds = getPluginIds()
		/*
		 * Enables auto-reload of dynamic plugins. Dynamic plugins will be reloaded automatically when their JARs are
		 * modified. This allows a much faster development cycle by avoiding a full restart of the development instance
		 * after code changes. Enabled by default in 2020.2 and higher.
		 */
		val autoReloadPlugins:Boolean = run {
			Version(ideBuildNumber.takeWhile { c -> c != '-' }) >= Version("202.0")
		}

		systemProperties.putAll(Utils.getIdeaSystemProperties(configDirectory, systemDirectory, pluginsDirectory, requiredPluginIds))
		if (OS_FAMILY == OS_FAMILY_MAC) {
			systemProperties.putIfAbsent("idea.smooth.progress", "false")
			systemProperties.putIfAbsent("apple.laf.useScreenMenuBar", "true")
			systemProperties.putIfAbsent("apple.awt.fileDialogForDirectories", "true")
		} else if (OS_FAMILY == OS_FAMILY_UNIX) {
			systemProperties.putIfAbsent("sun.awt.disablegrab", "true")
		}
		systemProperties.putIfAbsent("idea.classpath.index.enabled", "false")
		systemProperties.putIfAbsent("idea.is.internal", "true")
		systemProperties.putIfAbsent("idea.auto.reload.plugins", autoReloadPlugins.toString())

		if (!systemProperties.containsKey("idea.platform.prefix")) {
			val matcher = Utils.VERSION_PATTERN.matcher(ideBuildNumber)
			if (matcher.find()) {
				val abbreviation = matcher.group(1)
				val prefix = IntelliJ.PREFIXES[abbreviation]
				if (prefix != null && prefix.isNotBlank()) {
					systemProperties["idea.platform.prefix"] = prefix

					if (abbreviation == "RD") {
						// Allow debugging Rider's out of process ReSharper host
						systemProperties.putIfAbsent("rider.debug.mono.debug", "true")
						systemProperties.putIfAbsent("rider.debug.mono.allowConnect", "true")
					}
				}
			}
		}

		systemProperties
	}

	Keys.runOptions modify {
		val runOptions = it.toMutable()
		if (!runOptions.any { o -> o.startsWith("-Xmx") }) {
			runOptions.add("-Xmx512m")
		}
		if (!runOptions.any { o -> o.startsWith("-Xms") }) {
			runOptions.add("-Xms256m")
		}
		val bootJar = ideSdkDirectory() / "lib/boot.jar"
		if (bootJar.exists()) runOptions.add("-Xbootclasspath/a:${bootJar.absolutePath}")
		runOptions
	}

	Keys.javaExecutable set {
		val jbrVersion = IntelliJ.jbrVersion.getOrElse(null)
		if (jbrVersion != null) {
			val jbr = resolveJbr(jbrVersion, null)
			if (jbr != null) {
				return@set jbr.javaExecutable
			}
			LOG.warn("Cannot resolve JBR {}. Falling back to builtin JBR.", jbrVersion)
		}
		val builtinJbrVersion = Utils.getBuiltinJbrVersion(ideSdkDirectory())
		if (builtinJbrVersion != null) {
			val builtinJbr = resolveJbr(builtinJbrVersion, null)
			if (builtinJbr != null) {
				return@set builtinJbr.javaExecutable
			}
			LOG.warn("Cannot resolve builtin JBR {}. Falling back to local Java.", builtinJbrVersion)
		}

		val defaultJavaExecutable = javaExecutable(Keys.javaHome.get())

		val alternativeIdePath = IntelliJ.alternativeIdePath
		if (alternativeIdePath != null) {
			val jbrPath = javaExecutable(alternativeIdePath / if (OS_FAMILY == OS_FAMILY_MAC) "jbr/Contents/Home" else "jbr")
			if (jbrPath.exists()) {
				return@set jbrPath.toAbsolutePath()
			}
			LOG.warn("Cannot resolve JBR at {}. Falling back to {}.", jbrPath, defaultJavaExecutable)
		}

		defaultJavaExecutable
	}

	extend(Configurations.testing) {
		IntelliJ.sandboxDirectory set {
			val dir = Keys.cacheDirectory.get() / "idea-sandbox"
			prepareSandboxTask(dir, "-test")
		}

		Keys.externalClasspath modify {
			val ec = it.toMutable()
			val ideaDependency = IntelliJ.ideaDependency.get()
			ec.add(LocatedPath(ideaDependency.classes / "lib/resources.jar"))
			ec.add(LocatedPath(ideaDependency.classes / "lib/idea.jar"))
			ec
		}

		Keys.runSystemProperties modify {
			val sp = it.toMutableMap()

			val sandboxDir = IntelliJ.sandboxDirectory.get()

			// since 193 plugins from classpath are loaded before plugins from plugins directory
			// to handle this, use plugin.path property as task's the very first source of plugins
			// we cannot do this for IDEA < 193, as plugins from plugin.path can be loaded twice
			val ideVersion = IdeVersion.createIdeVersion(IntelliJ.ideaDependency.get().buildNumber)
			if (ideVersion.baselineVersion >= 193) {
				sp[PLUGIN_PATH] = Files.list(sandboxDir.plugins).collect(Collectors.toList()).joinToString(File.pathSeparator+",") { p -> p.absolutePath }
			}

			sp
		}
	}

	extend(uiTesting) {
		IntelliJ.sandboxDirectory set {
			val dir = Keys.cacheDirectory.get() / "idea-sandbox"
			prepareSandboxTask(dir, "-uiTest", intellijRobotServerPlugin.get())
		}
	}

	extend(Configurations.publishing) {
		/*
        def prepareSandboxTask = project.tasks.findByName(PREPARE_SANDBOX_TASK_NAME) as PrepareSandboxTask
        def jarSearchableOptionsTask = project.tasks.findByName(JAR_SEARCHABLE_OPTIONS_TASK_NAME) as Jar
        Zip zip = project.tasks.create(BUILD_PLUGIN_TASK_NAME, Zip).with {
            description = "Bundles the project as a distribution."
            group = GROUP_NAME
            from { "${prepareSandboxTask.getDestinationDir()}/${prepareSandboxTask.getPluginName()}" }
            into { prepareSandboxTask.getPluginName() }

            def searchableOptionsJar = VersionNumber.parse(project.gradle.gradleVersion) >= VersionNumber.parse("5.1")
                    ? jarSearchableOptionsTask.archiveFile : { jarSearchableOptionsTask.archivePath }
            from(searchableOptionsJar) { into 'lib' }
            dependsOn(JAR_SEARCHABLE_OPTIONS_TASK_NAME)
            if (VersionNumber.parse(project.gradle.gradleVersion) >= VersionNumber.parse("5.1")) {
                archiveBaseName.set(project.provider { prepareSandboxTask.getPluginName() })
            } else {
                conventionMapping('baseName', { prepareSandboxTask.getPluginName() })
            }
            it
        }
        Configuration archivesConfiguration = project.configurations.getByName(Dependency.ARCHIVES_CONFIGURATION)
        if (archivesConfiguration) {
            ArchivePublishArtifact zipArtifact = new ArchivePublishArtifact(zip)
            archivesConfiguration.getArtifacts().add(zipArtifact)
            project.getExtensions().getByType(DefaultArtifactPublicationSet.class).addCandidate(zipArtifact)
            project.getComponents().add(new IntelliJPluginLibrary())
        }
		 */
	}

	// Add a provided JetBrainsAnnotations, unless it is already there
	Keys.libraryDependencies modify { deps ->
		val alreadyHas = deps.any { it.dependencyId.group == JetBrainsAnnotations.dependencyId.group && it.dependencyId.name == JetBrainsAnnotations.dependencyId.name }
		if (alreadyHas) {
			deps
		} else {
			val mDeps = deps.toMutable()
			mDeps.add(JetBrainsAnnotations)
			mDeps
		}
	}

	extend(Configurations.retrievingSources) {
		IntelliJ.downloadSources set { true }
	}

	Keys.externalClasspath modify { cp ->
		val mcp = cp.toMutable()
		mcp.add(LocatedPath(IntelliJ.ideaDependency.get().classes))
		for (dependency in IntelliJ.intellijResolvedPluginDependencies.get()) {
			mcp.add(dependency.classesDirectory) // TODO(jp): What should be added here?
		}
		mcp
	}

	IntelliJ.ideaDependency set {
		val resolver = IdeaDependencyManager(IntelliJ.intellijRepo.get())
		val localPath = IntelliJ.localPath.getOrElse(null)
		val version = IntelliJ.intellijVersion.getOrElse(null)
		val result = if (localPath != null) {
			if (version != null) {
				// TODO(jp): Join both keys into one unambiguous key
				LOG.warn("Both {} and {} is set, second will be ignored", IntelliJ.localPath, IntelliJ.intellijVersion)
			}
			resolver.resolveLocal(null, localPath, IntelliJ.localSourcesPath.getOrElse(null))
		} else {
			resolver.resolveRemote(null, version ?: IntelliJ.DEFAULT_IDEA_VERSION, IntelliJ.intellijType.get(), IntelliJ.downloadSources.get(), IntelliJ.extraDependencies.get())
		}

		// TODO(jp): This section should not be here, probably, it just modifies external classpath
		if (IntelliJ.configureDefaultDependencies) {
			IdeaDependencyManager.register(null, result, dependencies/*???*/)
			if (result.extraDependencies.isNotEmpty()) {
				LOG.info("Note: {} extra dependencies ({}) should be applied manually", result.buildNumber, result.extraDependencies)
			}
		} else {
			LOG.info("IDE {} dependencies are applied manually", result.buildNumber)
		}

		result
	}

	// Apparently the IDE needs to have the tools.jar on classpath
	extend(Configurations.running) {
		Keys.unmanagedDependencies modify { ud ->
			val toolsJar = jdkToolsJar(Keys.javaHome.get())
			if (toolsJar != null) {
				ud.toMutable().apply { add(LocatedPath(toolsJar)) }
			} else ud
		}
	}

	IntelliJ.intellijResolvedPluginDependencies set {
		val ideaDependency = IntelliJ.ideaDependency.get()
		val ideVersion = IdeVersion.createIdeVersion(ideaDependency.buildNumber)
		val resolver = PluginDependencyManager(ideaDependency, IntelliJ.pluginRepositories.get())

		val pluginDependencyIds = HashSet(IntelliJ.pluginDependencies.get())
		if (IntelliJ.configureDefaultDependencies) {
			// Collect transitive dependencies of built-in plugins and add them as well
			pluginDependencyIds.addAll(ideaDependency.pluginsRegistry.collectBuiltinDependencies(
					pluginDependencyIds.mapNotNull { if (it is PluginDependencyNotation.Bundled) it.name else null }
			).map { PluginDependencyNotation.Bundled(it) })
		}

		val pluginDependencies = ArrayList<PluginDependency>()
		for (it in pluginDependencyIds) {
			val pluginDependency:PluginDependency = when (it) {
				// TODO(jp): Wtf is this schism
				is PluginDependencyNotation.Project -> {
					if (it.projectDependency.project.evaluate(null, *it.projectDependency.configurations) { IntelliJ.pluginName.getOrElse(null) } == null) {
						throw WemiException("Cannot use ${it.projectDependency} as a plugin dependency - it is not a plugin", false)
					}

					dependencies.add(project.dependencies.create(dependency))
					val pluginDependency = PluginProjectDependency(dependency)
					project.tasks.withType(PrepareSandboxTask).each {
						it.configureCompositePlugin(pluginDependency)
					}
					pluginDependency
				}
				else -> {
					val plugin = resolver.resolve(null, it)
					if (ideVersion != null && !plugin.isCompatible(ideVersion)) {
						throw WemiException("Plugin $it is not compatible to ${ideVersion.asString()}", false)
					}

					if (extension.configureDefaultDependencies) {
						resolver.register(project, plugin, dependencies)
					}
					project.tasks.withType(PrepareSandboxTask).each {
						it.configureExternalPlugin(plugin)
					}
					plugin
				}
			}

			pluginDependencies.add(pluginDependency)
		}

		// TODO(jp): Add all Maven plugin repositories which were used to resolve something to Keys.repositories (original plugin does this, for some reason)

		// TODO(jp): Maybe there is a better way to do this? I.e. managing dependencies from Wemi directly and patching xml?
		if (!pluginDependencyIds.any { it is PluginDependencyNotation.Bundled && it.name == "java" } && (ideaDependency.classes / "plugins/java").exists()) {
			for (file in sourcePluginXmlFiles(false)) {
				val pluginXml = parseXml(file.file) ?: continue
				val depends = pluginXml.documentElement.getFirstElement("idea-plugin")?.namedElements("depends") ?: continue
				for (depend in depends) {
					if (depend.textContent == "com.intellij.modules.java") {
						throw WemiException("The project depends on `com.intellij.modules.java` module but doesn't declare a compile dependency on it.\n" +
								"Please delete `depends` tag from ${file.file.absolutePath} or add `java` plugin to Wemi dependencies")// TODO(jp): How it should look
					}
				}
			}
		}
	}

	setupDownloadRobotServerPluginTask()
	setupPatchPluginXmlTask()
	setupVerifyPluginTask()
}

val uiTesting by configuration("IDE UI Testing", Configurations.testing) {}
