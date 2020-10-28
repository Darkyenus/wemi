package wemiplugin.intellij

import Configurations
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
import wemi.assembly.NoConflictStrategyChooser
import wemi.assembly.NoPrependData
import wemi.util.FileSet
import wemi.util.LocatedPath
import wemi.util.absolutePath
import wemi.util.div
import wemi.util.ensureEmptyDirectory
import wemi.util.isRegularFile
import wemi.util.linkOrCopyRecursively
import wemi.util.matchingLocatedFiles
import wemi.util.name
import wemi.util.pathHasExtension
import wemi.util.toSafeFileName
import java.nio.file.Path


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

	val externalClasspath = using(Configurations.running) {
		Keys.externalClasspath.get().map { it.classpathEntry }
	}

	val pluginJar = Keys.archive.get()
	if (pluginJar == null || !pluginJar.isRegularFile() || !pluginJar.name.pathHasExtension("jar")) {
		throw WemiException("Archive must produce a jar, got: $pluginJar")
	}

	pluginJar.linkOrCopyRecursively(pluginLibDir / pluginJar.name)
	for (path in externalClasspath) {
		path.linkOrCopyRecursively(pluginLibDir / path.name)
	}

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

val DefaultIntelliJSearchableOptions : Value<Path?> = v@{
	val ideVersion = IntelliJ.resolvedIntellijIdeDependency.get().version
	if (ideVersion.baselineVersion < 191 || (ideVersion.baselineVersion == 191 && ideVersion.build < 2752)) {
		return@v null
	}

	val cacheDir = Keys.cacheDirectory.get()
	val outputDir = cacheDir / "-intellij-searchable-options"
	outputDir.ensureEmptyDirectory()
	runIde(listOf("traverseUI", outputDir.absolutePath, "true"))

	// Now package it
	val jar = outputDir / "searchableOptions.jar"
	AssemblyOperation().use {
		// TODO(jp): This is probably a bit more complicated
		for (file in FileSet(outputDir).matchingLocatedFiles()) {
			it.addSource(file, true)
		}
		it.assembly(NoConflictStrategyChooser, DefaultRenameFunction, DefaultAssemblyMapFilter, jar, NoPrependData, true)
	}
	jar

	// TODO(jp): ???
	/*private static void configureJarSearchableOptionsTask(@NotNull Project project) {
		Utils.info(project, "Configuring jar searchable options task")
		project.tasks.create(JAR_SEARCHABLE_OPTIONS_TASK_NAME, JarSearchableOptionsTask).with {
			description = "Jars searchable options."
			if (VersionNumber.parse(project.gradle.gradleVersion) >= VersionNumber.parse("5.1")) {
				archiveBaseName.set('lib/searchableOptions')
				destinationDirectory.set(project.layout.buildDirectory.dir("libsSearchableOptions"))
			} else {
				conventionMapping('baseName', { 'lib/searchableOptions' })
				destinationDir = new File(project.buildDir, "libsSearchableOptions")
			}

			dependsOn(BUILD_SEARCHABLE_OPTIONS_TASK_NAME)
			onlyIf { new File(project.buildDir, SEARCHABLE_OPTIONS_DIR_NAME).isDirectory() }
		}
	}*/

	/*class JarSearchableOptionsTask extends Jar {
		JarSearchableOptionsTask() {
			def pluginJarFiles = null
			from {
				include { FileTreeElement element ->
					if (element.directory) {
						return true
					}
					def suffix = ".searchableOptions.xml"
					if (element.name.endsWith(suffix)) {
						if (pluginJarFiles == null) {
							def prepareSandboxTask = project.tasks.findByName(IntelliJPlugin.PREPARE_SANDBOX_TASK_NAME) as PrepareSandboxTask
							def lib = "${prepareSandboxTask.getPluginName()}/lib"
							def files = new File(prepareSandboxTask.getDestinationDir(), lib).list()
							pluginJarFiles = files != null ? files as Set : []
						}
						def jarName = element.name.replace(suffix, "")
						pluginJarFiles.contains(jarName)
					}
				}
				"$project.buildDir/$IntelliJPlugin.SEARCHABLE_OPTIONS_DIR_NAME"
			}
			eachFile { path = "search/$name" }
			includeEmptyDirs = false
		}
	}*/
}

val DefaultIntelliJPluginArchive : Value<Path> = {
	val folder = IntelliJ.intellijPluginFolder.get()
	val searchableOptions = IntelliJ.intellijPluginSearchableOptions.get()

	val zip = folder.parent / "${folder.name}.zip"
	AssemblyOperation().use {
		for (file in FileSet(folder).matchingLocatedFiles()) {
			it.addSource(file, true, extractJarEntries = false)
		}
		if (searchableOptions != null) {
			// TODO(jp): This path is probably wrong
			it.addSource(LocatedPath(searchableOptions), true, extractJarEntries = false)
		}
		it.assembly(NoConflictStrategyChooser, DefaultRenameFunction, DefaultAssemblyMapFilter, zip, NoPrependData, true)
	}

	zip
}