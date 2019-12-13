package com.darkyen.wemi.intellij.projectImport

import com.darkyen.wemi.intellij.SessionActivityTracker
import com.darkyen.wemi.intellij.WemiBuildScriptProjectName
import com.darkyen.wemi.intellij.WemiLauncher
import com.darkyen.wemi.intellij.WemiLauncherSession
import com.darkyen.wemi.intellij.WemiNotificationGroup
import com.darkyen.wemi.intellij.importing.actions.ReloadProjectAction
import com.darkyen.wemi.intellij.options.ProjectImportOptions
import com.darkyen.wemi.intellij.settings.WemiModuleType
import com.darkyen.wemi.intellij.showBalloon
import com.darkyen.wemi.intellij.stage
import com.darkyen.wemi.intellij.util.Version
import com.darkyen.wemi.intellij.util.deleteRecursively
import com.darkyen.wemi.intellij.util.digestToHexString
import com.darkyen.wemi.intellij.util.div
import com.darkyen.wemi.intellij.util.update
import com.esotericsoftware.jsonbeans.JsonValue
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.SyncViewManager
import com.intellij.build.events.EventResult
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.FinishBuildEventImpl
import com.intellij.build.events.impl.FinishEventImpl
import com.intellij.build.events.impl.StartBuildEventImpl
import com.intellij.build.events.impl.StartEventImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.build.process.BuildProcessHandler
import com.intellij.concurrency.AsyncFuture
import com.intellij.concurrency.AsyncFutureFactory
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.PerformInBackgroundOption
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.StreamUtil
import com.intellij.pom.java.LanguageLevel
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

private val refreshProjectVisualizationTreeRootNextId = AtomicInteger(1)

/** Start project import process. */
fun refreshProject(project: Project?, launcher: WemiLauncher, options: ProjectImportOptions) : AsyncFuture<ProjectNode> {
	val futureResult = AsyncFutureFactory.getInstance().createAsyncFutureResult<ProjectNode>()

	object : Task.Backgroundable(project, "Importing Wemi Project", true, PerformInBackgroundOption { project != null }) {

		override fun run(indicator: ProgressIndicator) {
			if (project == null) {
				executeImpl(indicator)
			} else {
				DumbService.getInstance(project).suspendIndexingAndRun(title) {
					executeImpl(indicator)
				}
			}
		}

		private fun executeImpl(indicator: ProgressIndicator) {
			if (project?.isDisposed == true || indicator.isCanceled) {
				futureResult.cancel(true)
				return
			}

			val importTracker = object : SessionActivityTracker {

				private val buildId = "WemiImportProject${refreshProjectVisualizationTreeRootNextId.getAndIncrement()}"
				private val syncViewManager = project?.getService(SyncViewManager::class.java)
				private val buildProcessHandler = object : BuildProcessHandler() {

					override fun getProcessInput(): OutputStream? = null

					override fun detachIsDefault(): Boolean = false

					override fun getExecutionName(): String = "Wemi Project Resolution"

					override fun detachProcessImpl() {}

					override fun destroyProcessImpl() {}
				}

				override fun sessionOutput(text: String, outputType: Key<*>) {
					buildProcessHandler.notifyTextAvailable(text, outputType)
				}

				private fun checkIfCancelled() {
					if (indicator.isCanceled) {
						throw CancelImportException
					}
				}

				fun importBegin() {
					checkIfCancelled()
					syncViewManager?.onEvent(buildId,
							StartBuildEventImpl(DefaultBuildDescriptor(buildId, project?.name ?: "Wemi Project", launcher.file.parent.toString(), System.currentTimeMillis()), "Importing Wemi Project...")
									.withProcessHandler(buildProcessHandler, null)
									.withRestartAction(ReloadProjectAction().apply {
										templatePresentation.icon = AllIcons.Actions.Refresh
										templatePresentation.text = "Reimport Wemi Project"
										templatePresentation.description = "Force reimport of selected Wemi project"
									})
							// I am not entirely sure what should this do, but maybe we'll want it in the future?
							/*.withContentDescriptorSupplier {
								if (consoleView == null) {
									null
								} else {
									val contentDescriptor = BuildContentDescriptor(consoleView, processHandler, consoleView.component, "Sync")
									contentDescriptor.isActivateToolWindowWhenAdded = true
									contentDescriptor.isActivateToolWindowWhenFailed = true
									contentDescriptor.isAutoFocusContent = true
									contentDescriptor
								}
							}*/
					)
				}

				fun importSuccess() {
					syncViewManager?.onEvent(buildId, FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "Done", SuccessResultImpl()))

					WemiNotificationGroup.showBalloon(project, "Wemi - Success", "Project imported successfully", NotificationType.INFORMATION)
				}

				fun importFailure(e: Exception) {
					syncViewManager?.onEvent(buildId, FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "Failed", FailureResultImpl("Failed to import Wemi project", e)))

					WemiNotificationGroup.showBalloon(project, "Wemi - Failure", "Failed to import the project", NotificationType.WARNING)
				}

				fun importCancelled() {
					syncViewManager?.onEvent(buildId, FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "Cancelled", FailureResultImpl("Import was cancelled")))
				}

				private val NoResult = object : EventResult {}

				private val stageStack = ArrayList<String>()
				private val taskStack = ArrayList<String>()

				override fun stageBegin(name: String) {
					checkIfCancelled()
					syncViewManager?.onEvent(buildId, StartEventImpl(name, stageStack.lastOrNull() ?: buildId, System.currentTimeMillis(), name))
					stageStack.add(name)
				}

				override fun stageEnd() {
					checkIfCancelled()
					if (stageStack.isEmpty()) {
						LOG.error("stageEnd(), but stack is empty (task: $taskStack)", Exception())
						return
					}
					val last = stageStack.removeAt(stageStack.lastIndex)
					syncViewManager?.onEvent(buildId, FinishEventImpl(last, stageStack.lastOrNull() ?: buildId, System.currentTimeMillis(), last, NoResult))
				}

				override fun taskBegin(name: String) {
					checkIfCancelled()
					taskStack.add(name)
					syncViewManager?.onEvent(buildId, StartEventImpl(name, stageStack.lastOrNull() ?: buildId, System.currentTimeMillis(), name))
				}

				override fun taskEnd() {
					checkIfCancelled()
					if (taskStack.isEmpty()) {
						LOG.error("taskEnd(), but stack is empty (stage: $stageStack)", Exception())
						return
					}
					val last = taskStack.removeAt(taskStack.lastIndex)
					syncViewManager?.onEvent(buildId, FinishEventImpl(last, stageStack.lastOrNull() ?: buildId, System.currentTimeMillis(), last, NoResult))
				}

			}

			val startMs = System.currentTimeMillis()

			var result:ProjectNode? = null
			var error:Exception? = null
			importTracker.importBegin()
			try {
				result = gatherWemiProjectData(launcher, options, importTracker)
				LOG.info("Wemi [${launcher.file}] project resolution completed in ${System.currentTimeMillis() - startMs} ms")
			} catch (e:CancelImportException) {
				LOG.warn("Wemi [${launcher.file}] project resolution cancelled after ${System.currentTimeMillis() - startMs} ms", e)
			} catch (e:Exception) {
				error = e
				LOG.warn("Wemi [${launcher.file}] project resolution failed after ${System.currentTimeMillis() - startMs} ms", e)
			} finally {
				if (project?.isDisposed == true) {
					futureResult.cancel(true)
				} else if (result != null) {
					importTracker.importSuccess()
					futureResult.set(result)
				} else if (error != null) {
					importTracker.importFailure(error)
					futureResult.setException(error)
				} else {
					importTracker.importCancelled()
					futureResult.cancel(true)
				}
			}
		}
	}.queue()

	return futureResult
}

private object CancelImportException : Throwable("ImportCancelled", null, false, false)

/**
 * Builds object-level representation of the external system config file contained at the given path.
 *
 * @return object-level representation of the target external system project;
 * {@code null} if it's not possible to resolve the project
 * @throws java.lang.IllegalArgumentException if given path is invalid
 * @throws java.lang.IllegalStateException    if it's not possible to resolve target project info
 */
@Throws(IllegalArgumentException::class, IllegalStateException::class)
private fun gatherWemiProjectData(launcher:WemiLauncher,
                          options: ProjectImportOptions,
                          tracker: SessionActivityTracker): ProjectNode {
	var session: WemiLauncherSession? = null
	try {
		session = tracker.stage("Creating session") {
			WemiLauncherSession(launcher, options, {
				launcher.createWemiProcess(options, false, true,-1, WemiLauncher.DebugScheme.DISABLED, allowBrokenBuildScripts = true, interactive = true, machineReadable = true)
			}, options.prefixConfigurations, tracker)
		}

		// First request on session will be probably waiting for build scripts to compile
		tracker.stage("Loading build scripts") {
			val wemiVersion = session.string(project = null, task = "#version", includeUserConfigurations = false)
			LOG.info("Wemi version is $wemiVersion")
			session.wemiVersion = Version(wemiVersion)
		}

		@Suppress("UnnecessaryVariable") // Creates place for breakpoint
		val resolvedProject = resolveProjectInfo(launcher, session, launcher.file.parent.toAbsolutePath(), options.downloadSources, options.downloadDocumentation, tracker)
		return resolvedProject
	} finally {
		StreamUtil.closeStream(session)
	}
}

private fun resolveProjectInfo(launcher:WemiLauncher,
                               session: WemiLauncherSession,
                               projectRoot: Path,
                               downloadSources:Boolean, downloadDocumentation:Boolean,
                               tracker: SessionActivityTracker): ProjectNode {
	tracker.stageBegin("Resolving project list")
	val wemiProjects = session.stringArray(project = null, task = "#projects", includeUserConfigurations = false).let {
		projectNames ->
		val projectMap = mutableMapOf<String, WemiProjectData>()
		for (projectName in projectNames) {
			// Build script project is handled differently
			if (projectName == WemiBuildScriptProjectName) {
				continue
			}
			projectMap[projectName] = createWemiProjectData(session, projectName)
		}
		projectMap
	}
	val defaultWemiProject = wemiProjects[session.stringOrNull(null, task = "#defaultProject", includeUserConfigurations = false)] ?: run {
		// If there is no default project, pick any
		if (wemiProjects.isEmpty()) {
			null
		} else {
			wemiProjects.iterator().next().value
		}
	}

	val ideProjectNode = ProjectNode(defaultWemiProject?.projectName ?: projectRoot.fileName.toString(), projectRoot)

	// Pick sensible defaults from default project
	if (defaultWemiProject != null) {
		// Java data
		ideProjectNode.compileOutputPath = defaultWemiProject.classOutput
		defaultWemiProject.javaSourceVersion?.let {
			ideProjectNode.javaSourceVersion = it
		}
		defaultWemiProject.javaTargetVersion?.let {
			ideProjectNode.javaTargetVersion = it
		}

		// Kotlin data
		val kotlinCompilerData = HashMap<String, JsonValue>()
		session.jsonArray(defaultWemiProject.projectName, task = "compilerOptions", configurations = *arrayOf("compilingKotlin")).forEach {
			val key = it.getString("key")!!
			val value = it.get("value")
			kotlinCompilerData[key] = value
		}
		ideProjectNode.kotlinCompilerData = kotlinCompilerData
	}
	tracker.stageEnd()

	val projectModules = HashMap<String, ModuleNode>()
	val libraryBank = WemiLibraryDependencyBank()
	for (project in wemiProjects.values) {
		tracker.stage("Resolving project ${project.projectName}") {
			val ideModuleNode = project.moduleNode(WemiModuleType.PROJECT)
			ideProjectNode.modules.add(ideModuleNode)
			projectModules[project.projectName] = ideModuleNode

			// Collect dependencies
			val compileDependencies = createWemiProjectCombinedDependencies(session, project.projectName, "compiling", downloadDocumentation, downloadSources)
			val runtimeDependencies = createWemiProjectCombinedDependencies(session, project.projectName, "running", downloadDocumentation, downloadSources)
			val testDependencies = createWemiProjectCombinedDependencies(session, project.projectName, "testing", downloadDocumentation, downloadSources)

			for ((hash, dep) in compileDependencies) {
				val inRuntime = runtimeDependencies.remove(hash) != null
				val inTest = testDependencies.remove(hash) != null

				libraryBank.projectUsesLibrary(project.projectName, dep, true, inRuntime, inTest)
			}

			for ((hash, dep) in runtimeDependencies) {
				val inTest = testDependencies.remove(hash) != null
				libraryBank.projectUsesLibrary(project.projectName, dep, inCompile = false, inRuntime = true, inTest = inTest)
			}

			for ((_, dep) in testDependencies) {
				libraryBank.projectUsesLibrary(project.projectName, dep, inCompile = false, inRuntime = false, inTest = true)
			}
		}
	}

	// Inter-project dependencies
	for (project in wemiProjects.values) {
		tracker.stage("Resolving project dependencies of ${project.projectName}") {
			// We currently only process projects and ignore configurations because there is no way to map that
			val compiling = session.task(project.projectName, "compiling", task = "projectDependencies")
					.data(JsonValue.ValueType.array).map { it.getString("project")!! }
			val running = session.task(project.projectName, "running", task = "projectDependencies")
					.data(JsonValue.ValueType.array).map { it.getString("project")!! }
			val testing = session.task(project.projectName, "testing", task = "projectDependencies")
					.data(JsonValue.ValueType.array).map { it.getString("project")!! }

			val all = LinkedHashSet<String>(compiling)
			all.addAll(running)
			all.addAll(testing)

			for (depProjectName in all) {
				if (depProjectName == project.projectName) {
					continue
				}
				val inCompiling = compiling.contains(depProjectName)
				val inRunning = running.contains(depProjectName)
				val inTesting = testing.contains(depProjectName)

				val scope = toDependencyScope(inCompiling, inRunning, inTesting, depProjectName)
				val myModule = projectModules[project.projectName]!!
				myModule.moduleDependencies.add(ModuleDependencyNode(
						depProjectName,
						scope,
						scope.exported
				))
			}
		}
	}


	// Tasks
	tracker.stage("Resolving tasks") {
		for (task in session.jsonArray(project = null, task = "#keysWithDescription", includeUserConfigurations = false)) {
			val taskName = task.getString("name")!!
			val taskDescription = task.getString("description")!!
			ideProjectNode.tasks.add(TaskNode(taskName, taskDescription))
		}
	}

	// Build scripts
	tracker.stage("Resolving build script module") {
		val buildFolder = session.path(project = WemiBuildScriptProjectName, task = "projectRoot", includeUserConfigurations = false)

		val classpath = session.jsonArray(project = WemiBuildScriptProjectName, task = "externalClasspath").map { it.locatedFileOrPathClasspathEntry() }
		val cacheFolder = session.path(project = WemiBuildScriptProjectName, task = "cacheDirectory")
		val libFolderPath = cacheFolder.resolve("wemi-libs-ide")
		// Ensure that it is empty so that we don't accumulate old libs
		libFolderPath.deleteRecursively()
		Files.createDirectories(libFolderPath)

		// Module Data
		val wemiJavaLanguageLevel = LanguageLevel.HIGHEST // Using kotlin, but just for some sensible defaults
		val ideModuleNode = ModuleNode(
				WemiBuildScriptProjectName, WemiModuleType.BUILD_SCRIPT,
				buildFolder,
				cacheFolder, null,
				wemiJavaLanguageLevel, JavaSdkVersion.fromLanguageLevel(wemiJavaLanguageLevel)
				)
		ideProjectNode.modules.add(ideModuleNode)
		ideModuleNode.roots.sourceRoots = setOf(buildFolder)
		ideModuleNode.excludedRoots = setOf(buildFolder / "cache", buildFolder / "logs", buildFolder / "artifacts")


		// Dependencies
		val libraryNode = LibraryNode("$WemiBuildScriptProjectName Classpath")
		// Classpath
		libraryNode.artifacts = classpath.map { artifact ->
			var effectiveArtifact = artifact
			val artifactName = artifact.fileName.toString()
			if (!artifactName.endsWith(".jar", true)) {
				// Copy it, because Intellij does not recognize files without extension
				val link = libFolderPath.resolve("$artifactName.jar")
				try {
					Files.createSymbolicLink(link, effectiveArtifact)
				} catch (e:Exception) {
					LOG.info("Failed to soft link $artifactName, copying instead", e)
					Files.copy(effectiveArtifact, link)
				}
				effectiveArtifact = link
			}
			effectiveArtifact
		}
		// Sources and docs
		if (downloadSources) {
			libraryNode.sources = createWemiProjectDependencies(session, WemiBuildScriptProjectName, "retrievingSources").flatMap {
				(_, dependencies) ->
				dependencies.map { it.artifact }
			}
		}
		if (downloadDocumentation) {
			libraryNode.documentation = createWemiProjectDependencies(session, WemiBuildScriptProjectName, "retrievingDocs").flatMap {
				(_, dependencies) ->
				dependencies.map { it.artifact }
			}
		}
		// Wemi sources
		libraryNode.sources = libraryNode.sources + launcher.getClasspathSourceEntries(session.options)
		ideModuleNode.libraryDependencies.add(LibraryDependencyNode(libraryNode, DependencyScope.COMPILE, false))
	}

	// Apply library bank to create libraries
	tracker.stage("Setting dependencies") {
		libraryBank.apply(ideProjectNode, projectModules)
	}

	return ideProjectNode
}

private fun createWemiProjectData(session: WemiLauncherSession, projectName:String):WemiProjectData {
	val javacOptions = session.jsonArray(projectName, task = "compilerOptions", configurations = *arrayOf("compilingJava"))
	val sourceRoots: Array<Path>
	val resourceRoots: Array<Path>
	val sourceRootsTesting: Array<Path>
	val resourceRootsTesting: Array<Path>

	if (session.wemiVersion < Version.WEMI_0_7) {
		sourceRoots = session.stringArray(projectName, task = "sourceRoots").map { Paths.get(it) }.toTypedArray()
		resourceRoots = session.stringArray(projectName, task = "resourceRoots").map { Paths.get(it) }.toTypedArray()
		sourceRootsTesting = session.stringArray(projectName, "testing", task = "sourceRoots").map { Paths.get(it) }.toTypedArray()
		resourceRootsTesting = session.stringArray(projectName, "testing", task = "resourceRoots").map { Paths.get(it) }.toTypedArray()
	} else {
		sourceRoots = session.jsonArray(projectName, task = "sources", orNull = true).fileSetRoots()
		resourceRoots = session.jsonArray(projectName, task = "resources", orNull = true).fileSetRoots()
		sourceRootsTesting = session.jsonArray(projectName, "testing", task = "sources", orNull = true).fileSetRoots()
		resourceRootsTesting = session.jsonArray(projectName, "testing", task = "resources", orNull = true).fileSetRoots()
	}

	val javaSourceVersion:LanguageLevel? = LanguageLevel.parse(javacOptions.mapGet("sourceVersion")?.asString())
	val javaTargetVersion:JavaSdkVersion? = javacOptions.mapGet("targetVersion")?.asString()?.let { JavaSdkVersion.fromVersionString(it) }

	return WemiProjectData(
			projectName,
			session.path(projectName, task = "projectRoot"),
			javaSourceVersion, javaTargetVersion,
			session.path(projectName, task = "outputClassesDirectory"),
			session.pathOrNull(projectName, configurations = *arrayOf("testing"), task = "outputClassesDirectory?"),
			sourceRoots,
			resourceRoots,
			sourceRootsTesting,
			resourceRootsTesting
	)
}

private fun createWemiProjectDependencies(session: WemiLauncherSession, projectName: String, vararg config: String): Map<String, List<WemiLibraryDependency>> {
	val dependencies = LinkedHashMap<String, MutableList<WemiLibraryDependency>>()

	fun add(id:String, dep:WemiLibraryDependency) {
		dependencies.getOrPut(id) { mutableListOf() }.add(dep)
	}

	try {
		if (session.wemiVersion < Version.WEMI_0_8) {
			// ResolvedDependency contained map of generic attributes, which could also contain a classifier.
			// This was flattened in 0.8 and classifier is now a direct (optional) property.
			// Similarly, artifact path structure has been simplified.
			session.jsonObject(projectName, *config, task = "resolvedLibraryDependencies?", orNull = true).get("value")?.forEach { resolvedValue ->
				val projectId = resolvedValue.get("key")
				val artifact = resolvedValue.get("value")?.get("data")?.find { it.getString("name") == "artifactFile" }?.get("value")?.asString()
						?: return@forEach

				val group = projectId.getString("group")!!
				val name = projectId.getString("name")!!
				val version = projectId.getString("version")!!
				val classifier = projectId.get("attributes").find { it.getString("key") == "m2-classifier" }?.get("value")?.asString()
						?: ""

				add("$group:$name:$version",
						WemiLibraryDependency(
								"$group:$name:$version${if (classifier.isBlank()) "" else "-$classifier"}",
								Paths.get(artifact).toAbsolutePath().normalize()))
			}
		} else {
			session.jsonObject(projectName, *config, task = "resolvedLibraryDependencies?", orNull = true).get("value")?.forEach { resolvedValue ->
				val projectId = resolvedValue.get("key")
				val group = projectId.getString("group")!!
				val name = projectId.getString("name")!!
				val version = projectId.getString("version")!!
				val classifier = projectId.getString("classifier", "")!!

				val artifact = resolvedValue.get("value")?.getString("artifact", null) ?: return@forEach

				add("$group:$name:$version",
						WemiLibraryDependency(
								"$group:$name:$version${if (classifier.isBlank()) "" else "-$classifier"}",
								Paths.get(artifact).toAbsolutePath().normalize()))
			}
		}

		session.jsonArray(projectName, *config, task = "unmanagedDependencies?", orNull = true).forEach {
			val file = it.locatedFileOrPathClasspathEntry()

			add(file.toString(), WemiLibraryDependency(file.fileName.toString(), file))
		}
	} catch (e: ProjectImportException) {
		LOG.warn("Failed to resolve dependencies for ${config.joinToString("") { "$it:" }}$projectName", e)
	}

	return dependencies
}

private fun createWemiProjectCombinedDependencies(session: WemiLauncherSession, projectName: String, stage:String, withDocs:Boolean, withSources:Boolean):MutableMap<String, WemiLibraryCombinedDependency> {
	val artifacts = createWemiProjectDependencies(session, projectName, stage)
	val sources = if (withSources) createWemiProjectDependencies(session, projectName, stage, "retrievingSources") else emptyMap()
	val docs = if (withDocs) createWemiProjectDependencies(session, projectName, stage, "retrievingDocs") else emptyMap()

	val combined = HashMap<String, WemiLibraryCombinedDependency>()
	for ((artifactId, artifactDependency) in artifacts) {
		val dep = WemiLibraryCombinedDependency(artifactDependency.joinToString(", ") { it.name },
				artifactDependency.map { it.artifact },
				sources[artifactId]?.map { it.artifact } ?: emptyList(),
				docs[artifactId]?.map { it.artifact } ?: emptyList()
		)
		combined[dep.hash] = dep
	}

	return combined
}

private class WemiLibraryDependency(val name:String, val artifact: Path)

private class WemiLibraryCombinedDependency(val name:String,
                                            artifactFiles:List<Path>,
                                            sourceArtifactFiles:List<Path>,
                                            docArtifactFiles:List<Path>
) {

	val artifacts = artifactFiles.sortedBy { it.toAbsolutePath() }
	val sourceArtifacts = sourceArtifactFiles.sortedBy { it.toAbsolutePath() }
	val docArtifacts = docArtifactFiles.sortedBy { it.toAbsolutePath() }

	val hash: String by lazy(LazyThreadSafetyMode.NONE) {
		digestToHexString {
			update(artifacts) { update(it) }
			update(sourceArtifacts) { update(it) }
			update(docArtifacts) { update(it) }
		}
	}

	override fun toString(): String = name
}

private class WemiLibraryDependencyBank {
	private val hashToDependencyAndProjects
			= HashMap<String, // Hash
			Pair<
					WemiLibraryCombinedDependency, // Dependency for hash
					MutableMap<
							String, // Project name
							DependencyScope // Scope for project
							>
					>
			>()

	fun projectUsesLibrary(project: String, library:WemiLibraryCombinedDependency, inCompile:Boolean, inRuntime:Boolean, inTest:Boolean) {
		val projectMap = hashToDependencyAndProjects.getOrPut(library.hash) { Pair(library, mutableMapOf()) }.second

		projectMap[project] = toDependencyScope(inCompile, inRuntime, inTest, library)
	}

	fun apply(project: ProjectNode, modules:Map<String, ModuleNode>) {
		val projectToDependenciesMap = HashMap<ModuleNode, ArrayList<LibraryDependencyNode>>()

		for ((library, projects) in hashToDependencyAndProjects.values) {
			val ideLibraryNode = LibraryNode(library.name)
			ideLibraryNode.artifacts = library.artifacts
			ideLibraryNode.sources = library.sourceArtifacts
			ideLibraryNode.documentation = library.docArtifacts
			project.libraries.add(ideLibraryNode)

			for ((projectName, dependencyScope) in projects) {
				projectToDependenciesMap.getOrPut(modules.getValue(projectName)) { ArrayList() }
						.add(LibraryDependencyNode(ideLibraryNode, dependencyScope, dependencyScope.exported))
			}
		}

		for ((ideModuleNode, dependencies) in projectToDependenciesMap) {
			// Sort dependencies
			dependencies.sortWith(DependencyComparator)
			ideModuleNode.libraryDependencies.addAll(dependencies)
		}
	}
}

private class WemiProjectData constructor(
		val projectName:String,
		val rootPath:Path,
		val javaSourceVersion:LanguageLevel?,
		val javaTargetVersion:JavaSdkVersion?,
		val classOutput:Path,
		val classOutputTesting: Path?,
		sourceRoots:Array<Path>,
		resourceRoots:Array<Path>,
		sourceRootsTesting:Array<Path>,
		resourceRootsTesting:Array<Path>
) {

	val sourceRoots:Set<Path> = sourceRoots.toSet()
	val resourceRoots:Set<Path> = resourceRoots.toSet()
	val sourceRootsTesting:Set<Path> = sourceRootsTesting.toMutableSet().apply { removeAll(sourceRoots) }
	val resourceRootsTesting:Set<Path> = resourceRootsTesting.toMutableSet().apply { removeAll(resourceRoots) }

	fun moduleNode(type:WemiModuleType): ModuleNode {
		return ModuleNode(projectName, type, rootPath,
				classOutput, classOutputTesting,
				javaSourceVersion, javaTargetVersion).also { node ->
			node.roots.sourceRoots = sourceRoots
			node.roots.resourceRoots = resourceRoots
			node.testRoots.sourceRoots = sourceRootsTesting
			node.testRoots.resourceRoots = resourceRootsTesting
		}
	}
}


private val LOG = Logger.getInstance("WemiProjectImport")

private fun WemiLauncherSession.stringArray(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true, orNull:Boolean = false):Array<String> {
	return task(project = project, configurations = *configurations, task = task, includeUserConfigurations = includeUserConfigurations)
			.data(JsonValue.ValueType.array, orNull)
			.asStringArray()
}

private fun WemiLauncherSession.string(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true):String {
	return task(project = project, configurations = *configurations, task = task, includeUserConfigurations = includeUserConfigurations)
			.data(JsonValue.ValueType.stringValue)
			.asString()
}

private fun WemiLauncherSession.path(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true):Path {
	return string(project, configurations = *configurations, task = task, includeUserConfigurations = includeUserConfigurations).let { Paths.get(it) }
}

private fun WemiLauncherSession.stringOrNull(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true):String? {
	return task(project = project, configurations = *configurations, task = task, includeUserConfigurations = includeUserConfigurations)
			.data(JsonValue.ValueType.stringValue, orNull = true)
			.asString()
}

private fun WemiLauncherSession.pathOrNull(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true):Path? {
	return stringOrNull(project, configurations = *configurations, task = task, includeUserConfigurations = includeUserConfigurations)?.let { Paths.get(it) }
}

private fun WemiLauncherSession.jsonObject(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true, orNull:Boolean = false): JsonValue {
	return task(project = project, configurations = *configurations, task = task, includeUserConfigurations = includeUserConfigurations)
			.data(JsonValue.ValueType.`object`, orNull)
}

private fun WemiLauncherSession.jsonArray(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true, orNull:Boolean = false): JsonValue {
	return task(project = project, configurations = *configurations, task = task, includeUserConfigurations = includeUserConfigurations)
			.data(JsonValue.ValueType.array, orNull)
}

private fun JsonValue.locatedFileOrPathClasspathEntry(): Path {
	return if (this.type() == JsonValue.ValueType.stringValue) {
		return Paths.get(asString())
	} else {
		Paths.get(get("root")?.asString() ?: getString("file")!!)
	}.toAbsolutePath()
}

private fun JsonValue.fileSetRoots():Array<Path> {
	if (this.isNull) {
		return emptyArray() // limitation of JsonWritable, empty FileSet is just null
	}

	var child = this.child
	return Array(size) {
		val root = child.getString("root")
		child = child.next
		Paths.get(root)
	}
}

private fun JsonValue.mapGet(key:String): JsonValue? {
	for (child in this) {
		if (child.getString("key") == key) {
			return child.get("value")
		}
	}
	return null
}

private fun WemiLauncherSession.Companion.Result.data(type: JsonValue.ValueType? = null, orNull:Boolean = false): JsonValue {
	if (this.data == null || this.status != WemiLauncherSession.Companion.ResultStatus.SUCCESS) {
		LOG.warn("Failed to retrieve data of type $type - task request failed ($this)")
		throw ProjectImportException(this, type)
	}
	val valueType = this.data.type()
	val typeIsValid = type == null || (valueType == type) || (orNull && valueType == JsonValue.ValueType.nullValue)
	if (!typeIsValid) {
		LOG.warn("Failed to retrieve data of type $type - type mismatch ($this)")
		throw ProjectImportException(this, type)
	}
	return data
}

private fun toDependencyScope(inCompile:Boolean, inRuntime:Boolean, inTest:Boolean, dependencyLogInfo:Any): DependencyScope {
	if (inCompile && inRuntime) {
		if (!inTest) {
			LOG.warn("Dependency $dependencyLogInfo will be included in test configurations, but shouldn't be")
		}
		return DependencyScope.COMPILE
	} else if (inCompile && !inRuntime) {
		if (!inTest) {
			LOG.warn("Dependency $dependencyLogInfo will be included in test configurations, but shouldn't be")
		}
		return DependencyScope.PROVIDED
	} else if (!inCompile && inRuntime) {
		if (!inTest) {
			LOG.warn("Dependency $dependencyLogInfo will be included in test configurations, but shouldn't be")
		}
		return DependencyScope.RUNTIME
	} else {
		assert(inTest)
		return DependencyScope.TEST
	}
}

private val DependencyScope.exported:Boolean
	get() = DependencyScope.COMPILE == this

private fun artifactCriteriaPriority(dependency: LibraryDependencyNode):Int {
	when {
		dependency.onLibrary.sources.isNotEmpty() -> return 0
		dependency.onLibrary.documentation.isNotEmpty() -> return 1
		else -> return 2
	}
}

/**
 * Sorts dependencies.
 * Criteria:
 * 1. Dependencies with sources first, then those with documentation, then rest
 *      (this is done because Intellij then tries to find sources/documentation in random jars
 *      that may not have them and overlooks those that do have them)
 * 2. Otherwise stable
 */
private val DependencyComparator:Comparator<LibraryDependencyNode> = Comparator { a, b ->
	val a1 = artifactCriteriaPriority(a)
	val b1 = artifactCriteriaPriority(b)
	a1.compareTo(b1)
}

private class ProjectImportException(val result:WemiLauncherSession.Companion.Result,
                                     expectedType:JsonValue.ValueType?)
	: Exception("Got $result but expected $expectedType")