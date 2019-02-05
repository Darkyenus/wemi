package com.darkyen.wemi.intellij.external

import com.darkyen.wemi.intellij.*
import com.darkyen.wemi.intellij.importing.KotlinCompilerSettingsData
import com.darkyen.wemi.intellij.importing.WEMI_KOTLIN_COMPILER_SETTINGS_KEY
import com.darkyen.wemi.intellij.importing.WEMI_MODULE_DATA_KEY
import com.darkyen.wemi.intellij.importing.WemiModuleComponentData
import com.darkyen.wemi.intellij.module.WemiModuleType
import com.darkyen.wemi.intellij.settings.WemiExecutionSettings
import com.darkyen.wemi.intellij.util.Version
import com.darkyen.wemi.intellij.util.deleteRecursively
import com.darkyen.wemi.intellij.util.digestToHexString
import com.darkyen.wemi.intellij.util.update
import com.esotericsoftware.jsonbeans.JsonValue
import com.intellij.externalSystem.JavaProjectData
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.*
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.externalSystem.service.project.ExternalSystemProjectResolver
import com.intellij.openapi.module.StdModuleTypes
import com.intellij.openapi.roots.DependencyScope
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipFile

/**
 * May be run in a different process from the rest of the IDE!
 */
class WemiProjectResolver : ExternalSystemProjectResolver<WemiExecutionSettings> {

    private val taskThreadBinding = mutableMapOf<ExternalSystemTaskId, Thread>()

    /**
     * Builds object-level representation of the external system config file contained at the given path.
     *
     * @param id            id of the current 'resolve project info' task
     * @param projectPath   absolute path to the target external system config file
     * @param isPreviewMode Indicates, that an implementation can not provide/resolve any external dependencies.
     *                      Only project dependencies and local file dependencies may included on the modules' classpath.
     *                      And should not include any 'heavy' tasks like not trivial code generations.
     *                      It is supposed to be fast.
     * @param settings      settings to use for the project resolving;
     *                      {@code null} as indication that no specific settings are required
     * @param listener      callback to be notified about the execution. Call only onStatusChange and onTaskOutput, rest is ignored!
     * @return object-level representation of the target external system project;
     * {@code null} if it's not possible to resolve the project due to the objective reasons
     * @throws ExternalSystemException  in case when unexpected exception occurs during project info construction
     * @throws java.lang.IllegalArgumentException if given path is invalid
     * @throws java.lang.IllegalStateException    if it's not possible to resolve target project info
     */
    @Throws(ExternalSystemException::class, IllegalArgumentException::class, IllegalStateException::class)
    override fun resolveProjectInfo(id: ExternalSystemTaskId,
                                    projectPath: String,
                                    isPreviewMode: Boolean,
                                    settings: WemiExecutionSettings?,
                                    listener: ExternalSystemTaskNotificationListener): DataNode<ProjectData>? {
        var beingThrown:Throwable? = null
        val tracker = ExternalStatusTracker(id, listener)

        var session: WemiLauncherSession? = null
        try {
            synchronized(taskThreadBinding) {
                taskThreadBinding.put(id, Thread.currentThread())
            }

            tracker.stage = "Creating session"
            val launcher = (settings ?: return null).launcher
                    ?: throw IllegalStateException("Wemi launcher is missing")

            var prefixConfigurations = settings.prefixConfigurationsArray()
            if (isPreviewMode) {
                prefixConfigurations += "offline"
            }

            val javaExecutable = if (settings.javaVmExecutable.isBlank()) "java" else settings.javaVmExecutable
            session = launcher.createMachineReadableResolverSession(
                    javaExecutable, settings.vmOptions, settings.env, settings.isPassParentEnvs,
                    prefixConfigurations, settings.allowBrokenBuildScripts, tracker)

            // First request on session will be probably waiting for build scripts to compile
            tracker.stage = "Loading build scripts"
            val wemiVersion = session.string(project = null, task = "#version", includeUserConfigurations = false)
            LOG.info("Wemi version is $wemiVersion")
            session.wemiVersion = Version(wemiVersion)

            @Suppress("UnnecessaryVariable") // Creates place for breakpoint
            val resolvedProject = resolveProjectInfo(session, projectPath, settings, tracker)
            return resolvedProject
        } catch (se:WemiSessionException) {
            LOG.warn("WemiSessionException encountered while resolving", se)
            throw ExternalSystemException(se.result.output).apply {
                // Real exception is added as suppressed instead of cause,
                // because IDE would show its message to the user, but we want to show only se.result.output
                addSuppressed(se)
                beingThrown = this
            }
        } catch (e:ThreadDeath) {
            LOG.debug("Thread stopped", e)
            return null
        } catch (e:Throwable) {
            LOG.warn("Error while resolving", e)
            throw ExternalSystemException(e).apply { beingThrown = this }
        } finally {
            try {
                synchronized(taskThreadBinding) {
                    taskThreadBinding.remove(id)
                }
                session?.done()
            } catch (e:Throwable) {
                beingThrown?.addSuppressed(e)
            }
        }
    }

    private fun resolveProjectInfo(session: WemiLauncherSession,
                                   projectPath: String,
                                   settings: WemiExecutionSettings,
                                   tracker: ExternalStatusTracker): DataNode<ProjectData> {
        tracker.stage = "Resolving project list"
        val projects = session.stringArray(project = null, task = "#projects", includeUserConfigurations = false).let {
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
        val defaultProject = projects[session.stringOrNull(null, task = "#defaultProject", includeUserConfigurations = false)] ?: run {
            // If there is no default project, pick any
            if (projects.isEmpty()) {
                null
            } else {
                projects.iterator().next().value
            }
        }

        val projectDataNode = DataNode(ProjectKeys.PROJECT, ProjectData(WemiProjectSystemId, defaultProject?.projectName ?: File(projectPath).name, projectPath, projectPath), null)

        // Pick sensible defaults from default project
        if (defaultProject != null) {
            // Java data
            projectDataNode.createChild(JavaProjectData.KEY, defaultProject.javaProjectData())

            // Kotlin data
            val kotlinCompilerData = HashMap<String, JsonValue>()
            session.jsonArray(defaultProject.projectName, task = "compilerOptions", configurations = *arrayOf("compilingKotlin")).forEach {
                val key = it.getString("key")!!
                val value = it.get("value")
                kotlinCompilerData[key] = value
            }
            projectDataNode.createChild(WEMI_KOTLIN_COMPILER_SETTINGS_KEY, KotlinCompilerSettingsData(kotlinCompilerData))
        }

        val projectModules = mutableMapOf<String, DataNode<ModuleData>>()

        val libraryBank = WemiLibraryDependencyBank()

        for (project in projects.values) {
            tracker.stage = "Resolving project "+project.projectName
            val moduleNode = projectDataNode.createChild(ProjectKeys.MODULE, project.moduleData(projectPath))
            moduleNode.createChild(ProjectKeys.CONTENT_ROOT, project.contentRoot())
            moduleNode.createChild(WEMI_MODULE_DATA_KEY, WemiModuleComponentData(WemiModuleType.PROJECT))
            projectModules[project.projectName] = moduleNode

            // Collect dependencies
            val compileDependencies = createWemiProjectCombinedDependencies(session, project.projectName, "compiling", settings.downloadDocs, settings.downloadSources)
            val runtimeDependencies = createWemiProjectCombinedDependencies(session, project.projectName, "running", settings.downloadDocs, settings.downloadSources)
            val testDependencies = createWemiProjectCombinedDependencies(session, project.projectName, "testing", settings.downloadDocs, settings.downloadSources)

            for ((hash, dep) in compileDependencies) {
                val inRuntime = runtimeDependencies.remove(hash) != null
                val inTest = testDependencies.remove(hash) != null

                libraryBank.projectUsesLibrary(project.projectName, dep, true, inRuntime, inTest)
            }

            for ((hash, dep) in runtimeDependencies) {
                val inTest = testDependencies.remove(hash) != null
                libraryBank.projectUsesLibrary(project.projectName, dep, false, true, inTest)
            }

            for ((_, dep) in testDependencies) {
                libraryBank.projectUsesLibrary(project.projectName, dep, false, false, true)
            }
        }

        // Inter-project dependencies
        for (project in projects.values) {
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

            var order = 0
            for (depProjectName in all) {
                if (depProjectName == project.projectName) {
                    continue
                }
                val inCompiling = compiling.contains(depProjectName)
                val inRunning = running.contains(depProjectName)
                val inTesting = testing.contains(depProjectName)

                val scope = toDependencyScope(inCompiling, inRunning, inTesting, depProjectName)
                val myModule = projectModules[project.projectName]!!
                val moduleDependencyData = ModuleDependencyData(
                        myModule.data,
                        projectModules[depProjectName]!!.data
                )
                moduleDependencyData.scope = scope
                moduleDependencyData.order = order++
                moduleDependencyData.isExported = scope.exported

                myModule.createChild(ProjectKeys.MODULE_DEPENDENCY, moduleDependencyData)
            }
        }


        // Tasks
        tracker.stage = "Resolving tasks"
        for (task in session.jsonArray(project = null, task = "#keysWithDescription", includeUserConfigurations = false)) {
            val taskName = task.getString("name")!!
            val taskDescription = task.getString("description")!!
            val taskData = TaskData(WemiProjectSystemId, taskName, projectPath, taskDescription)
            // (also group can be set here, but we don't have any groups yet)
            projectDataNode.createChild(ProjectKeys.TASK, taskData)
        }

        // Build scripts
        tracker.stage = "Resolving build script module"
        run {
            val buildFolder = session.string(project = WemiBuildScriptProjectName, task = "projectRoot", includeUserConfigurations = false)

            val classpath = session.jsonArray(project = WemiBuildScriptProjectName, task = "externalClasspath").map { it.locatedFileOrPathClasspathEntry() }
            val cacheFolder = session.string(project = WemiBuildScriptProjectName, task = "cacheDirectory")
            val libFolderPath = Paths.get(cacheFolder).resolve("wemi-libs-ide")
            // Ensure that it is empty so that we don't accumulate old libs
            libFolderPath.deleteRecursively()
            Files.createDirectories(libFolderPath)

            // Module Data
            val moduleData = ModuleData(WemiBuildScriptProjectName, WemiProjectSystemId, StdModuleTypes.JAVA.id, WemiBuildScriptProjectName, buildFolder, projectPath)
            moduleData.isInheritProjectCompileOutputPath = false
            moduleData.setCompileOutputPath(ExternalSystemSourceType.SOURCE, cacheFolder)
            moduleData.targetCompatibility = "1.8" // Using kotlin, but just for some sensible defaults
            moduleData.sourceCompatibility = "1.8" // Using kotlin, but just for some sensible defaults

            // Module Node
            val moduleNode = projectDataNode.createChild(ProjectKeys.MODULE, moduleData)
            moduleNode.createChild(WEMI_MODULE_DATA_KEY, WemiModuleComponentData(WemiModuleType.BUILD_SCRIPT))

            val contentRoot = ContentRootData(WemiProjectSystemId, buildFolder)
            contentRoot.storePath(ExternalSystemSourceType.SOURCE, buildFolder)
            contentRoot.storePath(ExternalSystemSourceType.EXCLUDED, "$buildFolder/cache")
            contentRoot.storePath(ExternalSystemSourceType.EXCLUDED, "$buildFolder/logs")
            contentRoot.storePath(ExternalSystemSourceType.EXCLUDED, "$buildFolder/artifacts")
            moduleNode.createChild(ProjectKeys.CONTENT_ROOT, contentRoot)

            // Dependencies
            val unresolved = classpath.any { !it.exists() }
            val libraryData = LibraryData(WemiProjectSystemId, "$WemiBuildScriptProjectName Classpath", unresolved)
            // Classpath
            for (artifact in classpath) {
                var affectiveArtifact = artifact.toPath()
                if (artifact.name.equals(WemiLauncherFileName, true)) {
                    // Copy it, because IntelliJ does not recognize files without extension
                    val link = libFolderPath.resolve("wemi.jar")
                    try {
                        Files.createSymbolicLink(link, affectiveArtifact)
                    } catch (e:Exception) {
                        LOG.info("Failed to soft link wemi, copying instead", e)
                        Files.copy(affectiveArtifact, link)
                    }
                    affectiveArtifact = link
                }
                libraryData.addPath(LibraryPathType.BINARY, affectiveArtifact.toAbsolutePath().toString())
            }
            // Sources and docs
            if (settings.downloadSources) {
                for ((_, dependencies) in createWemiProjectDependencies(session, WemiBuildScriptProjectName, "retrievingSources")) {
                    for (dependency in dependencies) {
                        libraryData.addPath(LibraryPathType.SOURCE, dependency.artifact.path)
                    }
                }
            }
            if (settings.downloadDocs) {
                for ((_, dependencies) in createWemiProjectDependencies(session, WemiBuildScriptProjectName, "retrievingDocs")) {
                    for (dependency in dependencies) {
                        libraryData.addPath(LibraryPathType.DOC, dependency.artifact.path)
                    }
                }
            }
            // Wemi Sources (included in the jar)
            if (settings.wemiLauncher != null) {
                try {
                    val wemiLauncherFile = File(settings.wemiLauncher)
                    ZipFile(wemiLauncherFile).use { file ->
                        val sourceEntry = file.getEntry("source.zip")
                        if (sourceEntry != null) {
                            val sourcesPath = libFolderPath.resolve("wemi-source.jar")

                            // Extract sources
                            file.getInputStream(sourceEntry).use { ins ->
                                Files.copy(ins, sourcesPath)
                            }

                            libraryData.addPath(LibraryPathType.SOURCE, sourcesPath.toAbsolutePath().toString())
                        }
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to retrieve Wemi sources", e)
                }
            }


            moduleNode.createChild(ProjectKeys.LIBRARY, libraryData)

            val libraryDependencyData = LibraryDependencyData(moduleData, libraryData, LibraryLevel.MODULE)
            libraryDependencyData.scope = DependencyScope.COMPILE
            libraryDependencyData.isExported = false
            moduleNode.createChild(ProjectKeys.LIBRARY_DEPENDENCY, libraryDependencyData)
        }

        // Apply library bank to create libraries
        tracker.stage = "Setting dependencies"
        libraryBank.apply(projectDataNode, projectModules)

        tracker.stage = "Resolved!"
        return projectDataNode
    }

    private fun createWemiProjectData(session: WemiLauncherSession, projectName:String):WemiProjectData {
        val javacOptions = session.jsonArray(projectName, task = "compilerOptions", configurations = *arrayOf("compilingJava"))
        val sourceRoots: Array<String>
        val resourceRoots: Array<String>
        val sourceRootsTesting: Array<String>
        val resourceRootsTesting: Array<String>

        if (session.wemiVersion < Version.WEMI_0_7) {
            sourceRoots = session.stringArray(projectName, task = "sourceRoots")
            resourceRoots = session.stringArray(projectName, task = "resourceRoots")
            sourceRootsTesting = session.stringArray(projectName, "testing", task = "sourceRoots")
            resourceRootsTesting = session.stringArray(projectName, "testing", task = "resourceRoots")
        } else {
            sourceRoots = session.jsonArray(projectName, task = "sources", orNull = true).fileSetRoots()
            resourceRoots = session.jsonArray(projectName, task = "resources", orNull = true).fileSetRoots()
            sourceRootsTesting = session.jsonArray(projectName, "testing", task = "sources", orNull = true).fileSetRoots()
            resourceRootsTesting = session.jsonArray(projectName, "testing", task = "resources", orNull = true).fileSetRoots()
        }

        return WemiProjectData(
                projectName,
                session.stringOrNull(projectName, task = "projectName?") ?: projectName,
                session.stringOrNull(projectName, task = "projectGroup?"),
                session.stringOrNull(projectName, task = "projectVersion?"),
                session.string(projectName, task = "projectRoot"),
                javacOptions.let {
                    it.mapGet("sourceVersion")?.asString() ?: ""
                },
                javacOptions.let {
                    it.mapGet("targetVersion")?.asString() ?: ""
                },
                session.string(projectName, task = "outputClassesDirectory"),
                session.stringOrNull(projectName, configurations = *arrayOf("testing"), task = "outputClassesDirectory?"),
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
            session.jsonObject(projectName, *config, task = "resolvedLibraryDependencies?", orNull = true).get("value")?.forEach { resolvedValue ->
                val projectId = resolvedValue.get("key")
                val artifact = resolvedValue.get("value")?.get("data")?.find { it.getString("name") == "artifactFile" }?.get("value")?.asString() ?: return@forEach

                val group = projectId.getString("group")!!
                val name = projectId.getString("name")!!
                val version = projectId.getString("version")!!
                val classifier = projectId.get("attributes").find { it.getString("key") == "m2-classifier" }?.get("value")?.asString() ?: ""

                add("$group:$name:$version",
                        WemiLibraryDependency(
                                "$group:$name:$version${if (classifier.isBlank()) "" else "-$classifier"}",
                                File(artifact).absoluteFile))
            }

            session.jsonArray(projectName, *config, task = "unmanagedDependencies?", orNull = true).forEach {
                val file = it.locatedFileOrPathClasspathEntry()

                add(file.absolutePath, WemiLibraryDependency(file.name, file))
            }
        } catch (e:WemiSessionException) {
            LOG.warn("Failed to resolve dependencies for "+config.joinToString(""){ "$it:" }+projectName, e)
        }

        return dependencies
    }

    private fun createWemiProjectCombinedDependencies(session: WemiLauncherSession, projectName: String, stage:String, withDocs:Boolean, withSources:Boolean):MutableMap<String, WemiLibraryCombinedDependency> {
        val artifacts = createWemiProjectDependencies(session, projectName, stage)
        val sources = if (withSources) createWemiProjectDependencies(session, projectName, stage, "retrievingSources") else emptyMap()
        val docs = if (withDocs) createWemiProjectDependencies(session, projectName, stage, "retrievingDocs") else emptyMap()

        val combined = mutableMapOf<String, WemiLibraryCombinedDependency>()
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

    private class WemiLibraryDependency(val name:String, val artifact:File)

    private class WemiLibraryCombinedDependency(val name:String,
                                                artifactFiles:List<File>,
                                                sourceArtifactFiles:List<File>,
                                                docArtifactFiles:List<File>
                                                ) {

        val artifacts = artifactFiles.sortedBy { it.absolutePath }
        val sourceArtifacts = sourceArtifactFiles.sortedBy { it.absolutePath }
        val docArtifacts = docArtifactFiles.sortedBy { it.absolutePath }

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
                = mutableMapOf<String, // Hash
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

        fun apply(project:DataNode<ProjectData>, modules:Map<String, DataNode<ModuleData>>) {
            val projectToDependenciesMap = HashMap<DataNode<ModuleData>, ArrayList<LibraryDependencyData>>()

            for ((library, projects) in hashToDependencyAndProjects.values) {
                val unresolved = library.artifacts.any { !it.exists() }

                val libraryData = LibraryData(WemiProjectSystemId, library.name, unresolved)
                for (artifact in library.artifacts) {
                    libraryData.addPath(LibraryPathType.BINARY, artifact.path)
                }
                for (artifact in library.sourceArtifacts) {
                    libraryData.addPath(LibraryPathType.SOURCE, artifact.path)
                }
                for (artifact in library.docArtifacts) {
                    libraryData.addPath(LibraryPathType.DOC, artifact.path)
                }

                project.createChild(ProjectKeys.LIBRARY, libraryData)

                for ((projectName, dependencyScope) in projects) {
                    val projectModule = modules.getValue(projectName)
                    val libraryDependencyData = LibraryDependencyData(projectModule.data, libraryData, LibraryLevel.PROJECT)
                    libraryDependencyData.scope = dependencyScope
                    libraryDependencyData.isExported = dependencyScope.exported

                    projectToDependenciesMap.getOrPut(projectModule) { ArrayList() }.add(libraryDependencyData)
                }
            }

            for ((projectModule, dependencies) in projectToDependenciesMap) {
                // Sort dependencies
                dependencies.sortWith(DependencyComparator)

                for (libraryDependencyData in dependencies) {
                    projectModule.createChild(ProjectKeys.LIBRARY_DEPENDENCY, libraryDependencyData)
                }
            }
        }
    }

    private class WemiProjectData(
            val projectName:String,
            val artifactName:String,
            val artifactGroup:String?,
            val artifactVersion:String?,
            val rootPath:String,
            val javaSourceVersion:String,
            val javaTargetVersion:String,
            val classOutput:String,
            val classOutputTesting: String?,
            sourceRoots:Array<String>,
            resourceRoots:Array<String>,
            sourceRootsTesting:Array<String>,
            resourceRootsTesting:Array<String>
    ) {

        val sourceRoots:Set<String> = sourceRoots.toSet()
        val resourceRoots:Set<String> = resourceRoots.toSet()
        val sourceRootsTesting:Set<String> = sourceRootsTesting.toMutableSet().apply { removeAll(sourceRoots) }
        val resourceRootsTesting:Set<String> = resourceRootsTesting.toMutableSet().apply { removeAll(resourceRoots) }

        fun javaProjectData():JavaProjectData {
            val data = JavaProjectData(WemiProjectSystemId, classOutput)
            data.setLanguageLevel(javaSourceVersion)
            data.setJdkVersion(javaSourceVersion)
            return data
        }

        fun moduleData(projectPath: String):ModuleData {
            val data = ModuleData(projectName, WemiProjectSystemId, StdModuleTypes.JAVA.id, projectName, rootPath, projectPath)
            data.isInheritProjectCompileOutputPath = false
            data.setCompileOutputPath(ExternalSystemSourceType.SOURCE, classOutput)
            data.setCompileOutputPath(ExternalSystemSourceType.TEST, classOutputTesting)
            data.sourceCompatibility = javaSourceVersion
            data.targetCompatibility = javaTargetVersion
            data.externalName = artifactName
            if (artifactGroup != null) {
                data.group = artifactGroup
            }
            if (artifactVersion != null) {
                data.version = artifactVersion
            }
            return data
        }

        private fun ContentRootData.add(type:ExternalSystemSourceType, paths:Array<String>) {
            for (path in paths) {
                storePath(type, path)
            }
        }

        fun contentRoot():ContentRootData {
            val contentRoot = ContentRootData(WemiProjectSystemId, rootPath)
            contentRoot.add(ExternalSystemSourceType.SOURCE, sourceRoots.toTypedArray())
            contentRoot.add(ExternalSystemSourceType.RESOURCE, resourceRoots.toTypedArray())
            contentRoot.add(ExternalSystemSourceType.TEST, sourceRootsTesting.toTypedArray())
            contentRoot.add(ExternalSystemSourceType.TEST_RESOURCE, resourceRootsTesting.toTypedArray())
            return contentRoot
        }
    }

    /**
     * @param taskId   id of the 'resolve project info' task
     * @param listener callback to be notified about the cancellation
     * @return true if the task execution was successfully stopped, false otherwise or if target external system does not support the task cancellation
     */
    override fun cancelTask(taskId: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean {
        var removed: Thread? = null
        synchronized(taskThreadBinding) {
            removed = taskThreadBinding.remove(taskId)
        }

        val thread = removed ?: return false

        listener.onTaskOutput(taskId, "Wemi resolution task is being cancelled", false)
        listener.beforeCancel(taskId)

        // This is a hack, but will have to do for now
        @Suppress("DEPRECATION")
        thread.stop()
        return true
    }

    private companion object {

        private val LOG = Logger.getInstance(WemiProjectResolver::class.java)

        fun WemiLauncherSession.stringArray(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true, orNull:Boolean = false):Array<String> {
            return task(project = project, configurations = *configurations, task = task, includeUserConfigurations = includeUserConfigurations)
                    .data(JsonValue.ValueType.array, orNull)
                    .asStringArray()
        }

        fun WemiLauncherSession.string(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true):String {
            return task(project = project, configurations = *configurations, task = task, includeUserConfigurations = includeUserConfigurations)
                    .data(JsonValue.ValueType.stringValue)
                    .asString()
        }

        fun WemiLauncherSession.stringOrNull(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true):String? {
            return task(project = project, configurations = *configurations, task = task, includeUserConfigurations = includeUserConfigurations)
                    .data(JsonValue.ValueType.stringValue, orNull = true)
                    .asString()
        }

        fun WemiLauncherSession.jsonObject(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true, orNull:Boolean = false):JsonValue {
            return task(project = project, configurations = *configurations, task = task, includeUserConfigurations = includeUserConfigurations)
                    .data(JsonValue.ValueType.`object`, orNull)
        }

        fun WemiLauncherSession.jsonArray(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true, orNull:Boolean = false):JsonValue {
            return task(project = project, configurations = *configurations, task = task, includeUserConfigurations = includeUserConfigurations)
                    .data(JsonValue.ValueType.array, orNull)
        }

        fun JsonValue.locatedFileOrPathClasspathEntry():File {
            return if (this.type() == JsonValue.ValueType.stringValue) {
                return File(asString())
            } else {
                File(get("root")?.asString() ?: getString("file")!!)
            }.absoluteFile
        }

        fun JsonValue.fileSetRoots():Array<String> {
            if (this.isNull) {
                return emptyArray() // limitation of JsonWritable, empty FileSet is just null
            }

            var child = this.child
            return Array(size) {
                val root = child.getString("root")
                child = child.next
                root
            }
        }

        fun JsonValue.mapGet(key:String):JsonValue? {
            for (child in this) {
                if (child.getString("key") == key) {
                    return child.get("value")
                }
            }
            return null
        }

        fun WemiLauncherSession.Companion.Result.data(type:JsonValue.ValueType? = null, orNull:Boolean = false):JsonValue {
            if (this.data == null || this.status != WemiLauncherSession.Companion.ResultStatus.SUCCESS) {
                throw WemiSessionException(this, type)
            }
            val valueType = this.data.type()
            val typeIsValid = type == null || (valueType == type) || (orNull && valueType == JsonValue.ValueType.nullValue)
            if (!typeIsValid) {
                throw WemiSessionException(this, type)
            }
            return data
        }

        private fun toDependencyScope(inCompile:Boolean, inRuntime:Boolean, inTest:Boolean, dependencyLogInfo:Any):DependencyScope {
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

        private fun artifactCriteriaPriority(dependency:LibraryDependencyData):Int {
            val target = dependency.target
            when {
                target.getPaths(LibraryPathType.SOURCE).isNotEmpty() -> return 0
                target.getPaths(LibraryPathType.DOC).isNotEmpty() -> return 1
                else -> return 2
            }
        }

        /**
         * Sorts dependencies.
         * Criteria:
         * 1. Dependencies with sources first, then those with documentation, then rest
         *      (this is done because IntelliJ then tries to find sources/documentation in random jars
         *      that may not have them and overlooks those that do have them)
         * 2. Otherwise stable
         */
        private val DependencyComparator:Comparator<LibraryDependencyData> = Comparator { a, b ->
            val a1 = artifactCriteriaPriority(a)
            val b1 = artifactCriteriaPriority(b)
             a1.compareTo(b1)
        }
    }

    @Suppress("CanBeParameter")
    private class WemiSessionException(val result:WemiLauncherSession.Companion.Result,
                                       val expectedType:JsonValue.ValueType?):Exception(
            "Got $result but expected $expectedType"
    )
}