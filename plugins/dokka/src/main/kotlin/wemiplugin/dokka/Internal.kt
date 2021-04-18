package wemiplugin.dokka

import org.jetbrains.dokka.DokkaConfiguration
import org.jetbrains.dokka.DokkaGenerator
import org.jetbrains.dokka.DokkaSourceSetID
import org.jetbrains.dokka.Platform
import org.jetbrains.dokka.utilities.DokkaLogger
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import wemi.ActivityListener
import wemi.WemiException
import wemi.dependency
import wemi.dependency.MavenCentral
import wemi.dependency.resolveDependencyArtifacts
import java.io.File
import java.net.URL
import java.nio.file.Path

internal fun DokkaConfig.toInternalView(outputDirectory: Path, cacheDirectory:Path): DokkaConfiguration {
	val base = this
	return object : DokkaConfiguration {
		override val cacheRoot: File? get() = cacheDirectory.toFile()
		override val delayTemplateSubstitution: Boolean get() = base.delayTemplateSubstitution
		override val failOnWarning: Boolean get() = base.failOnWarning
		override val moduleName: String get() = base.moduleName
		override val moduleVersion: String? get() = base.moduleVersion
		override val modules: List<DokkaConfiguration.DokkaModuleDescription> get() = base.modules.map { it.internalView }
		override val offlineMode: Boolean get() = base.offlineMode
		override val outputDir: File get() = outputDirectory.toFile()
		override val pluginsClasspath: List<File> get() = base.pluginsClasspath.map { it.toFile() }
		override val pluginsConfiguration: List<DokkaConfiguration.PluginConfiguration> get() = base.pluginsConfiguration.map { it.internalView }
		override val sourceSets: List<DokkaConfiguration.DokkaSourceSet> get() = base.sourceSets.map { it.internalView }
		override val suppressObviousFunctions: Boolean get() = base.suppressObviousFunctions
	}
}

internal val PluginConfigSerializationFormat.internalView:DokkaConfiguration.SerializationFormat
	get() = when (this) {
		PluginConfigSerializationFormat.JSON -> DokkaConfiguration.SerializationFormat.JSON
		PluginConfigSerializationFormat.XML -> DokkaConfiguration.SerializationFormat.XML
	}

internal val PluginConfig.internalView: DokkaConfiguration.PluginConfiguration
	get() = object : DokkaConfiguration.PluginConfiguration {
		override val fqPluginName: String get() = this@internalView.fqPluginName
		override val serializationFormat: DokkaConfiguration.SerializationFormat get() = this@internalView.serializationFormat.internalView
		override val values: String get() = this@internalView.values
	}

internal val DokkaSourceSet.internalSourceSetId:DokkaSourceSetID
	get() {
		val rawId = this.sourceSetID
		val slashIndex = rawId.indexOf('/')
		if (slashIndex >= 0) {
			return DokkaSourceSetID(rawId.substring(0, slashIndex), rawId.substring(slashIndex + 1))
		}
		return DokkaSourceSetID(this.displayName, hashCode().toString())
	}

internal val DokkaSourceSet.internalView: DokkaConfiguration.DokkaSourceSet
	get() = object : DokkaConfiguration.DokkaSourceSet {
		override val analysisPlatform: Platform get() = this@internalView.analysisPlatform.internalView
		override val apiVersion: String? get() = this@internalView.apiVersion
		override val classpath: List<File> get() = this@internalView.classpath.map { it.toFile() }
		override val dependentSourceSets: Set<DokkaSourceSetID> get() = this@internalView.dependentSourceSets.mapTo(HashSet()) { it.internalSourceSetId }
		override val displayName: String get() = this@internalView.displayName
		override val externalDocumentationLinks: Set<DokkaConfiguration.ExternalDocumentationLink> get() = this@internalView.externalDocumentationLinks.mapTo(HashSet()) { it.internalView }
		override val includeNonPublic: Boolean get() = this@internalView.includeNonPublic
		override val includes: Set<File> get() = this@internalView.includes.mapTo(HashSet()) { it.toFile() }
		override val jdkVersion: Int get() = this@internalView.jdkVersion
		override val languageVersion: String? get() = this@internalView.languageVersion
		override val noJdkLink: Boolean get() = this@internalView.noJdkLink
		override val noStdlibLink: Boolean get() = this@internalView.noStdlibLink
		override val perPackageOptions: List<DokkaConfiguration.PackageOptions> get() = this@internalView.perPackageOptions.map { it.internalView }
		override val reportUndocumented: Boolean get() = this@internalView.reportUndocumented
		override val samples: Set<File> get() = this@internalView.samples.mapTo(HashSet()) { it.toFile() }
		override val skipDeprecated: Boolean get() = this@internalView.skipDeprecated
		override val skipEmptyPackages: Boolean get() = this@internalView.skipEmptyPackages
		override val sourceLinks: Set<DokkaConfiguration.SourceLinkDefinition> get() = this@internalView.sourceLinks.mapTo(HashSet()) { it.internalView }
		override val sourceRoots: Set<File> get() = this@internalView.sourceRoots.mapTo(HashSet()) { it.toFile() }
		override val sourceSetID: DokkaSourceSetID get() = this@internalView.internalSourceSetId
		override val suppressedFiles: Set<File> get() = this@internalView.suppressedFiles.mapTo(HashSet()) { it.toFile() }
	}

internal val DokkaAnalysisPlatform.internalView: Platform
	get() = when (this) {
		DokkaAnalysisPlatform.JVM -> Platform.jvm
		DokkaAnalysisPlatform.JS -> Platform.js
		DokkaAnalysisPlatform.NATIVE -> Platform.native
		DokkaAnalysisPlatform.COMMON -> Platform.common
	}

internal val DokkaModuleDesc.internalView: DokkaConfiguration.DokkaModuleDescription
	get() = object : DokkaConfiguration.DokkaModuleDescription {
		override val includes: Set<File> get() = this@internalView.includes.mapTo(HashSet()) { it.toFile() }
		override val name: String get() = this@internalView.name
		override val relativePathToOutputDirectory: File get() = this@internalView.relativePathToOutputDirectory.toFile()
		override val sourceOutputDirectory: File get() = this@internalView.sourceOutputDirectory.toFile()
	}

internal val SourceLinkDef.internalView: DokkaConfiguration.SourceLinkDefinition
	get() = object : DokkaConfiguration.SourceLinkDefinition {
		override val localDirectory: String get() = this@internalView.localDirectory
		override val remoteLineSuffix: String? get() = this@internalView.remoteLineSuffix
		override val remoteUrl: URL get() = this@internalView.remoteUrl
	}

internal val PackageOptions.internalView: DokkaConfiguration.PackageOptions
	get() = object : DokkaConfiguration.PackageOptions {
		override val includeNonPublic: Boolean get() = this@internalView.includeNonPublic
		override val matchingRegex: String get() = this@internalView.matchingRegex
		override val reportUndocumented: Boolean? get() = this@internalView.reportUndocumented
		override val skipDeprecated: Boolean get() = this@internalView.skipDeprecated
		override val suppress: Boolean get() = this@internalView.suppress
	}

internal val ExternalDocumentationLink.internalView: DokkaConfiguration.ExternalDocumentationLink
	get() = object : DokkaConfiguration.ExternalDocumentationLink {
		override val packageListUrl: URL get() = this@internalView.packageListUrl
		override val url: URL get() = this@internalView.url
	}

/**
 * @param outputDirectory to put the result in
 * @param logger to log info to
 * @param loggerMarker to use when logging with [logger]
 */
fun generateDokka(outputDirectory: Path,
                  cacheDirectory: Path,
                  options: DokkaConfig,
                  progressListener: ActivityListener?,
                  logger: Logger = LoggerFactory.getLogger("Dokka"),
                  loggerMarker: Marker? = null) {

	var processedOptions = options

	if (processedOptions.plugins.isNotEmpty()) {
		val dependencies = processedOptions.plugins.map { dependency("org.jetbrains.dokka", it.artifactName, DokkaVersion) }
		val artifacts = resolveDependencyArtifacts(dependencies, listOf(MavenCentral), progressListener)
			?: throw WemiException("Failed to resolve plugin artifacts")
		processedOptions = processedOptions.copy(plugins = emptyList(), pluginsClasspath = processedOptions.pluginsClasspath + artifacts)
	}

	var inDokkaActivity = false
	var hasProgressMessage = false
	try {
		progressListener?.beginActivity("Generating Dokka documentation")
		inDokkaActivity = true

		val gen = DokkaGenerator(processedOptions.toInternalView(outputDirectory, cacheDirectory), object : DokkaLogger {
			override var warningsCount: Int = 0

			override var errorsCount: Int = 0

			override fun debug(message: String) {
				logger.debug(loggerMarker, "{}", message)
			}

			override fun progress(message: String) {
				if (inDokkaActivity) {
					if (hasProgressMessage) {
						progressListener?.endActivity()
					}
					progressListener?.beginActivity(message)
					hasProgressMessage = true
				}
			}

			override fun info(message: String) {
				logger.info(loggerMarker, "{}", message)
			}

			override fun warn(message: String) {
				logger.warn(loggerMarker, "{}", message)
			}

			override fun error(message: String) {
				logger.error(loggerMarker, "{}", message)
			}
		})
		gen.generate()
	} finally {
		if (inDokkaActivity) {
			inDokkaActivity = false
			if (hasProgressMessage) {
				progressListener?.endActivity()
			}
			progressListener?.endActivity()
		}
	}

}