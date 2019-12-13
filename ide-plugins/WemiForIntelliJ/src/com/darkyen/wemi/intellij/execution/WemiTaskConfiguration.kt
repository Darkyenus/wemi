package com.darkyen.wemi.intellij.execution

import com.darkyen.wemi.intellij.WemiLauncher
import com.darkyen.wemi.intellij.importing.getWemiLauncher
import com.darkyen.wemi.intellij.options.RunConfigOptions
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

    var options = RunConfigOptions()
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
        clone.options = RunConfigOptions().also { options.copyTo(it) }
        clone.beforeRunTasks = ContainerUtil.copyList(beforeRunTasks)
        return clone
    }

    class WemiRunProfileState(private val project:Project, val options: RunConfigOptions, val debugPort:Int) : RunProfileState {

        override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
            val launcher = project.getWemiLauncher() ?: throw ExecutionException("Wemi launcher is missing")
            val debugScheme =
                    if (debugPort <= 0)
                        WemiLauncher.DebugScheme.DISABLED
                    else if (options.debugWemiItself)
                        WemiLauncher.DebugScheme.WEMI_BUILD_SCRIPTS
                    else
                        WemiLauncher.DebugScheme.WEMI_FORKED_PROCESSES
            val process: OSProcessHandler = launcher.createWemiProcessHandler(options, true, true, debugPort, debugScheme, allowBrokenBuildScripts = false, interactive = false, machineReadable = false)
            val terminal = TerminalExecutionConsole(project, process)

            return DefaultExecutionResult(terminal, process, *AnAction.EMPTY_ARRAY)
        }

    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        val options = RunConfigOptions()
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
        options = RunConfigOptions().apply {
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

}
