package wemiplugin.intellij

import Files
import Keys
import Path
import com.jetbrains.plugin.structure.intellij.version.IdeVersion
import org.slf4j.LoggerFactory
import wemi.Configurations
import wemi.Key
import wemi.archetype
import wemi.collections.toMutable
import wemi.configuration
import wemi.dependency.Dependency
import wemi.dependency.DependencyId
import wemi.dependency.Repository
import wemi.dependency.ScopeProvided
import wemi.key
import wemi.util.LocatedPath
import wemi.util.absolutePath
import wemi.util.div
import wemi.util.jdkToolsJar
import wemiplugin.intellij.dependency.IdeaDependency
import wemiplugin.intellij.tasks.DownloadRobotServerPluginTask.intellijRobotServerPlugin
import wemiplugin.intellij.tasks.DownloadRobotServerPluginTask.setupDownloadRobotServerPluginTask
import wemiplugin.intellij.tasks.PatchPluginXmlTask.setupPatchPluginXmlTask
import java.io.File
import java.net.URL
import java.util.stream.Collectors

private val LOG = LoggerFactory.getLogger("IntelliJPlugin")

/** All related keys. */
object IntelliJ {

	// TODO(jp): Remove unused elements
	const val PREPARE_SANDBOX_TASK_NAME = "prepareSandbox"
	const val BUILD_SEARCHABLE_OPTIONS_TASK_NAME = "buildSearchableOptions"
	const val SEARCHABLE_OPTIONS_DIR_NAME = "searchableOptions"
	const val JAR_SEARCHABLE_OPTIONS_TASK_NAME = "jarSearchableOptions"
	const val BUILD_PLUGIN_TASK_NAME = "buildPlugin"

	val intellijPluginName by key<String>("Name of the plugin")

	val intellijPluginDependencies by key<List<IntelliJPluginDependency>>("Dependencies on another plugins", emptyList())
	val intellijPluginRepositories by key<List<IntelliJPluginRepository>>("Repositories in which plugin dependencies can be found", listOf(IntelliJPluginsRepo))
	val resolvedIntellijPluginDependencies by key<List<ResolvedIntelliJPluginDependency>>("Resolved dependencies on another plugins")

	val intellijJbrVersion: Key<String?> by key("Explicitly set JBR version to use. null means use default for given IDE.", null as String?)
	val intellijJbrRepository: Key<URL?> by key("URL of repository for downloading JetBrains Java Runtime. null means use default for given version.", null as URL?)

	val intellijIdeDependency by key<IntelliJIDE>("The IntelliJ Platform IDE dependency specification")
	val intellijIdeRepository: Key<URL> by key("Repository to search for IntelliJ Platform IDE dependencies", IntelliJIDERepo)
	val resolvedIntellijIdeDependency by key<IdeaDependency>("IDE dependency to use for compilation and running")

	val instrumentCode by key("Instrument Java classes with nullability assertions and compile forms created by IntelliJ GUI Designer.", true)
	val verifyPlugin by key<Boolean>("Validates completeness and contents of plugin.xml descriptors as well as plugin’s archive structure.")
	val verifyPluginStrictness by key("How strict the plugin verification should be", Strictness.ALLOW_WARNINGS)

	val preparedIntellijIdeSandbox by key<IntelliJIDESandbox>("Prepare and return a sandbox directory that can be used for running an IDE along with the developed plugin")

	val intellijPluginArchive by key<Path>("Prepare and return a directory or a zip file containing the packaged plugin")

	/**
	 * configure extra dependency artifacts from intellij repo
	 *  the dependencies on them could be configured only explicitly using intellijExtra function in the dependencies block
	 */
	val extraDependencies by key<List<String>>("", emptyList())
}

val JetBrainsAnnotations = Dependency(DependencyId("org.jetbrains", "annotations", "20.1.0"), scope = ScopeProvided, optional = true)
val IntelliJPluginsRepo = IntelliJPluginRepository.Maven(Repository("intellij-plugins-repo", "https://cache-redirector.jetbrains.com/plugins.jetbrains.com/maven"))

/** A layer over [wemi.Archetypes.JVMBase] which turns the project into an IntelliJ platform plugin. */
val IntelliJPluginLayer by archetype {

	IntelliJ.intellijPluginName set { Keys.projectName.get() }

	Keys.libraryDependencies add { JetBrainsAnnotations }

	IntelliJ.preparedIntellijIdeSandbox set { prepareIntelliJIDESandbox() }

	Keys.runSystemProperties modify DefaultModifySystemProperties
	Keys.runOptions modify DefaultModifyRunOptions
	Keys.javaExecutable set DefaultJavaExecutable

	extend(Configurations.testing) {
		IntelliJ.preparedIntellijIdeSandbox set { prepareIntelliJIDESandbox(testSuffix = "-test") }

		Keys.externalClasspath modify {
			val ec = it.toMutable()
			val ideaDependency = IntelliJ.resolvedIntellijIdeDependency.get()
			ec.add(LocatedPath(ideaDependency.classes / "lib/resources.jar"))
			ec.add(LocatedPath(ideaDependency.classes / "lib/idea.jar"))
			ec
		}

		Keys.runSystemProperties modify {
			val sp = it.toMutableMap()

			val sandboxDir = IntelliJ.preparedIntellijIdeSandbox.get()

			// since 193 plugins from classpath are loaded before plugins from plugins directory
			// to handle this, use plugin.path property as task's the very first source of plugins
			// we cannot do this for IDEA < 193, as plugins from plugin.path can be loaded twice
			val ideVersion = IdeVersion.createIdeVersion(IntelliJ.resolvedIntellijIdeDependency.get().buildNumber)
			if (ideVersion.baselineVersion >= 193) {
				sp["plugin.path"] = Files.list(sandboxDir.plugins).collect(Collectors.toList()).joinToString(File.pathSeparator+",") { p -> p.absolutePath }
			}

			sp
		}
	}

	extend(uiTesting) {
		IntelliJ.preparedIntellijIdeSandbox set { prepareIntelliJIDESandbox(testSuffix = "-uiTest", extraPluginDirectories = *arrayOf(intellijRobotServerPlugin.get())) }
	}

	extend(Configurations.publishing) {
		// TODO(jp): Implement
		/*
        def prepareSandboxTask = project.tasks.findByName(PREPARE_SANDBOX_TASK_NAME) as PrepareSandboxTask
        def jarSearchableOptionsTask = project.tasks.findByName(JAR_SEARCHABLE_OPTIONS_TASK_NAME) as Jar
        Zip zip = project.tasks.create(BUILD_PLUGIN_TASK_NAME, Zip).with {
            description = "Bundles the project as a distribution."
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


	Keys.externalClasspath modify { cp ->
		val mcp = cp.toMutable()
		for (path in IntelliJ.resolvedIntellijIdeDependency.get().jarFiles) {
			mcp.add(LocatedPath(path))
		}
		for (dependency in IntelliJ.resolvedIntellijPluginDependencies.get()) {
			for (it in dependency.classpath()) {
				mcp.add(LocatedPath(it))
			}
		}
		mcp
	}

	IntelliJ.intellijIdeDependency set { IntelliJIDE.External() }
	IntelliJ.resolvedIntellijIdeDependency set defaultIdeDependency(false)

	extend(Configurations.retrievingSources) {
		IntelliJ.resolvedIntellijIdeDependency set defaultIdeDependency(true)
		Keys.externalClasspath addAll {
			IntelliJ.resolvedIntellijIdeDependency.get().sources.map { LocatedPath(it) }
		}
	}

	// Apparently the IDE needs to have the tools.jar on classpath
	extend(Configurations.running) {
		Keys.externalClasspath addAll { jdkToolsJar(Keys.javaHome.get())?.let { listOf(LocatedPath(it)) } ?: emptyList() }
	}

	IntelliJ.resolvedIntellijPluginDependencies set DefaultResolvedIntellijPluginDependencies

	setupDownloadRobotServerPluginTask()
	setupPatchPluginXmlTask()
	IntelliJ.verifyPlugin set DefaultVerifyIntellijPlugin
}

val uiTesting by configuration("IDE UI Testing", Configurations.testing) {}
