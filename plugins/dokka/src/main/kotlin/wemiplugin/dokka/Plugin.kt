package wemiplugin.dokka

import org.slf4j.LoggerFactory
import wemi.KeyDefaults
import wemi.KeyDefaults.defaultArchiveFileName
import wemi.Value
import wemi.archetypes.JavaKotlinProject
import wemi.assembly.AssemblyOperation
import wemi.assembly.DefaultAssemblyMapFilter
import wemi.assembly.DefaultRenameFunction
import wemi.assembly.NoConflictStrategyChooser
import wemi.assembly.NoPrependData
import wemi.compile.JavaCompilerFlags
import wemi.compile.KotlinCompilerFlags
import wemi.compile.KotlinJVMCompilerFlags
import wemi.configurations.offline
import wemi.expiresWith
import wemi.inject
import wemi.key
import wemi.keys.archiveDocs
import wemi.keys.cacheDirectory
import wemi.keys.compilerOptions
import wemi.keys.externalClasspath
import wemi.keys.projectName
import wemi.keys.projectVersion
import wemi.keys.scopesCompile
import wemi.keys.sources
import wemi.plugin.PluginEnvironment
import wemi.util.LocatedPath
import wemi.util.constructLocatedFiles
import wemi.util.div
import wemi.util.ensureEmptyDirectory
import wemi.util.javadocUrl
import wemi.util.parseJavaVersion
import wemi.util.toSafeFileName
import java.net.URL
import java.nio.file.Path
import java.util.HashSet

val archiveDokkaOptions by key<DokkaConfig>("Options when archiving Dokka")

val ArchiveDokkaOptions: Value<DokkaConfig> = {
	val compilerOptions = compilerOptions.get()

	val options = DokkaOptions()

	for (sourceRoot in sources.getLocatedPaths().mapNotNullTo(HashSet()) { it.root?.toAbsolutePath() }) {
		options.sourceRoots.add(DokkaOptions.SourceRoot(sourceRoot))
	}

	val javaVersion = parseJavaVersion(
		compilerOptions.getOrNull(JavaCompilerFlags.sourceVersion)
			?: compilerOptions.getOrNull(JavaCompilerFlags.targetVersion)
			?: compilerOptions.getOrNull(KotlinJVMCompilerFlags.jvmTarget))

	val projectName = projectName.get()
	val externalClasspath = externalClasspath.getLocatedPathsForScope(scopesCompile.get()).map { it.classpathEntry }

	DokkaConfig(
		moduleName = compilerOptions.getOrNull(KotlinCompilerFlags.moduleName) ?: projectName,
		moduleVersion = projectVersion.get(),
		sourceSets = listOf(DokkaSourceSet(
			displayName = projectName,
			sourceSetID = "$projectName/main",
			classpath = externalClasspath,
			sourceRoots = sources.getLocatedPaths().mapTo(HashSet()) { it.classpathEntry },
			jdkVersion = javaVersion ?: 8,
			languageVersion = compilerOptions.getOrNull(KotlinCompilerFlags.languageVersion),
			apiVersion = compilerOptions.getOrNull(KotlinCompilerFlags.apiVersion),
			externalDocumentationLinks = setOf(ExternalDocumentationLink(URL(javadocUrl(javaVersion))))
		))
	)
}

private val ARCHIVE_DOKKA_LOG = LoggerFactory.getLogger("ArchiveDokka")
val ArchiveDokka: Value<Path> = archive@{
	val options = archiveDokkaOptions.get()

	if (options.sourceSets.isEmpty()) {
		ARCHIVE_DOKKA_LOG.info("No source files for Dokka, creating dummy documentation instead")
		return@archive KeyDefaults.ArchiveDummyDocumentation.invoke(this)
	}

	val cacheDirectory = cacheDirectory.get()
	val dokkaOutput = cacheDirectory / "dokka-${projectName.get().toSafeFileName('_')}"
	dokkaOutput.ensureEmptyDirectory()
	val dokkaCache = cacheDirectory / "-dokka-cache"
	dokkaCache.ensureEmptyDirectory()

	generateDokka(dokkaOutput, dokkaCache, options, progressListener, ARCHIVE_DOKKA_LOG)

	val locatedFiles = ArrayList<LocatedPath>()
	constructLocatedFiles(dokkaOutput, locatedFiles)

	AssemblyOperation().use { assemblyOperation ->
		// Load data
		for (file in locatedFiles) {
			assemblyOperation.addSource(file, own = true, extractJarEntries = false)
		}

		val outputFile = defaultArchiveFileName("docs", "jar")
		assemblyOperation.assembly(
			NoConflictStrategyChooser,
			DefaultRenameFunction,
			DefaultAssemblyMapFilter,
			outputFile,
			NoPrependData,
			compress = true)

		expiresWith(outputFile)
		outputFile
	}
}

/**
 * Initializes the plugin environment.
 */
class DokkaPluginEnvironment : PluginEnvironment {
	override fun initialize() {
		::JavaKotlinProject.inject {
			archiveDokkaOptions set ArchiveDokkaOptions
			archiveDocs set ArchiveDokka

			extend(offline) {
				archiveDokkaOptions modify { it.copy(offlineMode = true) }
			}
		}
	}
}