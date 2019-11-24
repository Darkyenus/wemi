package com.darkyen.wemi.intellij.execution

import com.darkyen.wemi.intellij.execution.WemiProgramRunner
import com.darkyen.wemi.intellij.execution.WemiTaskConfigurationType
import com.darkyen.wemi.intellij.module.WemiModuleComponent
import com.darkyen.wemi.intellij.module.WemiModuleType
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.RunConfigurationBeforeRunProvider
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.task.ModuleBuildTask
import com.intellij.task.ProjectTask
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskNotification
import com.intellij.task.ProjectTaskResult
import com.intellij.task.ProjectTaskRunner

/**
 * Catches requests to build modules/project and handles them.
 * These requests come from "make" button in top, right-click on module->build, etc.
 */
class WemiModuleBuildTaskRunner : ProjectTaskRunner() {

    private val LOG = Logger.getInstance("#com.darkyen.wemi.intellij.compilerIntegration.WemiProjectTaskRunner")

    override fun run(project: Project, context: ProjectTaskContext,
                     callback: ProjectTaskNotification?, tasks: MutableCollection<out ProjectTask>) {
        var compileBuildScript = false
        val projectsToCompile = ArrayList<String>()

        for (task in tasks) {
            if (task is ModuleBuildTask) {
                val moduleComponent = task.module.getComponent(WemiModuleComponent::class.java) ?: throw AssertionError("Module ${task.module} does not have WemiModuleComponent")
                val moduleType = moduleComponent.moduleType ?: throw AssertionError("Module ${task.module} is not a Wemi module")
                when (moduleType) {
                    WemiModuleType.BUILD_SCRIPT -> {
                        compileBuildScript = true
                    }
                    WemiModuleType.PROJECT -> {
                        projectsToCompile.add(moduleComponent.wemiModuleName ?: task.module.name)
                    }
                }
            } else {
                throw AssertionError("WemiModuleBuildTaskRunner can't run $task")
            }
        }

        val configuration = WemiTaskConfigurationType.INSTANCE.taskConfigurationFactory.createTemplateConfiguration(project)
        if (projectsToCompile.isEmpty() && !compileBuildScript) {
            callback?.finished(ProjectTaskResult(false, 0, 0))
            return
        }
        configuration.options.tasks = projectsToCompile.map { arrayOf("$it/compile") }
        configuration.useSuggestedName()

        val runner = WemiProgramRunner.instance()
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironment(executor, runner, RunManager.getInstance(project).createConfiguration(configuration, configuration.factory), project)
        environment.assignNewExecutionId()

        val result = RunConfigurationBeforeRunProvider.doRunTask(executor.id, environment, runner)
        callback?.finished(ProjectTaskResult(false, if (result) 0 else 1, 0))
    }

    override fun canRun(projectTask: ProjectTask): Boolean {
        return when (projectTask) {
            is ModuleBuildTask -> {
                projectTask.module.getComponent(WemiModuleComponent::class.java)?.moduleType != null
            }
            else -> false
        }
    }

}