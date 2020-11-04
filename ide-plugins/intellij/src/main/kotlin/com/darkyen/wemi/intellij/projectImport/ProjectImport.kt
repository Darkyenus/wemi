package com.darkyen.wemi.intellij.projectImport

import com.darkyen.wemi.intellij.WemiBuildDirectoryName
import com.darkyen.wemi.intellij.WemiBuildScriptProjectName
import com.darkyen.wemi.intellij.WemiCacheDirectoryName
import com.darkyen.wemi.intellij.WemiLauncher
import com.darkyen.wemi.intellij.WemiLauncherSession
import com.darkyen.wemi.intellij.WemiNotificationGroup
import com.darkyen.wemi.intellij.importing.actions.ReloadProjectAction
import com.darkyen.wemi.intellij.importing.withWemiLauncher
import com.darkyen.wemi.intellij.options.ProjectImportOptions
import com.darkyen.wemi.intellij.settings.WemiModuleType
import com.darkyen.wemi.intellij.settings.WemiProjectService
import com.darkyen.wemi.intellij.showBalloon
import com.darkyen.wemi.intellij.util.SessionActivityTracker
import com.darkyen.wemi.intellij.util.SessionState
import com.darkyen.wemi.intellij.util.Version
import com.darkyen.wemi.intellij.util.deleteRecursively
import com.darkyen.wemi.intellij.util.digestToHexString
import com.darkyen.wemi.intellij.util.div
import com.darkyen.wemi.intellij.util.name
import com.darkyen.wemi.intellij.util.stage
import com.darkyen.wemi.intellij.util.stagesFor
import com.darkyen.wemi.intellij.util.update
import com.esotericsoftware.jsonbeans.JsonValue
import com.intellij.build.BuildContentManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.SyncViewManager
import com.intellij.build.events.EventResult
import com.intellij.build.events.FailureResult
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.FinishBuildEventImpl
import com.intellij.build.events.impl.FinishEventImpl
import com.intellij.build.events.impl.OutputBuildEventImpl
import com.intellij.build.events.impl.StartBuildEventImpl
import com.intellij.build.events.impl.StartEventImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.build.process.BuildProcessHandler
import com.intellij.concurrency.AsyncFuture
import com.intellij.concurrency.AsyncFutureFactory
import com.intellij.concurrency.ResultConsumer
import com.intellij.execution.KillableProcess
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.PerformInBackgroundOption
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.pom.java.LanguageLevel
import com.intellij.util.IncorrectOperationException
import com.intellij.util.concurrency.EdtExecutorService
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

private val refreshProjectVisualizationTreeRootNextId = AtomicInteger(1)

fun importWemiProject(project:Project, initial:Boolean) {
	project.withWemiLauncher(if (initial) "Wemi project import" else "Wemi project reload") { launcher ->
		val options = project.getService(WemiProjectService::class.java)!!.options
		val future = importWemiProjectStructure(project, launcher, options, initial, false)
		future.addConsumer(EdtExecutorService.getInstance(), object : ResultConsumer<ProjectNode> {
			override fun onSuccess(value: ProjectNode) {
				ApplicationManager.getApplication().invokeLater({
					WriteAction.run<Nothing> {
						importProjectStructureToIDE(value, project, importProjectName = initial)
					}
				}, ModalityState.NON_MODAL)
			}

			override fun onFailure(t: Throwable) {}
		})
	}
}

/** Start project import process. */
fun importWemiProjectStructure(project: Project?, launcher: WemiLauncher, options: ProjectImportOptions, activateToolWindow:Boolean, modal:Boolean) : AsyncFuture<ProjectNode> {
	val futureResult = AsyncFutureFactory.getInstance().createAsyncFutureResult<ProjectNode>()

	fun executeImpl(indicator: ProgressIndicator) {
		val sessionState = SessionState(indicator)
		if (project != null) {
			if (project.isDisposed) {
				sessionState.cancel(false)
			} else {
				try {
					Disposer.register(project, Disposable { sessionState.cancel(false) })
				} catch (e: IncorrectOperationException) {
					// Thrown when project is disposed or being disposed
					sessionState.cancel(false)
				}
			}
		}

		if (sessionState.isCancelled()) {
			futureResult.cancel(true)
			return
		}

		val startMs = System.currentTimeMillis()
		val buildId = "WemiImportProject${refreshProjectVisualizationTreeRootNextId.getAndIncrement()}"
		val syncViewManager = project?.getService(SyncViewManager::class.java)

		val buildProcessHandler = object : BuildProcessHandler(), KillableProcess, SessionState.Listener {

			override fun getProcessInput(): OutputStream? = null
			override fun detachIsDefault(): Boolean = false
			override fun getExecutionName(): String = "Wemi Project Resolution"

			override fun detachProcessImpl() {
				destroyProcessImpl()
			}

			override fun destroyProcessImpl() {
				sessionState.cancel(false)
			}

			override fun killProcess() {
				sessionState.cancel(true)
			}

			override fun canKillProcess(): Boolean = true

			override fun sessionStateChange(newState: SessionState.State) {
				if (newState == SessionState.State.FINISHED || newState == SessionState.State.FINISHED_CANCELLED) {
					notifyProcessTerminated(sessionState.exitCode)
				}
			}
		}
		sessionState.addListener(buildProcessHandler)

		val importTracker = object : SessionActivityTracker {
			override fun sessionOutput(text: String, outputType: Key<*>) {
				buildProcessHandler.notifyTextAvailable(text, outputType)
			}

			private val NoResult = object : EventResult {}
			private val FailResult = FailureResult { emptyList() }

			private val stageStack = ArrayList<String>()
			private val taskStack = ArrayList<String>()

			override fun stageBegin(name: String) {
				sessionState.checkCancelled()
				syncViewManager?.onEvent(buildId, StartEventImpl(name, stageStack.lastOrNull() ?: buildId, System.currentTimeMillis(), name))
				stageStack.add(name)
				indicator.text = name
			}

			override fun stageEnd() {
				sessionState.checkCancelled()
				if (stageStack.isEmpty()) {
					LOG.error("stageEnd(), but stack is empty (task: $taskStack)", Exception())
					return
				}
				val last = stageStack.removeAt(stageStack.lastIndex)
				indicator.text = stageStack.lastOrNull() ?: ""
				syncViewManager?.onEvent(buildId, FinishEventImpl(last, stageStack.lastOrNull() ?: buildId, System.currentTimeMillis(), last, NoResult))
			}

			override fun taskBegin(name: String) {
				sessionState.checkCancelled()
				taskStack.add(name)
				indicator.text2 = name
				syncViewManager?.onEvent(buildId, StartEventImpl(name, stageStack.lastOrNull() ?: buildId, System.currentTimeMillis(), name))
			}

			override fun taskEnd(output: String?, success:Boolean) {
				sessionState.checkCancelled()
				if (taskStack.isEmpty()) {
					LOG.error("taskEnd(), but stack is empty (stage: $stageStack)", Exception())
					return
				}
				val last = taskStack.removeAt(taskStack.lastIndex)
				indicator.text2 = taskStack.lastOrNull() ?: ""
				if (output != null) {
					syncViewManager?.onEvent(buildId, OutputBuildEventImpl(last, output, success))
				}
				syncViewManager?.onEvent(buildId, FinishEventImpl(last, stageStack.lastOrNull() ?: buildId, System.currentTimeMillis(), last, if (success) NoResult else FailResult))
			}

			private var maxProgressStack = IntArray(10)
			private var doneProgressStack = IntArray(10)

			override fun stageProgress(done: Int, outOf: Int) {
				// Update state
				val progressStackIndex = stageStack.size
				if (progressStackIndex >= maxProgressStack.size) {
					val newSize = maxOf(maxProgressStack.size, progressStackIndex + 1)
					maxProgressStack = maxProgressStack.copyOf(newSize)
					doneProgressStack = doneProgressStack.copyOf(newSize)
				}
				val maxProgressStack = maxProgressStack
				val doneProgressStack = doneProgressStack
				maxProgressStack[progressStackIndex] = maxOf(outOf, 1)
				doneProgressStack[progressStackIndex] = minOf(maxOf(done, 0), maxProgressStack[progressStackIndex])

				// Compute total progress
				var nestedFraction = 1f
				var total = 0f
				for (i in 0..progressStackIndex) {
					val levelSubdivisions = 1f / maxProgressStack[i].toFloat()
					total += (doneProgressStack[i].toFloat() * levelSubdivisions) * nestedFraction
					nestedFraction *= levelSubdivisions
				}

				if (indicator.isIndeterminate) {
					indicator.isIndeterminate = false
				}
				indicator.fraction = total.toDouble()
			}
		}

		syncViewManager?.onEvent(buildId,
				StartBuildEventImpl(DefaultBuildDescriptor(buildId, project.name, launcher.file.parent.toString(), System.currentTimeMillis()), "Importing Wemi Project...")
						.withProcessHandler(buildProcessHandler, null)
						.withRestartAction(ReloadProjectAction().also {
							it.templatePresentation.apply {
								icon = AllIcons.Actions.Refresh
								text = "Reimport Wemi Project"
								description = "Force reimport of selected Wemi project"
							}
						}))
		if (activateToolWindow && project != null && syncViewManager != null) {
			ApplicationManager.getApplication().invokeLater({
				var window : ToolWindow? = null
				try {// TODO(jp): Remove this after min version level is at 201
					// Available since 201
					window = project.getService(BuildContentManager::class.java).getOrCreateToolWindow()
				} catch (e:LinkageError) {
					try {
						// Deprecated since 201
						window = ToolWindowManager.getInstance(project).getToolWindow("Build" /*ToolWindowId.BUILD to silence deprecation warning */)
					} catch (e:LinkageError) {}
				}
				window?.show(null)
			}, ModalityState.NON_MODAL)
		}

		var result:ProjectNode? = null
		var error:Exception? = null
		try {
			sessionState.checkCancelled()
			result = gatherWemiProjectData(launcher, options, importTracker, sessionState)
			LOG.info("Wemi [${launcher.file}] project resolution completed in ${System.currentTimeMillis() - startMs} ms")
		} catch (e:ProcessCanceledException) {
			LOG.warn("Wemi [${launcher.file}] project resolution cancelled after ${System.currentTimeMillis() - startMs} ms")
		} catch (e:Exception) {
			error = e
			LOG.warn("Wemi [${launcher.file}] project resolution failed after ${System.currentTimeMillis() - startMs} ms", e)
		} finally {
			if (project?.isDisposed == true) {
				futureResult.cancel(true)
			} else if (result != null) {
				syncViewManager?.onEvent(buildId, FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "Done", SuccessResultImpl()))
				WemiNotificationGroup.showBalloon(project, "Wemi - Success", "Project imported successfully", NotificationType.INFORMATION)
				futureResult.set(result)
			} else if (error != null) {
				syncViewManager?.onEvent(buildId, FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "Failed", FailureResultImpl("Failed to import Wemi project", error)))
				WemiNotificationGroup.showBalloon(project, "Wemi - Failure", "Failed to import the project", NotificationType.WARNING)
				futureResult.setException(error)
			} else {
				syncViewManager?.onEvent(buildId, FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "Cancelled", FailureResultImpl("Import was cancelled")))
				futureResult.cancel(true)
			}
		}
	}

	if (!modal) {
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
		}.queue()
	} else {
		object : Task.Modal(project, "Importing Wemi Project", true) {
			override fun run(indicator: ProgressIndicator) {
				if (project == null) {
					executeImpl(indicator)
				} else {
					DumbService.getInstance(project).suspendIndexingAndRun(title) {
						executeImpl(indicator)
					}
				}
			}
		}.queue()
	}

	return futureResult
}

private const val TOP_LEVEL_STAGE_COUNT = 8

/**
 * Builds object-level representation of the external system config file contained at the given path.
 *
 * @return object-level representation of the target external system project
 * @throws Throwable on failure
 */
private fun gatherWemiProjectData(launcher:WemiLauncher, options: ProjectImportOptions,
                                  tracker: SessionActivityTracker, sessionState:SessionState): ProjectNode {
	var session: WemiLauncherSession? = null
	try {
		tracker.stageProgress(0, TOP_LEVEL_STAGE_COUNT)
		session = tracker.stage("Creating session") {
			WemiLauncherSession(launcher, options, {
				launcher.createWemiProcess(options, color = false, unicode = true,
						arguments = listOf("--allow-broken-build-scripts", "--interactive", "--machine-readable-output"),
						tasks = emptyList(), debugPort = -1, debugConfig = WemiLauncher.DebugScheme.DISABLED,
						pty = false).first
			}, options.prefixConfigurations, tracker, sessionState)
		}

		// First request on a session will be probably waiting for build scripts to compile
		tracker.stageProgress(1, TOP_LEVEL_STAGE_COUNT)
		tracker.stage("Loading build scripts") {
			val wemiVersion = session.string(project = null, task = "#version", includeUserConfigurations = false)
			LOG.info("Wemi version is $wemiVersion")
			session.wemiVersion = Version(wemiVersion)
			if (session.wemiVersion < Version.WEMI_0_14) {
				LOG.warn("Wemi versions older than 0.14 are not supported")
			}
		}

		val resolvedProject: ProjectNode
		try {
			resolvedProject = resolveProjectInfo(launcher, session, launcher.file.parent.toAbsolutePath(), options.downloadSources, options.downloadDocumentation, tracker)
		} catch (e:ProcessCanceledException) {
			throw e
		} catch (e:InvalidTaskResultException) {
			tracker.sessionOutput(e.toString(), ProcessOutputTypes.SYSTEM)
			throw e
		} catch (e:Exception) {
			val writer = StringWriter()
			PrintWriter(writer).use { e.printStackTrace(it) }
			tracker.sessionOutput(writer.toString(), ProcessOutputTypes.SYSTEM)
			throw e
		}
		return resolvedProject
	} finally {
		session?.close()
	}
}

private fun resolveProjectInfo(launcher:WemiLauncher,
                               session: WemiLauncherSession,
                               projectRoot: Path,
                               downloadSources:Boolean, downloadDocumentation:Boolean,
                               tracker: SessionActivityTracker): ProjectNode {
	tracker.stageProgress(2, TOP_LEVEL_STAGE_COUNT)
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
	}

	// Kotlin data
	ideProjectNode.kotlinCompilerData = defaultWemiProject?.compilerOptions ?: emptyMap()

	tracker.stageEnd()

	val projectModules = HashMap<String, ModuleNode>()
	val libraryBank = WemiLibraryDependencyBank()
	tracker.stageProgress(3, TOP_LEVEL_STAGE_COUNT)
	tracker.stagesFor("Resolving projects", wemiProjects.values, {"Resolving project ${it.projectName}"}) { project ->
		val ideModuleNode = project.moduleNode(projectRoot, WemiModuleType.PROJECT)
		ideProjectNode.modules.add(ideModuleNode)
		projectModules[project.projectName] = ideModuleNode

		// Collect dependencies
		val dependencies = createWemiProjectCombinedDependencies(session, project.projectName, downloadDocumentation, downloadSources)

		for ((dep, scopes) in dependencies) {
			for (scope in scopes) {
				libraryBank.projectUsesLibrary(project.projectName, dep, scope)
			}
		}
	}

	// Inter-project dependencies
	tracker.stageProgress(4, TOP_LEVEL_STAGE_COUNT)
	tracker.stagesFor("Resolving project dependencies", wemiProjects.values, {"Resolving project dependencies of ${it.projectName}"}) { project ->
		// We currently only process projects and ignore configurations because there is no way to map that
		val projectDependencies = session.task(project.projectName, task = "projectDependencies")
				.data(JsonValue.ValueType.array).map { it.getString("project")!! to (it.getString("scope") ?: "compile") }

		for ((projectDep, scopeStr) in projectDependencies) {
			if (projectDep == project.projectName) {
				continue
			}
			val scope = when (scopeStr.toLowerCase()) {// TODO(jp): Refactor
				"provided" -> DependencyScope.PROVIDED
				"runtime" -> DependencyScope.RUNTIME
				"test" -> DependencyScope.TEST
				else -> DependencyScope.COMPILE
			}

			val myModule = projectModules[project.projectName]!!
			myModule.moduleDependencies.add(ModuleDependencyNode(
					projectDep,
					scope,
					scope.exported
			))
		}
	}

	// Tasks
	tracker.stageProgress(5, TOP_LEVEL_STAGE_COUNT)
	tracker.stage("Resolving tasks") {
		for (task in session.jsonArray(project = null, task = "#keysWithDescription", includeUserConfigurations = false)) {
			val taskName = task.getString("name")!!
			val taskDescription = task.getString("description")!!
			ideProjectNode.tasks.add(TaskNode(taskName, taskDescription))
		}
	}

	// Build scripts
	tracker.stageProgress(6, TOP_LEVEL_STAGE_COUNT)
	tracker.stage("Resolving build script module") {
		val BUILD_SCRIPT_MODULE_PART_COUNT = 5
		tracker.stageProgress(0, BUILD_SCRIPT_MODULE_PART_COUNT)
		val buildFolder = session.path(project = WemiBuildScriptProjectName, task = "projectRoot", includeUserConfigurations = false)

		val classpath = session.jsonArray(project = WemiBuildScriptProjectName, task = "externalClasspath").map { it.locatedFileOrPathClasspathEntry() }
		val cacheFolder = session.path(project = WemiBuildScriptProjectName, task = "cacheDirectory")
		val libFolderPath = cacheFolder.resolve("wemi-libs-ide")
		// Ensure that it is empty so that we don't accumulate old libs
		libFolderPath.deleteRecursively()
		Files.createDirectories(libFolderPath)

		// Module Data
		tracker.stageProgress(1, BUILD_SCRIPT_MODULE_PART_COUNT)
		val wemiJavaLanguageLevel = LanguageLevel.HIGHEST // Using kotlin, but just for some sensible defaults
		val ideModuleNode = ModuleNode(
				WemiBuildScriptProjectName, WemiModuleType.BUILD_SCRIPT,
				buildFolder,
				cacheFolder, null,
				wemiJavaLanguageLevel, JavaSdkVersion.fromLanguageLevel(wemiJavaLanguageLevel)
				)
		ideProjectNode.modules.add(ideModuleNode)
		ideModuleNode.roots.sourceRoots = listOf(buildFolder)
		ideModuleNode.excludedRoots = setOf(buildFolder / "cache", buildFolder / "logs", buildFolder / "artifacts")


		// Dependencies
		val libraryNode = LibraryNode("$WemiBuildScriptProjectName Classpath")

		// Classpath
		tracker.stageProgress(2, BUILD_SCRIPT_MODULE_PART_COUNT)
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
		tracker.stageProgress(3, BUILD_SCRIPT_MODULE_PART_COUNT)
		if (downloadSources) {
			libraryNode.sources = createWemiProjectSourcesDocs(session, WemiBuildScriptProjectName, SourcesDocs.Sources)
		}
		if (downloadDocumentation) {
			libraryNode.documentation = createWemiProjectSourcesDocs(session, WemiBuildScriptProjectName, SourcesDocs.Docs)
		}

		// Wemi sources
		tracker.stageProgress(4, BUILD_SCRIPT_MODULE_PART_COUNT)
		libraryNode.sources = libraryNode.sources + launcher.getClasspathSourceEntries(session.options)
		ideModuleNode.libraryDependencies.add(LibraryDependencyNode(libraryNode, DependencyScope.COMPILE, false))
	}

	// Apply library bank to create libraries
	tracker.stageProgress(7, TOP_LEVEL_STAGE_COUNT)
	tracker.stage("Setting dependencies") {
		libraryBank.apply(ideProjectNode, projectModules)
	}

	return ideProjectNode
}

private fun createWemiProjectData(session: WemiLauncherSession, projectName:String):WemiProjectData {
	val compilerOptions = session.jsonArray(projectName, task = "compilerOptions").arrayToMap()

	val sourceRoots: Array<Path> = session.jsonArray(projectName, task = "sources", orNull = true).fileSetRoots()
	val resourceRoots: Array<Path> = session.jsonArray(projectName, task = "resources", orNull = true).fileSetRoots()
	val sourceRootsTesting: Array<Path> = session.jsonArray(projectName, "testing", task = "sources", orNull = true).fileSetRoots()
	val resourceRootsTesting: Array<Path> = session.jsonArray(projectName, "testing", task = "resources", orNull = true).fileSetRoots()

	return WemiProjectData(
			projectName,
			session.path(projectName, task = "projectRoot"),
			compilerOptions,
			session.path(projectName, task = "outputClassesDirectory"),
			session.pathOrNull(projectName, configurations = *arrayOf("testing"), task = "outputClassesDirectory?"),
			sourceRoots,
			resourceRoots,
			sourceRootsTesting,
			resourceRootsTesting
	)
}

private enum class SourcesDocs {
	Sources,
	Docs
}
private fun createWemiProjectSourcesDocs(session: WemiLauncherSession, projectName: String, sourcesDocs:SourcesDocs): List<Path> {
	try {
		return session.jsonArray(projectName, task = when (sourcesDocs) {
			SourcesDocs.Sources -> "externalSources"
			SourcesDocs.Docs -> "externalDocs"
		}, orNull = true).map { it.locatedFileOrPathClasspathEntry() }
	} catch (e: InvalidTaskResultException) {
		LOG.warn("Failed to resolve $sourcesDocs for $projectName", e)
	}
	return emptyList()
}

private fun createWemiProjectDependencies(session: WemiLauncherSession, projectName: String): Map<Path, Set<DependencyScope>> {
	val dependencies = LinkedHashMap<Path, MutableSet<DependencyScope>>()// TODO(jp): Check that Path can be used as a key safely

	fun add(dep:Path, scopeStr:String) {
		val scope = when (scopeStr.toLowerCase()) {
			"provided" -> DependencyScope.PROVIDED
			"runtime" -> DependencyScope.RUNTIME
			"test" -> DependencyScope.TEST
			else -> DependencyScope.COMPILE
		}
		dependencies.getOrPut(dep) { mutableSetOf() }.add(scope)
	}

	try {
		session.jsonArray(projectName, task = "externalClasspath?", orNull = true)?.forEach { scopedLocatedPath ->
			val scope = scopedLocatedPath.getString("scope", null) ?: "compile"
			val path = scopedLocatedPath.get("value")?.locatedFileOrPathClasspathEntry()
			if (path != null) {
				add(path, scope)
			} else {
				LOG.warn("Failed to import externalClasspath element {}", scopedLocatedPath)
			}
		}

		// Collect generated dependencies
		val generated = session.jsonArray(projectName, task="generatedClasspath?", orNull = true).mapNotNull { it?.locatedFileOrPathClasspathEntry() }
		for (path in generated) {
			add(path, "compile")
		}
	} catch (e: InvalidTaskResultException) {
		LOG.warn("Failed to resolve dependencies for $projectName", e)
	}

	return dependencies
}

private fun createWemiProjectCombinedDependencies(session: WemiLauncherSession, projectName: String, withDocs:Boolean, withSources:Boolean):MutableMap<WemiLibraryCombinedDependency, Set<DependencyScope>> {
	val artifacts = createWemiProjectDependencies(session, projectName)
	val sources = if (withSources) createWemiProjectSourcesDocs(session, projectName, SourcesDocs.Sources) else emptyList()
	val docs = if (withDocs) createWemiProjectSourcesDocs(session, projectName, SourcesDocs.Docs) else emptyList()

	val combined = HashMap<WemiLibraryCombinedDependency, Set<DependencyScope>>()
	for ((artifact, scopes) in artifacts) {
		val name = artifact.name // TODO(jp): Detect a better name for the dependency!

		val dep = WemiLibraryCombinedDependency(name,
				listOf(artifact),
				// TODO(jp): This mapping must be constructed differently!
				/*sources[artifactId]?.map { it.artifact } ?:*/ emptyList(),
				/*docs[artifactId]?.map { it.artifact } ?:*/ emptyList()
		)
		combined[dep] = scopes
	}

	return combined
}

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

	// TODO(jp): Generate equals and hash code!!!!!
}

private class WemiLibraryDependencyBank {
	private val bank = HashMap<WemiLibraryCombinedDependency, HashSet<Pair<String /* project */, DependencyScope>>>()

	fun projectUsesLibrary(project: String, library:WemiLibraryCombinedDependency, scope:DependencyScope) {
		bank.getOrPut(library) { HashSet() }.add(project to scope)
	}

	fun apply(project: ProjectNode, modules:Map<String, ModuleNode>) {
		val projectToDependenciesMap = HashMap<ModuleNode, ArrayList<LibraryDependencyNode>>()

		for ((library, projects) in bank) {
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
		val compilerOptions:Map<String, JsonValue>,
		val classOutput:Path,
		val classOutputTesting: Path?,
		sourceRoots:Array<Path>,
		resourceRoots:Array<Path>,
		sourceRootsTesting:Array<Path>,
		resourceRootsTesting:Array<Path>
) {

	val javaSourceVersion:LanguageLevel? = LanguageLevel.parse(compilerOptions["javacSourceVersion"]?.asString())
	val javaTargetVersion:JavaSdkVersion? = compilerOptions["javacTargetVersion"]?.asString()?.let { JavaSdkVersion.fromVersionString(it) }

	val sourceRoots:Set<Path> = sourceRoots.toSet()
	val resourceRoots:Set<Path> = resourceRoots.toSet()
	val sourceRootsTesting:Set<Path> = sourceRootsTesting.toMutableSet().apply { removeAll(sourceRoots) }
	val resourceRootsTesting:Set<Path> = resourceRootsTesting.toMutableSet().apply { removeAll(resourceRoots) }

	fun moduleNode(wemiRootPath:Path, type:WemiModuleType): ModuleNode {
		val wemiCachePath = (wemiRootPath / "$WemiBuildDirectoryName/$WemiCacheDirectoryName").toAbsolutePath()

		return ModuleNode(projectName, type, rootPath,
				classOutput, classOutputTesting,
				javaSourceVersion, javaTargetVersion).also { node ->

			val (generatedSourceRoots, sourceRoots) = sourceRoots.partition { it.toAbsolutePath().startsWith(wemiCachePath) }
			node.roots.sourceRoots = sourceRoots
			node.generatedRoots.sourceRoots = generatedSourceRoots

			val (generatedResourceRoots, resourceRoots) = resourceRoots.partition { it.toAbsolutePath().startsWith(wemiCachePath) }
			node.roots.resourceRoots = resourceRoots
			node.generatedRoots.resourceRoots = generatedResourceRoots

			val (generatedSourceRootsTesting, sourceRootsTesting) = sourceRootsTesting.partition { it.toAbsolutePath().startsWith(wemiCachePath) }
			node.testRoots.sourceRoots = sourceRootsTesting
			node.generatedTestRoots.sourceRoots = generatedSourceRootsTesting

			val (generatedResourceRootsTesting, resourceRootsTesting) = resourceRootsTesting.partition { it.toAbsolutePath().startsWith(wemiCachePath) }
			node.testRoots.resourceRoots = resourceRootsTesting
			node.generatedTestRoots.resourceRoots = generatedResourceRootsTesting
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

private fun JsonValue.arrayToMap():Map<String, JsonValue> {
	val result = HashMap<String, JsonValue>()
	for (child in this) {
		val key = child.getString("key") ?: continue
		result[key] = child.get("value") ?: continue
	}
	return result
}

private fun JsonValue.locatedFileOrPathClasspathEntry(): Path {
	return if (this.type() == JsonValue.ValueType.stringValue) {
		return Paths.get(asString())
	} else {
		var locatedPath = this
		if (!locatedPath.has("root") && !locatedPath.has("file")) {
			locatedPath = locatedPath.get("value")!! // Scoped<LocatedPath>
		}
		Paths.get(locatedPath.get("root")?.asString() ?: locatedPath.getString("file")!!)
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

private fun WemiLauncherSession.Companion.Result.data(type: JsonValue.ValueType? = null, orNull:Boolean = false): JsonValue {
	if (this.data == null) {
		if (orNull && this.status.convertsToNull) {
			LOG.debug("Failed to retrieve data of type $type - task request failed ($this) - converted to null")
			return JsonValue(JsonValue.ValueType.nullValue)
		}
		LOG.warn("Failed to retrieve data of type $type - task request failed ($this)")
		throw InvalidTaskResultException(this, type)
	}

	val valueType = this.data.type()
	val typeIsValid = type == null || (valueType == type) || (orNull && valueType == JsonValue.ValueType.nullValue)
	if (!typeIsValid) {
		LOG.warn("Failed to retrieve data of type $type - type mismatch ($this)")
		throw InvalidTaskResultException(this, type)
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
	return when {
		dependency.onLibrary.sources.isNotEmpty() -> 0
		dependency.onLibrary.documentation.isNotEmpty() -> 1
		else -> 2
	}
}

/**
 * Sorts dependencies.
 * Criteria:
 * 1. Dependencies with sources first, then those with a documentation, then rest
 *      (this is done because Intellij then tries to find sources/documentation in random jars
 *      that may not have them and overlooks those that do have them)
 * 2. Otherwise, stable
 */
private val DependencyComparator:Comparator<LibraryDependencyNode> = Comparator { a, b ->
	val a1 = artifactCriteriaPriority(a)
	val b1 = artifactCriteriaPriority(b)
	a1.compareTo(b1)
}

private class InvalidTaskResultException(val result:WemiLauncherSession.Companion.Result,
                                         expectedType:JsonValue.ValueType?)
	: Exception("Got $result but expected $expectedType")