package com.darkyen.wemi.intellij.external

import com.darkyen.wemi.intellij.WemiLauncherSession
import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.darkyen.wemi.intellij.importing.KotlinCompilerSettingsData
import com.darkyen.wemi.intellij.importing.WEMI_KOTLIN_COMPILER_SETTINGS_KEY
import com.darkyen.wemi.intellij.settings.WemiExecutionSettings
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
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationEvent
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

        var session: WemiLauncherSession? = null
        try {
            synchronized(taskThreadBinding) {
                taskThreadBinding.put(id, Thread.currentThread())
            }

            listener.onStatusChange(ExternalSystemTaskNotificationEvent(id, "Creating session"))
            val launcher = (settings ?: return null).launcher

            var prefixConfigurations = settings.prefixConfigurationsArray()
            if (isPreviewMode) {
                prefixConfigurations += "offline"
            }

            session = if (settings.javaVmExecutable.isBlank()) {
                launcher.createMachineReadableSession(settings.env, settings.isPassParentEnvs, prefixConfigurations)
            } else {
                launcher.createMachineReadableSession(settings.javaVmExecutable, settings.vmOptions, settings.env, settings.isPassParentEnvs, prefixConfigurations)
            }

            // First request on session will be probably waiting for build scripts to compile
            listener.onStatusChange(ExternalSystemTaskNotificationEvent(id, "Loading Wemi build scripts"))
            val wemiVersion = session.string(project = null, task = "#version", includeUserConfigurations = false)
            LOG.info("Wemi version is "+wemiVersion)

            return resolveProjectInfo(id, session, projectPath, settings, listener, wemiVersion)
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

    private fun resolveProjectInfo(id: ExternalSystemTaskId,
                                   session: WemiLauncherSession,
                                   projectPath: String,
                                   settings: WemiExecutionSettings,
                                   listener: ExternalSystemTaskNotificationListener,
                                   wemiVersion:String): DataNode<ProjectData> {
        listener.onStatusChange(ExternalSystemTaskNotificationEvent(id, "Resolving project list"))
        val projects = session.stringArray(project = null, task = "#projects", includeUserConfigurations = false).let {
            projectNames ->
            val projectMap = mutableMapOf<String, WemiProjectData>()
            for (projectName in projectNames) {
                projectMap[projectName] = createWemiProjectData(session, projectName)
            }
            projectMap
        }
        val defaultProject = projects[session.string(null, task = "#defaultProject", includeUserConfigurations = false)]!!

        val projectDataNode = DataNode(ProjectKeys.PROJECT, ProjectData(WemiProjectSystemId, defaultProject.projectName, projectPath, projectPath), null)
        // Java data
        projectDataNode.createChild(JavaProjectData.KEY, defaultProject.javaProjectData())
        // Kotlin data
        val kotlinCompilerData = HashMap<String, String>()
        session.jsonArray(defaultProject.projectName, task = "compilerOptions", configurations = *arrayOf("compilingKotlin")).forEach {
            val key = it.getString("key")
            val value = it.getString("value")
            kotlinCompilerData[key] = value
        }
        projectDataNode.createChild(WEMI_KOTLIN_COMPILER_SETTINGS_KEY, KotlinCompilerSettingsData(kotlinCompilerData))

        val projectModules = mutableMapOf<String, DataNode<ModuleData>>()

        val libraryBank = WemiLibraryDependencyBank()

        for (project in projects.values) {
            listener.onStatusChange(ExternalSystemTaskNotificationEvent(id, "Resolving project "+project.projectName))
            val moduleNode = projectDataNode.createChild(ProjectKeys.MODULE, project.moduleData(projectPath))
            moduleNode.createChild(ProjectKeys.CONTENT_ROOT, project.contentRoot())
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
                    .data(JsonValue.ValueType.array).map { it.getString("project") }
            val running = session.task(project.projectName, "running", task = "projectDependencies")
                    .data(JsonValue.ValueType.array).map { it.getString("project") }
            val testing = session.task(project.projectName, "testing", task = "projectDependencies")
                    .data(JsonValue.ValueType.array).map { it.getString("project") }

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
                moduleDependencyData.isExported = true

                myModule.createChild(ProjectKeys.MODULE_DEPENDENCY, moduleDependencyData)
            }
        }


        // Tasks
        listener.onStatusChange(ExternalSystemTaskNotificationEvent(id, "Resolving tasks"))
        for (task in session.jsonArray(project = null, task = "#keysWithDescription", includeUserConfigurations = false)) {
            val taskName = task.getString("name")
            val taskDescription = task.getString("description")
            val taskData = TaskData(WemiProjectSystemId, taskName, projectPath, taskDescription)
            // (also group can be set here, but we don't have any groups yet)
            projectDataNode.createChild(ProjectKeys.TASK, taskData)
        }

        // Build scripts
        listener.onStatusChange(ExternalSystemTaskNotificationEvent(id, "Resolving build script modules"))
        session.jsonObject(project = null, task = "#buildScript", includeUserConfigurations = false).let { buildScript ->
            // Retrieve info
            val buildFolder = buildScript.getString("buildFolder")
            val projectsUnderBuildScript:Collection<String> = projects.keys
            val name: String
            if (projectsUnderBuildScript.isEmpty() || projectsUnderBuildScript.size > 2) {
                name = "wemi-projects-build"
            } else {
                name = projectsUnderBuildScript.joinToString("-", postfix = "-build")
            }
            val classpath = buildScript.get("classpath").asStringArray().map { File(it) }
            val scriptJar = buildScript.getString("scriptJar")

            // Module Data
            val moduleData = ModuleData(name, WemiProjectSystemId, StdModuleTypes.JAVA.id, name, buildFolder, projectPath)
            moduleData.isInheritProjectCompileOutputPath = false
            moduleData.setCompileOutputPath(ExternalSystemSourceType.SOURCE, File(scriptJar).parent)
            moduleData.targetCompatibility = "1.8" // Using kotlin, but just for some sensible defaults
            moduleData.sourceCompatibility = "1.8" // Using kotlin, but just for some sensible defaults

            // Module Node
            val moduleNode = projectDataNode.createChild(ProjectKeys.MODULE, moduleData)

            val contentRoot = ContentRootData(WemiProjectSystemId, buildFolder)
            contentRoot.storePath(ExternalSystemSourceType.SOURCE, buildFolder)
            contentRoot.storePath(ExternalSystemSourceType.EXCLUDED, "$buildFolder/cache")
            contentRoot.storePath(ExternalSystemSourceType.EXCLUDED, "$buildFolder/logs")
            contentRoot.storePath(ExternalSystemSourceType.EXCLUDED, "$buildFolder/artifacts")
            moduleNode.createChild(ProjectKeys.CONTENT_ROOT, contentRoot)

            // Dependencies
            val unresolved = classpath.any { !it.exists() }
            val libraryData = LibraryData(WemiProjectSystemId, name+" Classpath", unresolved)
            // Classpath
            for (artifact in classpath) {
                libraryData.addPath(LibraryPathType.BINARY, artifact.path)
            }
            // Sources and docs
            if (projectsUnderBuildScript.isNotEmpty()) {
                // We can download sources and docs only through other projects
                //TODO This is slightly ugly
                val projectToUseToDownloadSources = projectsUnderBuildScript.iterator().next()

                if (settings.downloadSources) {
                    for ((_, dependencies) in createWemiProjectDependencies(session, projectToUseToDownloadSources, "wemiBuildScript", "retrievingSources")) {
                        for (dependency in dependencies) {
                            libraryData.addPath(LibraryPathType.SOURCE, dependency.artifact.path)
                        }
                    }
                }
                if (settings.downloadDocs) {
                    for ((_, dependencies) in createWemiProjectDependencies(session, projectToUseToDownloadSources, "wemiBuildScript", "retrievingDocs")) {
                        for (dependency in dependencies) {
                            libraryData.addPath(LibraryPathType.DOC, dependency.artifact.path)
                        }
                    }
                }
            }
            // Wemi Sources (included in the jar)
            try {
                val wemiLauncherFile = File(settings.wemiLauncher)
                ZipFile(wemiLauncherFile).use { file ->
                    val sourceEntry = file.getEntry("source.zip")
                    if (sourceEntry != null) {
                        val sb = StringBuilder()
                        sb.append("wemi-extracted-sources-").append(wemiVersion)
                        if (wemiVersion.endsWith("-SNAPSHOT")) {
                            sb.append('-').append(wemiLauncherFile.lastModified())
                        }
                        sb.append(".zip")

                        val sourcesZip = Paths.get(scriptJar).parent.resolve(sb.toString())
                        if (!Files.exists(sourcesZip)) {
                            Files.newOutputStream(sourcesZip).use { out ->
                                file.getInputStream(sourceEntry).use { ins ->
                                    val buffer = ByteArray(Math.min(4096L, sourceEntry.size).toInt())
                                    while (true) {
                                        val read = ins.read(buffer)
                                        if (read == -1) {
                                            break
                                        }
                                        out.write(buffer, 0, read)
                                    }
                                }
                            }
                        }

                        libraryData.addPath(LibraryPathType.SOURCE, sourcesZip.toAbsolutePath().toString())
                    }
                }
            } catch (e:Exception) {
                LOG.warn("Failed to retrieve Wemi sources", e)
            }


            moduleNode.createChild(ProjectKeys.LIBRARY, libraryData)

            val libraryDependencyData = LibraryDependencyData(moduleData, libraryData, LibraryLevel.MODULE)
            libraryDependencyData.scope = DependencyScope.COMPILE
            libraryDependencyData.isExported = false
            moduleNode.createChild(ProjectKeys.LIBRARY_DEPENDENCY, libraryDependencyData)
        }

        // Apply library bank to create libraries
        listener.onStatusChange(ExternalSystemTaskNotificationEvent(id, "Setting dependencies"))
        libraryBank.apply(projectDataNode, projectModules)

        listener.onStatusChange(ExternalSystemTaskNotificationEvent(id, "Resolved!"))
        return projectDataNode
    }

    private fun createWemiProjectData(session: WemiLauncherSession, projectName:String):WemiProjectData {
        val javacOptions = session.jsonArray(projectName, task = "compilerOptions", configurations = *arrayOf("compilingJava"))
        return WemiProjectData(
                projectName,
                session.string(projectName, task = "projectName"),
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
                session.string(projectName, configurations = *arrayOf("testing"), task = "outputClassesDirectory"),
                session.stringArray(projectName, task = "sourceRoots"),
                session.stringArray(projectName, task = "resourceRoots"),
                session.stringArray(projectName, "testing", task = "sourceRoots"),
                session.stringArray(projectName, "testing", task = "resourceRoots")
                )
    }

    private fun createWemiProjectDependencies(session: WemiLauncherSession, projectName: String, vararg config: String): Map<String, List<WemiLibraryDependency>> {
        val dependencies = LinkedHashMap<String, MutableList<WemiLibraryDependency>>()

        fun add(id:String, dep:WemiLibraryDependency) {
            dependencies.getOrPut(id) { mutableListOf() }.add(dep)
        }

        try {
            session.jsonObject(projectName, *config, task = "resolvedLibraryDependencies").get("value").forEach {
                val projectId = it.get("key")
                val artifact = it.get("value")?.get("data")?.find { it.getString("name") == "artifactFile" }?.getString("value") ?: return@forEach

                val group = projectId.getString("group")
                val name = projectId.getString("name")
                val version = projectId.getString("version")
                val classifier = projectId.get("attributes").find { it.getString("key") == "m2-classifier" }?.getString("value") ?: ""

                add("$group:$name:$version",
                        WemiLibraryDependency(
                                "$group:$name:$version${if (classifier.isBlank()) "" else "-$classifier"}",
                                File(artifact).absoluteFile))
            }

            session.jsonArray(projectName, *config, task = "unmanagedDependencies").forEach {
                val file = File(it.getString(if (it.getBoolean("simple")) "file" else "root")).absoluteFile

                add(file.absolutePath, WemiLibraryDependency(file.name, file))
            }
        } catch (e:WemiSessionException) {
            LOG.warn("Failed to resolve dependencies for "+config.joinToString(""){it+":"}+projectName, e)
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
                    val projectModule = modules[projectName]!!
                    val libraryDependencyData = LibraryDependencyData(projectModule.data, libraryData, LibraryLevel.PROJECT)
                    libraryDependencyData.scope = dependencyScope
                    libraryDependencyData.isExported = true
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
            val classOutputTesting:String,
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

        fun WemiLauncherSession.stringArray(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true):Array<String> {
            return task(project = project, configurations = *configurations, task = task, includeUserConfigurations = includeUserConfigurations)
                    .data(JsonValue.ValueType.array)
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

        fun WemiLauncherSession.jsonObject(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true):JsonValue {
            return task(project = project, configurations = *configurations, task = task, includeUserConfigurations = includeUserConfigurations)
                    .data(JsonValue.ValueType.`object`)
        }

        fun WemiLauncherSession.jsonArray(project:String?, vararg configurations:String, task:String, includeUserConfigurations:Boolean = true):JsonValue {
            return task(project = project, configurations = *configurations, task = task, includeUserConfigurations = includeUserConfigurations)
                    .data(JsonValue.ValueType.array)
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
    }

    @Suppress("CanBeParameter")
    private class WemiSessionException(val result:WemiLauncherSession.Companion.Result,
                                       val expectedType:JsonValue.ValueType?):Exception(
            "Got $result but expected $expectedType"
    )
}