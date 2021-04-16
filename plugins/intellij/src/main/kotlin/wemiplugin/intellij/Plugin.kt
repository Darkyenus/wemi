package wemiplugin.intellij

import Files
import Keys
import Path
import org.slf4j.LoggerFactory
import wemi.Archetypes
import wemi.Axis
import wemi.Configurations
import wemi.Key
import wemi.StringValidator
import wemi.WemiException
import wemi.archetype
import wemi.collections.toMutable
import wemi.command
import wemi.configuration
import wemi.dependency
import wemi.dependency.Dependency
import wemi.dependency.Repository
import wemi.dependency.ScopeProvided
import wemi.dependency.ScopeTest
import wemi.dependency.TypeChooseByPackaging
import wemi.dependency.resolveDependencyArtifacts
import wemi.generation.generateResources
import wemi.key
import wemi.readSecret
import wemi.readSecretFromFile
import wemi.run.ExitCode
import wemi.test.JUnit4Engine
import wemi.util.LocatedPath
import wemi.util.absolutePath
import wemi.util.div
import wemi.util.name
import wemi.util.pathWithoutExtension
import wemi.util.scoped
import wemiplugin.intellij.utils.Patch
import wemiplugin.intellij.utils.Utils.getPluginIds
import wemiplugin.intellij.utils.unZipIfNew
import java.io.File
import java.net.URL
import java.util.stream.Collectors

private val LOG = LoggerFactory.getLogger("IntelliJPlugin")

/** All related keys. */
object IntelliJ {

	val intellijPluginName by key<String>("Name of the plugin")

	val intellijPluginDependencies by key<List<IntelliJPluginDependency>>("Dependencies on another plugins", emptyList())
	val intellijPluginRepositories by key<List<IntelliJPluginRepository>>("Repositories in which plugin dependencies can be found", listOf(IntelliJPluginsRepo))
	val intellijResolvedPluginDependencies by key<List<ResolvedIntelliJPluginDependency>>("Resolved dependencies on another plugins")
	@Deprecated("Renamed", replaceWith = ReplaceWith("intellijResolvedPluginDependencies"))
	val resolvedIntellijPluginDependencies = intellijResolvedPluginDependencies

	/** See https://bintray.com/jetbrains/intellij-jbr for available versions. */
	val intellijJbrVersion: Key<String?> by key("Explicitly set JBR version to use. null means use default for given IDE.", null as String?)
	val intellijJbrRepository: Key<URL?> by key("URL of repository for downloading JetBrains Java Runtime. null means use default for given version.", null as URL?)

	val intellijIdeDependency by key<IntelliJIDE>("The IntelliJ Platform IDE dependency specification")
	val intellijIdeRepository: Key<URL> by key("Repository to search for IntelliJ Platform IDE dependencies", IntelliJIDERepo)
	val intellijResolvedIdeDependency by key<ResolvedIntelliJIDE>("IDE dependency to use for compilation and running")
	@Deprecated("Renamed", replaceWith = ReplaceWith("intellijResolvedIdeDependency"))
	val resolvedIntellijIdeDependency = intellijResolvedIdeDependency

	val intellijInstrumentationClasspath by key<List<java.nio.file.Path>>("Classpath used to spawn the instrumentation process")
	val intellijInstrumentCode by key("Instrument Java classes with nullability assertions and compile forms created by IntelliJ GUI Designer.", true)

	val intellijRobotServerDependency: Key<Pair<Dependency, Repository>> by key("Dependency on robot-server plugin for UI testing")

	val intellijPluginXmlFiles by key<List<LocatedPath>>("plugin.xml files that should be patched and added to classpath", emptyList())
	@Deprecated("Renamed", replaceWith = ReplaceWith("intellijPluginXmlFiles"))
	val intelliJPluginXmlFiles = intellijPluginXmlFiles

	val intellijPluginXmlPatches by key<List<Patch>>("Values to change in plugin.xml. Later added values override previous patches, unless using the ADD mode.", emptyList())
	@Deprecated("Renamed", replaceWith = ReplaceWith("intellijPluginXmlPatches"))
	val intelliJPluginXmlPatches = intellijPluginXmlPatches

	val intellijIdeSandbox by key<IntelliJIDESandbox>("Prepare and return a sandbox directory that can be used for running an IDE along with the developed plugin")
	@Deprecated("Renamed", replaceWith = ReplaceWith("intellijIdeSandbox"))
	val preparedIntellijIdeSandbox = intellijIdeSandbox


	val intellijVerifyPluginStrictness by key("How strict the plugin verification should be", Strictness.ALLOW_WARNINGS)
	val intellijPluginFolder by key<Path>("Prepare and return a directory containing the packaged plugin")
	val intellijPluginSearchableOptions by key<Path?>("A jar with indexing data for plugin's preferences (null if not supported for given IDE version)")
	val intellijPluginArchive by key<Path>("Package $intellijPluginFolder into a zip file, together with $intellijPluginSearchableOptions")

	val intellijPublishPluginToRepository: Key<Unit> by key("Publish plugin distribution on plugins.jetbrains.com")
	val intellijPublishPluginRepository: Key<String> by key("Repository to which the IntelliJ plugins are published to", "https://plugins.jetbrains.com")
	val intellijPublishPluginToken: Key<String> by key("Plugin publishing token")
	val intellijPublishPluginChannels: Key<List<String>> by key("Channels to which the plugin is published", listOf("default"))
}

val JetBrainsAnnotationsDependency = dependency("org.jetbrains", "annotations", "20.1.0", scope = ScopeProvided)
val IntelliJPluginsRepo = IntelliJPluginRepository.Maven(Repository("intellij-plugins-repo", URL("https://cache-redirector.jetbrains.com/plugins.jetbrains.com/maven"), authoritative = true, verifyChecksums = false))
val IntelliJThirdPartyRepo = Repository("intellij-third-party-dependencies", "https://jetbrains.bintray.com/intellij-third-party-dependencies")
val RobotServerDependency = dependency("org.jetbrains.test", "robot-server-plugin", "0.10.0", type = TypeChooseByPackaging)

val uiTesting by configuration("IDE UI Testing (launch the IDE in UI testing mode through ${Keys.run} key)") {}

val searchableOptionsAxis = Axis("searchableOptions")
val withoutSearchableOptions by configuration("Do not build IntelliJ plugin searchable options", searchableOptionsAxis) {}
val withSearchableOptions by configuration("Do not build IntelliJ plugin searchable options", searchableOptionsAxis) {}

val intellijPublishPluginToRepository by command("Proxy for intellijPublishPluginToRepository that allows setting the intellijPublishPluginToken") {
	val pluginName = evaluate { IntelliJ.intellijPluginName.get() }
	val token = readSecret("intellij-plugin-publish-token-$pluginName",
		"Publish token for plugin $pluginName. You can get one at https://plugins.jetbrains.com",
		StringValidator)

	if (token != null) {
		IntelliJ.intellijPublishPluginToken put token
	}

	evaluate { IntelliJ.intellijPublishPluginToRepository.get() }
}

/** A layer over [wemi.Archetypes.JVMBase] which turns the project into an IntelliJ platform plugin. */
val IntelliJPluginLayer by archetype(Archetypes::JUnitLayer) {

	// Project input info
	IntelliJ.intellijPluginName set { Keys.projectName.get() }
	Keys.libraryDependencies add { JetBrainsAnnotationsDependency }

	// Project dependencies
	Keys.externalClasspath modify  { cp ->
		val mcp = cp.toMutable()
		for (path in IntelliJ.intellijResolvedIdeDependency.get().jarFiles) {
			mcp.add(LocatedPath(path).scoped(ScopeProvided))
		}
		for (dependency in IntelliJ.intellijResolvedPluginDependencies.get()) {
			for (it in dependency.classpath()) {
				mcp.add(LocatedPath(it).scoped(ScopeProvided))
			}
		}
		mcp
	}
	IntelliJ.intellijResolvedPluginDependencies set DefaultResolvedIntellijPluginDependencies
	// Gradle plugin does something like this, but I don't think we need it
	//Keys.repositories addAll { IntelliJ.intellijPluginRepositories.get().mapNotNull { (it as? IntelliJPluginRepository.Maven)?.repo } }
	// IntelliJ already contains its own Kotlin stdlib - it would be nice to detect which version is used and use that
	Keys.automaticKotlinStdlib put false
	IntelliJ.intellijInstrumentationClasspath set DefaultInstrumentationClasspath
	Keys.compile modify { output ->
		if (IntelliJ.intellijInstrumentCode.get()) {
			instrumentClasses(output, IntelliJ.intellijInstrumentationClasspath.get())
		} else {
			output
		}
	}

	// Project compilation, archival and publishing
	IntelliJ.intellijPluginFolder set DefaultIntelliJPluginFolder
	IntelliJ.intellijPluginArchive set DefaultIntelliJPluginArchive
	IntelliJ.intellijPluginSearchableOptions put null
	extend(withSearchableOptions) {
		IntelliJ.intellijPluginSearchableOptions set DefaultIntelliJSearchableOptions
	}
	IntelliJ.intellijPublishPluginToken set {
		val pluginName = IntelliJ.intellijPluginName.get()
		readSecretFromFile("intellij-plugin-publish-token-$pluginName", StringValidator)
			?: throw WemiException("IntelliJ publish token for plugin $pluginName must be specified")
	}
	IntelliJ.intellijPublishPluginToRepository set DefaultIntellijPublishPluginToRepository

	// plugin.xml
	IntelliJ.intellijPluginXmlPatches addAll DefaultIntelliJPluginXmlPatches
	generateResources("patched-plugin-xml-files") { generatePatchedPluginXmlFiles(it) }

	// IntelliJ SDK resolution
	/** To find exact release version, try https://confluence.jetbrains.com/display/IDEADEV/IDEA+2020.1+latest+builds and related pages. */
	/** To find exact release version, try https://confluence.jetbrains.com/display/IDEADEV/IDEA+2020.1+latest+builds and related pages. */
	/** To find exact release version, try https://confluence.jetbrains.com/display/IDEADEV/IDEA+2020.1+latest+builds and related pages. */
	/** To find exact release version, try https://confluence.jetbrains.com/display/IDEADEV/IDEA+2020.1+latest+builds and related pages. */
	IntelliJ.intellijIdeDependency set { IntelliJIDE.External() }
	IntelliJ.intellijResolvedIdeDependency set ResolveIdeDependency
	IntelliJ.intellijIdeSandbox set { prepareIntelliJIDESandbox() }
	Keys.externalSources modify { es ->
		val sources = es.toMutable()
		sources.addAll(ResolveIdeDependencySources.invoke(this))
		sources
	}

	// IntelliJ SDK launch
	Keys.runSystemProperties modify DefaultModifySystemProperties
	Keys.runOptions modify DefaultModifyRunOptions
	Keys.javaHome set DefaultJbrJavaHome
	Keys.mainClass put "com.intellij.idea.Main"
	Keys.run set {
		expiresNow()
		ExitCode(runIde())
	}

	// Unit testing
	extend(Configurations.testing) {
		IntelliJ.intellijIdeSandbox set { prepareIntelliJIDESandbox(testSuffix = "-test") } // TODO(jp): Test tests

		Keys.runSystemProperties modify {
			val sp = it.toMutableMap()

			val sandboxDir = IntelliJ.intellijIdeSandbox.get()

			// since 193 plugins from classpath are loaded before plugins from plugins directory
			// to handle this, use plugin.path property as task's the very first source of plugins
			// we cannot do this for IDEA < 193, as plugins from plugin.path can be loaded twice
			val ideVersion = IntelliJ.intellijResolvedIdeDependency.get().version
			if (ideVersion.baselineVersion >= 193) {
				sp["plugin.path"] = Files.list(sandboxDir.plugins).collect(Collectors.toList()).joinToString(File.pathSeparator+",") { p -> p.absolutePath }
			}

			// TODO: Review this in context of Wemi (https://github.com/JetBrains/gradle-intellij-plugin/commit/ce057092a952609254768517357756cec5de06db)
			// appClassLoader should be used for user's plugins. Otherwise, classes it won't be possible to use
			// its classes of application components or services in tests: class loaders will be different for
			// classes references by test code and for classes loaded by the platform (pico container).
			//
			// The proper way to handle that is to substitute Gradle's test class-loader and teach it
			// to understand PluginClassLoaders. Unfortunately, I couldn't find a way to do that.
			sp["idea.use.core.classloader.for.plugin.path"] = "true"
			// the same as previous – setting appClassLoader but outdated. Works for part of 203 builds.
			sp["idea.use.core.classloader.for"] = getPluginIds().joinToString(",")

			sp
		}

		// TODO(jp): This may be needed
		// task.classpath += project.files(
		//                        "$extension.ideaDependency.classes/lib/resources.jar",
		//                        "$extension.ideaDependency.classes/lib/idea.jar"
		//                )
	}
	// IntelliJ test fixtures use JUnit4 API
	Keys.libraryDependencies add { Dependency(JUnit4Engine, scope= ScopeTest) }

	// UI Testing
	// Since I can't find any documentation about the robot thing, and since
	// https://jetbrains.org/intellij/sdk/docs/basics/testing_plugins/testing_plugins.html
	// mentions no UI testing (and actually discourages it, as of time of this writing),
	// this part of the plugin remains untested and a direct port from the gradle plugin.
	// If you are interested in making this work (or know how it works), feel free to get in touch.
	IntelliJ.intellijRobotServerDependency put (RobotServerDependency to IntelliJThirdPartyRepo)
	extend(uiTesting) {
		IntelliJ.intellijIdeSandbox set {
			val (dep, repo) = IntelliJ.intellijRobotServerDependency.get()
			val artifacts = resolveDependencyArtifacts(listOf(dep), listOf(repo), progressListener)
					?: throw WemiException("Failed to obtain robot-server dependency", false)
			val artifactZip = artifacts.singleOrNull()
					?: throw WemiException("Failed to obtain robot-server dependency - single artifact expected, but got $artifacts", false)
			val robotFolder = artifactZip.parent / artifactZip.name.pathWithoutExtension()
			unZipIfNew(artifactZip, robotFolder)
			prepareIntelliJIDESandbox(testSuffix = "-uiTest", extraPluginDirectories = *arrayOf(robotFolder))
		}
	}
}
