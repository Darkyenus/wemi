package wemiplugin.intellij

import Files
import Keys
import com.jetbrains.plugin.structure.base.plugin.PluginCreationFail
import com.jetbrains.plugin.structure.base.plugin.PluginCreationSuccess
import com.jetbrains.plugin.structure.base.plugin.PluginProblem
import com.jetbrains.plugin.structure.intellij.plugin.IdePluginManager
import org.slf4j.LoggerFactory
import wemi.Value
import wemi.WemiException
import wemi.assembly.AssemblyOperation
import wemi.assembly.DefaultAssemblyMapFilter
import wemi.assembly.DefaultRenameFunction
import wemi.assembly.MergeStrategy
import wemi.assembly.NoConflictStrategyChooser
import wemi.assembly.NoPrependData
import wemi.dependency.ScopeCompile
import wemi.dependency.ScopeRuntime
import wemi.util.FileSet
import wemi.util.absolutePath
import wemi.util.div
import wemi.util.ensureEmptyDirectory
import wemi.util.include
import wemi.util.isRegularFile
import wemi.util.linkOrCopyRecursively
import wemi.util.matchingFiles
import wemi.util.matchingLocatedFiles
import wemi.util.name
import wemi.util.pathHasExtension
import wemi.util.toSafeFileName
import wemiplugin.intellij.utils.Utils
import java.nio.file.Path
import java.util.stream.Collectors


private val LOG = LoggerFactory.getLogger("PackagePlugin")

enum class Strictness {
	/** Validation will always pass */
	LENIENT,
	/** Validation will pass as long as there are no failures */
	ALLOW_WARNINGS,
	/** Validation will pass only if there are no warnings nor failures */
	STRICT,
}

val DefaultIntelliJPluginFolder : Value<Path> = {
	val pluginName = IntelliJ.intellijPluginName.get().toSafeFileName()
	val pluginDir = Keys.cacheDirectory.get() / "-intellij-plugin-archive" / pluginName
	pluginDir.ensureEmptyDirectory()
	val pluginLibDir = pluginDir / "lib"
	Files.createDirectories(pluginLibDir)

	val externalClasspath = Keys.externalClasspath.getLocatedPathsForScope(setOf(ScopeCompile, ScopeRuntime)).map { it.classpathEntry }

	val pluginJar = Keys.archive.get()
	if (pluginJar == null || !pluginJar.isRegularFile() || !pluginJar.name.pathHasExtension("jar")) {
		throw WemiException("Archive must produce a jar, got: $pluginJar")
	}

	pluginJar.linkOrCopyRecursively(pluginLibDir / pluginJar.name)
	for (path in externalClasspath) {
		path.linkOrCopyRecursively(pluginLibDir / path.name)
	}

	val searchableOptions = IntelliJ.intellijPluginSearchableOptions.get()
	searchableOptions?.linkOrCopyRecursively(pluginLibDir / searchableOptions.name)

	val strictness = IntelliJ.intellijVerifyPluginStrictness.get()
	when (val creationResult = IdePluginManager.createManager().createPlugin(pluginDir)) {
		is PluginCreationSuccess -> {
			val warnings = creationResult.warnings
			if (warnings.isNotEmpty()) {
				for (warning in warnings) {
					LOG.warn("Plugin validation warning: {}", warning)
				}
				if (strictness > Strictness.ALLOW_WARNINGS) {
					throw WemiException("Plugin validation failed - fix the warnings or lower the strictness (key ${IntelliJ.intellijVerifyPluginStrictness})")
				}
			}
		}
		is PluginCreationFail -> {
			for (problem in creationResult.errorsAndWarnings) {
				when (problem.level) {
					PluginProblem.Level.ERROR -> LOG.warn("Plugin validation error: {}", problem)
					PluginProblem.Level.WARNING -> LOG.warn("Plugin validation warning: {}", problem)
				}
			}
			if (strictness > Strictness.LENIENT) {
				throw WemiException("Plugin validation failed - fix the warnings or lower the strictness (key ${IntelliJ.intellijVerifyPluginStrictness})")
			}
		}
	}

	pluginDir
}

val DefaultIntelliJSearchableOptions : Value<Path?> = {
	// The trouble here is that runIde requires plugin folder, which in turn requires searchable options
	// By explicitly using withoutSearchableOptions, it kicks out withSearchableOptions configuration,
	// which defines this, so it calls stub and not an infinite loop
	using(withoutSearchableOptions) {
		val ideVersion = IntelliJ.resolvedIntellijIdeDependency.get().version
		if (ideVersion.baselineVersion < 191 || (ideVersion.baselineVersion == 191 && ideVersion.build < 2752)) {
			return@using null
		}

		val cacheDir = Keys.cacheDirectory.get()
		val outputDir = cacheDir / "-intellij-searchable-options"
		outputDir.ensureEmptyDirectory()
		LOG.info("Starting IDE for searchable option collection")
		runIde(listOf("traverseUI", outputDir.absolutePath, "true"))

		// Collect
		val searchableOptionsSuffix = ".searchableOptions.xml"
		val searchXMLs = Utils.collectJars(IntelliJ.intellijPluginFolder.get() / "lib")
				.flatMap {
					FileSet(outputDir / it.name / "search", include("*$searchableOptionsSuffix")).matchingFiles().stream()
				}.collect(Collectors.toList())

		// Now package it
		val jar = outputDir / "searchableOptions.jar"
		AssemblyOperation().use {
			for (xml in searchXMLs) {
				val content = Files.readAllBytes(xml)
				it.addSource("search/${xml.name}", content, true)
			}
			it.assembly({ MergeStrategy.RenameDeduplicate }, DefaultRenameFunction, DefaultAssemblyMapFilter, jar, NoPrependData, true)
		}
		jar
	}
}

val DefaultIntelliJPluginArchive : Value<Path> = {
	val folder = using(withSearchableOptions) { IntelliJ.intellijPluginFolder.get() }

	val zip = folder.parent / "${folder.name}.zip"
	AssemblyOperation().use {
		for (file in FileSet(folder).matchingLocatedFiles()) {
			it.addSource(file, true, extractJarEntries = false)
		}
		it.assembly(NoConflictStrategyChooser, DefaultRenameFunction, DefaultAssemblyMapFilter, zip, NoPrependData, true)
	}

	zip
}