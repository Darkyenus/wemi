package wemiplugin.intellij.tasks

import Keys
import wemi.BindingHolder
import wemi.assembly.AssemblyOperation
import wemi.assembly.DefaultAssemblyMapFilter
import wemi.assembly.DefaultRenameFunction
import wemi.assembly.NoConflictStrategyChooser
import wemi.assembly.NoPrependData
import wemi.key
import wemi.util.FileSet
import wemi.util.Version
import wemi.util.absolutePath
import wemi.util.div
import wemi.util.ensureEmptyDirectory
import wemi.util.matchingLocatedFiles
import wemiplugin.intellij.tasks.RunIdeTask.runIde
import wemiplugin.intellij.utils.Utils.ideBuildNumber
import wemiplugin.intellij.utils.Utils.ideSdkDirectory
import java.nio.file.Path

/**
 *
 */
object SearchableOptionsTask {

	// TODO(jp): Pack in these searchable options into the archive when publishing or something
	val searchableOptions by key<Path?>("A jar with indexing data for plugin's preferences", null)

	internal fun BindingHolder.setupSearchableOptionsTask() {
		searchableOptions set {
			val buildNumber = ideBuildNumber(ideSdkDirectory())
			if (Version(buildNumber.takeWhile { it != '-' }) < Version("191.2752")) {
				return@set null
			}

			val cacheDir = Keys.cacheDirectory.get()
			val outputDir = cacheDir / "-intellij-searchable-options"
			outputDir.ensureEmptyDirectory()
			runIde(false, listOf("traverseUI", outputDir.absolutePath, "true"))

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
		}
	}

	/*private static void configureJarSearchableOptionsTask(@NotNull Project project) {
		Utils.info(project, "Configuring jar searchable options task")
		project.tasks.create(JAR_SEARCHABLE_OPTIONS_TASK_NAME, JarSearchableOptionsTask).with {
			group = GROUP_NAME
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