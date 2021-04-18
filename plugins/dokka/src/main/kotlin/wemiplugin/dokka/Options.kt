package wemiplugin.dokka

import Path
import wemi.util.div
import java.net.URL

/** See https://kotlin.github.io/dokka/1.4.30/user_guide/introduction/ for more info. */
data class DokkaConfig(
	val moduleName: String = "root",
	val moduleVersion: String? = null,
	val offlineMode: Boolean = false,
	val sourceSets: List<DokkaSourceSet> = emptyList(),
	/** Plugins to automatically add to pluginsClasspath. */
	val plugins:List<DokkaPlugin> = listOf(DokkaPlugin.HTML),
	val pluginsClasspath: List<Path> = emptyList(),
	val pluginsConfiguration: List<PluginConfig> = emptyList(),
	val modules: List<DokkaModuleDesc> = emptyList(),
	val failOnWarning: Boolean = false,
	val delayTemplateSubstitution: Boolean = false,
	val suppressObviousFunctions: Boolean = true
)

enum class DokkaPlugin(val artifactName:String) {
	HTML("dokka-base"),
	MARKDOWN("gfm-plugin"),
	JEKYLL("jekyll-plugin"),
	JAVADOC("javadoc-plugin"),
	KOTLIN_AS_JAVA("kotlin-as-java-plugin"),
	ANDROID_DOC("android-documentation-plugin"),
}

enum class PluginConfigSerializationFormat {
	JSON, XML
}

data class PluginConfig(
	val fqPluginName: String,
	val serializationFormat: PluginConfigSerializationFormat,
	val values: String
)

data class DokkaSourceSet(
	val displayName: String = "JVM",
	val sourceSetID: String = displayName,
	val classpath: List<Path> = emptyList(),
	val sourceRoots: Set<Path> = emptySet(),
	val dependentSourceSets: Set<DokkaSourceSet> = emptySet(),
	val samples: Set<Path> = emptySet(),
	val includes: Set<Path> = emptySet(),
	val includeNonPublic: Boolean = false,
	val reportUndocumented: Boolean = false,
	val skipEmptyPackages: Boolean = true,
	val skipDeprecated: Boolean = false,
	val jdkVersion: Int = 8,
	val sourceLinks: Set<SourceLinkDef> = emptySet(),
	val perPackageOptions: List<PackageOptions> = emptyList(),
	val externalDocumentationLinks: Set<ExternalDocumentationLink> = emptySet(),
	val languageVersion: String? = null,
	val apiVersion: String? = null,
	val noStdlibLink: Boolean = false,
	val noJdkLink: Boolean = false,
	val suppressedFiles: Set<Path> = emptySet(),
	val analysisPlatform: DokkaAnalysisPlatform = DokkaAnalysisPlatform.JVM
)

enum class DokkaAnalysisPlatform {
	JVM,
	JS,
	NATIVE,
	COMMON;

	companion object {
		fun fromString(key: String): DokkaAnalysisPlatform? {
			return when (key.toLowerCase()) {
				"jvm", "androidjvm", "android" -> JVM
				"js" -> JS
				"native" -> NATIVE
				"common", "metadata" -> COMMON
				else -> null
			}
		}
	}
}


data class DokkaModuleDesc(
	val name: String,
	val relativePathToOutputDirectory: Path,
	val includes: Set<Path>,
	val sourceOutputDirectory: Path
)

data class SourceLinkDef(
	val localDirectory: String,
	val remoteUrl: URL,
	val remoteLineSuffix: String?
)

data class PackageOptions(
	val matchingRegex: String,
	val includeNonPublic: Boolean = true,
	val reportUndocumented: Boolean? = null,
	val skipDeprecated: Boolean = false,
	val suppress: Boolean = false
)

data class ExternalDocumentationLink(
	val url: URL,
	val packageListUrl: URL = url / "package-list"
)
