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
import com.darkyen.wemi.intellij.util.div
import com.darkyen.wemi.intellij.util.name
import com.darkyen.wemi.intellij.util.pathWithoutExtension
import com.darkyen.wemi.intellij.util.stage
import com.darkyen.wemi.intellij.util.stagesFor
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
import java.io.IOError
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.Comparator
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.collections.LinkedHashMap
import kotlin.math.abs

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

	// Make sure that kotlincJvmTarget is at least 1.8, otherwise build script will be red
	run {
		val kotlincJvmTarget = ideProjectNode.kotlinCompilerData["kotlincJvmTarget"]
		if (kotlincJvmTarget == null || !kotlincJvmTarget.isString || Version(kotlincJvmTarget.asString()) < Version("1.8")) {
			val map = ideProjectNode.kotlinCompilerData.toMutableMap()
			map["kotlincJvmTarget"] = JsonValue("1.8")
			ideProjectNode.kotlinCompilerData = map
		}
	}

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
				.data(JsonValue.ValueType.array).map { it.getString("project")!! to (it.getString("scope", null)) }

		for ((projectDep, scopeStr) in projectDependencies) {
			if (projectDep == project.projectName) {
				continue
			}
			val scope = scopeStr.stringToScope("dependency of ${project.projectName} on $projectDep")

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

		val classpath = session.classpath(WemiBuildScriptProjectName).map { (path, _) -> path }
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
				wemiJavaLanguageLevel, JavaSdkVersion.fromLanguageLevel(wemiJavaLanguageLevel),
				null)
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

	val javaHome:Path? = session.task(projectName, task="javaHome?").data?.let { javaHome ->
		if (javaHome.isString) {
			javaHome.asString()
		} else if (javaHome.isObject) {
			javaHome.getString("home", null)
		} else null
	}?.let { try { Paths.get(it) } catch (e:Exception) { null } }

	return WemiProjectData(
			projectName,
			session.path(projectName, task = "projectRoot"),
			compilerOptions,
			session.path(projectName, task = "outputClassesDirectory"),
			session.pathOrNull(projectName, configurations = *arrayOf("testing"), task = "outputClassesDirectory?"),
			sourceRoots,
			resourceRoots,
			sourceRootsTesting,
			resourceRootsTesting,
			javaHome
	)
}

private enum class SourcesDocs {
	Sources,
	Docs
}
private fun createWemiProjectSourcesDocs(session: WemiLauncherSession, projectName: String, sourcesDocs:SourcesDocs): List<Path> {
	val task = when (sourcesDocs) {
		SourcesDocs.Sources -> "externalSources"
		SourcesDocs.Docs -> "externalDocs"
	}

	try {
		return session.jsonArray(projectName, task = task, orNull = true).mapNotNull {
			it.locatedPathToClasspathEntry("$task of $projectName")
		}
	} catch (e: InvalidTaskResultException) {
		LOG.warn("Failed to resolve $sourcesDocs for $projectName", e)
	}
	return emptyList()
}

private fun createWemiProjectDependencies(session: WemiLauncherSession, projectName: String): Map<Path, Set<DependencyScope>> {
	val dependencies = LinkedHashMap<Path, MutableSet<DependencyScope>>()

	for ((path, scope) in session.classpath(projectName)) {
		dependencies.getOrPut(path) { HashSet() }.add(scope)
	}

	for (scopeSet in dependencies.values) {
		// Compile scope automatically encompasses all other scopes, so there is no need to have them
		if (scopeSet.size > 1 && DependencyScope.COMPILE in scopeSet) {
			scopeSet.clear()
			scopeSet.add(DependencyScope.COMPILE)
		}
	}

	return dependencies
}

/**
 * Collection of functions that measure the goodness of a fit with increasing granularity.
 * The returned number is a distance, smaller means better.
 */
private val AUX_MATCH_FIT_GOODNESS:Array<(aux:Path, artifact:Path) -> Int> = arrayOf(
		{ aux, artifact ->
			// Find files that are closer together in the filesystem, ideally in the same directory
			aux.parent.relativize(artifact.parent).nameCount
		},
		{ aux, artifact ->
			// Find files that share name
			val auxName = aux.name.pathWithoutExtension().removeSuffix("-sources").removeSuffix("-docs")
			val artifactName = artifact.name.pathWithoutExtension()
			if (auxName.equals(artifactName, ignoreCase = false)) {
				0
			} else if (auxName.equals(artifactName, ignoreCase = true)) {
				1
			} else {
				Int.MAX_VALUE
			}
		}
		// Anything less precise leads to problems, for example in IntelliJ itself, where a single maven-sources file should apply to all IntelliJ's jars.
)

private fun assignAuxiliaryFilesArtifacts(auxFiles:List<Path>, artifacts:Collection<Path>):Map</*artifact*/Path, /*aux*/List<Path>> {
	if (auxFiles.isEmpty() || artifacts.isEmpty()) {
		return emptyMap()
	}

	val result = HashMap<Path, ArrayList<Path>>()

	aux@for (aux in auxFiles) {
		var previousBest = artifacts
		for (fitFunction in AUX_MATCH_FIT_GOODNESS) {
			val currentBest = ArrayList<Path>()
			var currentBestDistance = Int.MAX_VALUE

			for (artifact in previousBest) {
				val distance = try {
					fitFunction(aux, artifact)
				} catch (e:Exception) {
					LOG.warn("Failed to evaluate fitFunction $fitFunction on ($aux, $artifact)", e)
					Int.MAX_VALUE
				}

				if (distance < currentBestDistance) {
					currentBestDistance = distance
					currentBest.clear()
					currentBest.add(artifact)
				} else if (distance == currentBestDistance) {
					currentBest.add(artifact)
				}
			}


			if (currentBest.size == 1) {
				// Best match found, assign and move on
				result.getOrPut(currentBest[0]) { ArrayList() }.add(aux)
				continue@aux
			} else if (currentBest.isEmpty()) {
				// Can not find the best match, assign to all previous best fits
				break
			}
			previousBest = currentBest
		}

		// Out of fit functions
		LOG.info("Failed to find a single best fit for auxiliary file $aux - assigning it to multiple artifacts: $previousBest")
		for (path in previousBest) {
			result.getOrPut(path) { ArrayList() }.add(aux)
		}
	}

	return result
}

private fun jarRootPackageNames(artifact:Path):Set<String> {
	try {
		return ZipFile(artifact.toFile(), ZipFile.OPEN_READ, Charsets.UTF_8).entries().asSequence().map {
			val parts = it.name.split('/').dropWhile { p -> p.isEmpty() }
			if (parts.size > 1 || (parts.size == 1 && it.isDirectory)) {
				parts[0].toLowerCase(Locale.ROOT)
			} else null
		}.filterNotNullTo(HashSet())
	} catch (e:Exception) {
		LOG.debug("Failed to extract package root names from $artifact", e)
		return emptySet()
	}
}

private fun createArtifactLibraryName(artifact:Path, groupStart:Int, groupEnd:Int, name:String, versionWithClassifier:String):String {
	val result = StringBuilder()
	for (i in groupStart until groupEnd) {
		if (i != groupStart) {
			result.append('.')
		}
		result.append(artifact.getName(i))
	}
	result.append(':').append(name).append(':').append(versionWithClassifier)
	return result.toString()
}

private fun indexOfGroupStartInMavenArtifact(artifact:Path):Int {
	val m2Index = artifact.indexOfLast { it.toString().equals(".m2", ignoreCase = true) }
	if (m2Index >= 0) {
		return m2Index + 2 // .m2/repository
	}

	val wemiIndex = artifact.indexOfLast { it.toString().equals(".wemi", ignoreCase = true) }
	if (wemiIndex >= 0) {
		return wemiIndex + 3 // .wemi/maven-cache/<repo-name>
	}

	return -1
}

private fun detectArtifactLibraryName(artifact:Path):String {
	// First try checking whether this is a maven library
	val plainName = artifact.name.pathWithoutExtension()
	val maybeVersionDir = artifact.parent
	val maybeVersion = maybeVersionDir?.name
	val maybeArtifactNameDir = maybeVersionDir?.parent
	val maybeArtifactName = maybeArtifactNameDir?.name
	if (maybeVersion != null && maybeArtifactName != null
			&& maybeVersion in plainName && maybeArtifactName in plainName
			&& plainName.indexOf(maybeArtifactName) < plainName.indexOf(maybeVersion)) {
		// This looks very much like a Maven artifact in a repository!
		// Determine classifier, if any
		val versionWithClassifier = plainName.substring(plainName.indexOf(maybeVersion))

		val groupEndIndex = maybeArtifactNameDir.nameCount - 1
		val groupStartIndex = indexOfGroupStartInMavenArtifact(artifact)
		if (groupStartIndex >= 0 && groupStartIndex < groupEndIndex) {
			// It is in a known repository!
			// Reconstruct the group
			return createArtifactLibraryName(artifact, groupStartIndex, groupEndIndex, maybeArtifactName, versionWithClassifier)
		}

		// Repository root not known
		// If the file is a zip, then we can find packages inside and at least one of them should appear in the path
		val rootPackageNames = jarRootPackageNames(artifact)
		var i = groupEndIndex
		while (i >= 0) {
			if (artifact.getName(i).toString().toLowerCase(Locale.ROOT) in rootPackageNames) {
				// Looks good
				return createArtifactLibraryName(artifact, i, groupEndIndex, maybeArtifactName, versionWithClassifier)
			}
			i--
		}

		// Still not known, just do it without any group
		return createArtifactLibraryName(artifact, 0, 0, maybeArtifactName, versionWithClassifier)
	}

	// Not a maven artifact, probably, return dummy name
	return plainName
}

private fun createWemiProjectCombinedDependencies(session: WemiLauncherSession, projectName: String, withDocs:Boolean, withSources:Boolean):MutableMap<WemiLibraryCombinedDependency, Set<DependencyScope>> {
	val artifacts = createWemiProjectDependencies(session, projectName)
	val sources = if (withSources) createWemiProjectSourcesDocs(session, projectName, SourcesDocs.Sources) else emptyList()
	val docs = if (withDocs) createWemiProjectSourcesDocs(session, projectName, SourcesDocs.Docs) else emptyList()
	val sourcesByArtifact = assignAuxiliaryFilesArtifacts(sources, artifacts.keys)
	val docsByArtifact = assignAuxiliaryFilesArtifacts(docs, artifacts.keys)

	val combined = HashMap<WemiLibraryCombinedDependency, Set<DependencyScope>>()
	for ((artifact, scopes) in artifacts) {
		val name = detectArtifactLibraryName(artifact)

		val dep = WemiLibraryCombinedDependency(name,
				listOf(artifact),
				sourcesByArtifact.getOrDefault(artifact, emptyList()),
				docsByArtifact.getOrDefault(artifact, emptyList())
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
		resourceRootsTesting:Array<Path>,
		val javaHome:Path?
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
				javaSourceVersion, javaTargetVersion, javaHome).also { node ->

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

private fun String?.stringToScope(debugContext:String):DependencyScope {
	return when (this?.toLowerCase()) {
		"provided" -> DependencyScope.PROVIDED
		"runtime" -> DependencyScope.RUNTIME
		"test" -> DependencyScope.TEST
		"compile", "aggregate" -> DependencyScope.COMPILE
		else -> {
			LOG.warn("Unrecognized scope of $debugContext: '$this' - falling back to compile scope")
			DependencyScope.COMPILE
		}
	}
}

private fun JsonValue.locatedPathToClasspathEntry(debugContext:String):Path? {
	val classpathPath = if (isString) asString() else getString("root", null) ?: getString("file", null)
	if (classpathPath == null) {
		LOG.warn("Failed to resolve classpath entry of $debugContext from $this")
		return null
	}

	val path = try {
		Paths.get(classpathPath)
	} catch (e: InvalidPathException) {
		LOG.warn("Failed to resolve path of classpath entry of $debugContext from $classpathPath", e)
		return null
	}

	val absPath = try {
		path.toAbsolutePath()
	} catch (e: IOError) {
		LOG.debug("Failed to make $path absolute for classpath entry of $debugContext", e)
		path
	}

	return absPath.normalize()
}

private fun WemiLauncherSession.classpath(project:String):List<Pair<Path, DependencyScope>> {
	val result = ArrayList<Pair<Path, DependencyScope>>()
	try {
		for (scopedLocatedPath in jsonArray(project, task = "externalClasspath?", orNull = true)) {
			val path = scopedLocatedPath.locatedPathToClasspathEntry("externalClasspath of $project")
					?: continue

			val scope = scopedLocatedPath.getString("scope", null)
					.stringToScope("'$path' of project $project")
			result.add(path to scope)
		}
	} catch (e:InvalidTaskResultException) {
		LOG.warn("Failed to evaluate externalClasspath of project $project", e)
	}

	// Collect internal dependencies (due to ideImport configuration, it collects only generated classpath by default)
	try {
		for (locatedPath in jsonArray(project, task = "internalClasspath?", orNull = true)) {
			val path = locatedPath.locatedPathToClasspathEntry("internalClasspath of $project")
					?: continue
			result.add(path to DependencyScope.COMPILE)
		}
	} catch (e:InvalidTaskResultException) {
		LOG.warn("Failed to evaluate internalClasspath of project $project", e)
	}

	return result
}

private fun JsonValue.arrayToMap():Map<String, JsonValue> {
	val result = HashMap<String, JsonValue>()
	for (child in this) {
		val key = child.getString("key") ?: continue
		result[key] = child.get("value") ?: continue
	}
	return result
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

private val DependencyScope.exported:Boolean
	get() = DependencyScope.TEST != this

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