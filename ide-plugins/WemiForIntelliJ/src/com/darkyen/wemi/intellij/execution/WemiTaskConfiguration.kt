package com.darkyen.wemi.intellij.execution

import com.darkyen.wemi.intellij.WemiLauncher
import com.darkyen.wemi.intellij.findWemiLauncher
import com.darkyen.wemi.intellij.ui.BooleanPropertyEditor
import com.darkyen.wemi.intellij.ui.CommandArgumentEditor
import com.darkyen.wemi.intellij.ui.EnvironmentVariablesEditor
import com.darkyen.wemi.intellij.ui.PropertyEditorPanel
import com.darkyen.wemi.intellij.ui.TaskListPropertyEditor
import com.darkyen.wemi.intellij.ui.WemiJavaExecutableEditor
import com.darkyen.wemi.intellij.util.BooleanXmlSerializer
import com.darkyen.wemi.intellij.util.ListOfStringArraysXmlSerializer
import com.darkyen.wemi.intellij.util.ListOfStringXmlSerializer
import com.darkyen.wemi.intellij.util.MapStringStringXmlSerializer
import com.darkyen.wemi.intellij.util.StringXmlSerializer
import com.darkyen.wemi.intellij.util.XmlSerializable
import com.intellij.execution.BeforeRunTask
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionBundle
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.JavaExecutionUtil
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.LocatableConfiguration
import com.intellij.execution.configurations.RefactoringListenerProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.SettingsEditorGroup
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.terminal.TerminalExecutionConsole
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.net.NetUtils
import org.jdom.Element
import org.jetbrains.kotlin.idea.run.KotlinRunConfigurationProducer
import org.jetbrains.kotlin.psi.KtDeclarationContainer
import java.util.*
import javax.swing.Icon

/** Used when creating Wemi task run to run as IDE Configuration */
class WemiTaskConfiguration(
        private val project:Project,
        private val factory:ConfigurationFactory,
        private var name:String)
    : RunConfiguration, LocatableConfiguration, RefactoringListenerProvider {

    var options = RunOptions()
        private set
    private var beforeRunTasks:MutableList<BeforeRunTask<*>> = Collections.emptyList()

    override fun getName(): String = name
    override fun isGeneratedName(): Boolean = options.generatedName

    override fun suggestedName(): String = options.shortTaskSummary()

    override fun setName(name: String?) {
        this.name = name ?: return
        options.generatedName = false
    }

    fun useSuggestedName() {
        this.name = suggestedName()
        options.generatedName = true
    }

    override fun getProject(): Project = project

    override fun getFactory(): ConfigurationFactory = factory

    override fun getType(): ConfigurationType = WemiTaskConfigurationType.INSTANCE

    override fun getIcon(): Icon = factory.icon

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        val group = SettingsEditorGroup<WemiTaskConfiguration>()
        group.addEditor(ExecutionBundle.message("run.configuration.configuration.tab.title"), WemiTaskConfigurationEditor())
        return group
    }

    override fun clone(): WemiTaskConfiguration {
        val clone = WemiTaskConfiguration(project, factory, name)
        clone.options = RunOptions().also { options.copyTo(it) }
        clone.beforeRunTasks = ContainerUtil.copyList(beforeRunTasks)
        return clone
    }

    class WemiRunProfileState(private val project:Project, val options: RunOptions, val debugPort:Int) : RunProfileState {

        override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
            val launcher = findWemiLauncher(project) ?: throw ExecutionException("Wemi launcher is missing")
            val debugScheme =
                    if (debugPort <= 0)
                        WemiLauncher.DebugScheme.DISABLED
                    else if (options.debugWemiItself)
                        WemiLauncher.DebugScheme.WEMI_BUILD_SCRIPTS
                    else
                        WemiLauncher.DebugScheme.WEMI_FORKED_PROCESSES
            val process: OSProcessHandler = launcher.createWemiProcess(options, debugPort, debugScheme, allowBrokenBuildScripts = false, interactive = false, machineReadable = false)
            val terminal = TerminalExecutionConsole(project, process)

            return DefaultExecutionResult(terminal, process, *AnAction.EMPTY_ARRAY)
        }

    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        val options = RunOptions()
        this.options.copyTo(options)
        var debugPort = -1
        if (executor.id == DefaultDebugExecutor.EXECUTOR_ID) {
            debugPort = NetUtils.findAvailableSocketPort()
        }
        return WemiRunProfileState(environment.project, options, debugPort)
    }

    /** If there are any tasks sensitive to class names, fix them on refactor. */
    private fun onClassRenameRefactor(oldName:String, newName:String) {
        if (!options.tasks.any { task -> task.lastIndexOf(oldName) > 0 }) {
            return
        }

        options.tasks = options.tasks.map { task ->
            if (task.lastIndexOf(oldName) <= 0) {
                task
            } else {
                val newTask = task.copyOf()
                for (i in 1 until newTask.size) {
                    if (newTask[i] == oldName) {
                        newTask[i] = newName
                    }
                }
                newTask
            }
        }
    }

    /** Drives [onClassRenameRefactor] */
    override fun getRefactoringElementListener(element: PsiElement?): RefactoringElementListener? {
        return when (element) {
            is PsiClass -> {
                var name = JavaExecutionUtil.getRuntimeQualifiedName(element) ?: return null

                object : RefactoringElementListener {
                    override fun elementRenamed(newElement: PsiElement) {
                        val newName = JavaExecutionUtil.getRuntimeQualifiedName(element) ?: return
                        onClassRenameRefactor(name, newName)
                        name = newName
                    }

                    override fun elementMoved(newElement: PsiElement) {}
                }
            }
            is KtDeclarationContainer -> {
                var name = try { KotlinRunConfigurationProducer.getStartClassFqName(element) } catch (e:Exception) { null } ?: return null

                object : RefactoringElementListener {
                    override fun elementRenamed(newElement: PsiElement) {
                        val newName = try { KotlinRunConfigurationProducer.getStartClassFqName(element) } catch (e:Exception) { null } ?: return
                        onClassRenameRefactor(name, newName)
                        name = newName
                    }

                    override fun elementMoved(newElement: PsiElement) {}
                }
            }
            else -> null
        }
    }

    override fun setAllowRunningInParallel(value: Boolean) {
        options.allowRunningInParallel = value
    }

    override fun isAllowRunningInParallel(): Boolean = options.allowRunningInParallel

    override fun readExternal(element: Element) {
        options = RunOptions().apply {
            readExternal(element)
        }
    }

    override fun writeExternal(element: Element) {
        options.writeExternal(element)
    }

    override fun setBeforeRunTasks(value: MutableList<BeforeRunTask<*>>) {
        beforeRunTasks = value
    }

    override fun getBeforeRunTasks(): MutableList<BeforeRunTask<*>> = beforeRunTasks

    override fun checkConfiguration() {
        if (options.tasks.isEmpty()) {
            throw RuntimeConfigurationException("There should be at least one task")
        }
    }

    open class BaseOptions : XmlSerializable() {

        var tasks:List<Array<String>> = emptyList()
        var environmentVariables: Map<String, String> = emptyMap()
        var passParentEnvironmentVariables:Boolean = true

        var javaExecutable:String = ""
        var javaOptions:List<String> = emptyList()

        init {
            register(BaseOptions::tasks, ListOfStringArraysXmlSerializer::class)
            register(BaseOptions::environmentVariables, MapStringStringXmlSerializer::class)
            register(BaseOptions::passParentEnvironmentVariables, BooleanXmlSerializer::class)
            register(BaseOptions::javaExecutable, StringXmlSerializer::class)
            register(BaseOptions::javaOptions, ListOfStringXmlSerializer::class)
        }

        fun shortTaskSummary():String {
            val tasks = tasks
            if (tasks.isEmpty()) {
                return "No Wemi tasks"
            } else if (tasks.size == 1) {
                return tasks[0].joinToString(" ")
            } else {
                return "${tasks[0].joinToString(" ")}; (${tasks.size - 1} more)"
            }
        }

        fun copyTo(o:BaseOptions) {
            o.tasks = tasks
            o.environmentVariables = environmentVariables
            o.passParentEnvironmentVariables = passParentEnvironmentVariables
            o.javaExecutable = javaExecutable
            o.javaOptions = javaOptions
        }

        open fun createUi(panel: PropertyEditorPanel) {
            panel.edit(TaskListPropertyEditor(this::tasks))
            panel.gap(5)
            panel.edit(EnvironmentVariablesEditor(this::environmentVariables, this::passParentEnvironmentVariables))
            panel.gap(5)
            panel.edit(WemiJavaExecutableEditor(this::javaExecutable))
            panel.edit(CommandArgumentEditor(this::javaOptions))
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BaseOptions) return false

            if (tasks != other.tasks) return false
            if (environmentVariables != other.environmentVariables) return false
            if (passParentEnvironmentVariables != other.passParentEnvironmentVariables) return false
            if (javaExecutable != other.javaExecutable) return false
            if (javaOptions != other.javaOptions) return false

            return true
        }

        override fun hashCode(): Int {
            var result = tasks.hashCode()
            result = 31 * result + environmentVariables.hashCode()
            result = 31 * result + passParentEnvironmentVariables.hashCode()
            result = 31 * result + javaExecutable.hashCode()
            result = 31 * result + javaOptions.hashCode()
            return result
        }
    }

    class RunOptions : BaseOptions() {
        // Bookkeeping settings
        var allowRunningInParallel:Boolean = false
        var generatedName:Boolean = false

        // Run settings
        var debugWemiItself:Boolean = false

        init {
            register(RunOptions::allowRunningInParallel, BooleanXmlSerializer::class)
            register(RunOptions::generatedName, BooleanXmlSerializer::class)
            register(RunOptions::debugWemiItself, BooleanXmlSerializer::class)
        }

        override fun createUi(panel: PropertyEditorPanel) {
            super.createUi(panel)
            panel.edit(BooleanPropertyEditor(this::debugWemiItself, "Debug build scripts", "Debugger will be attached to the Wemi process itself", "Debugger will be attached to any forked process"))
        }

        fun copyTo(o:RunOptions) {
            copyTo(o as BaseOptions)
            o.allowRunningInParallel = allowRunningInParallel
            o.generatedName = generatedName
            o.debugWemiItself = debugWemiItself
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RunOptions) return false
            if (!super.equals(other)) return false

            if (allowRunningInParallel != other.allowRunningInParallel) return false
            if (generatedName != other.generatedName) return false
            if (debugWemiItself != other.debugWemiItself) return false

            return true
        }

        override fun hashCode(): Int {
            var result = super.hashCode()
            result = 31 * result + allowRunningInParallel.hashCode()
            result = 31 * result + generatedName.hashCode()
            result = 31 * result + debugWemiItself.hashCode()
            return result
        }
    }
}
