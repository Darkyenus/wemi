package com.darkyen.wemi.intellij.execution

import com.darkyen.wemi.intellij.settings.WemiModuleService
import com.darkyen.wemi.intellij.settings.WemiModuleType
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.RunConfigurationBeforeRunProvider
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.task.ModuleBuildTask
import com.intellij.task.ProjectTask
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskRunner
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise

/**
 * Catches requests to build modules/project and handles them.
 * These requests come from "make" button in top, right-click on module->build, etc.
 */
class WemiModuleBuildTaskRunner : ProjectTaskRunner() {

    private val LOG = Logger.getInstance("#com.darkyen.wemi.intellij.compilerIntegration.WemiProjectTaskRunner")

    override fun run(project: Project, context: ProjectTaskContext, vararg tasks: ProjectTask?): Promise<Result> {
        var compileBuildScript = false
        val projectsToCompile = ArrayList<String>()

        for (task in tasks) {
            if (task is ModuleBuildTask) {
                val moduleComponent = task.module.getService(WemiModuleService::class.java) ?: throw AssertionError("Module ${task.module} does not have WemiModuleComponent")
                val moduleType = moduleComponent.wemiModuleType ?: throw AssertionError("Module ${task.module} is not a Wemi module")
                when (moduleType) {
                    WemiModuleType.BUILD_SCRIPT -> {
                        compileBuildScript = true
                    }
                    WemiModuleType.PROJECT -> {
                        projectsToCompile.add(moduleComponent.wemiProjectName ?: task.module.name)
                    }
                }
            } else {
                throw AssertionError("WemiModuleBuildTaskRunner can't run $task")
            }
        }

        val configuration = WemiTaskConfigurationType.INSTANCE.taskConfigurationFactory.createTemplateConfiguration(project)
        if (projectsToCompile.isEmpty() && !compileBuildScript) {
            return resolvedPromise(object : Result {
                override fun hasErrors(): Boolean = false

                override fun isAborted(): Boolean = false
            })
        }
        configuration.options.tasks = projectsToCompile.map { arrayOf("$it/compile") }
        configuration.useSuggestedName()

        val runner = WemiProgramRunner.instance()
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironment(executor, runner, RunManager.getInstance(project).createConfiguration(configuration, configuration.factory), project)
        environment.assignNewExecutionId()

        val result = RunConfigurationBeforeRunProvider.doRunTask(executor.id, environment, runner)
        return resolvedPromise(object : Result {
            override fun hasErrors(): Boolean = !result

            override fun isAborted(): Boolean = false
        })
    }

    override fun canRun(projectTask: ProjectTask): Boolean {
        return when (projectTask) {
            is ModuleBuildTask -> {
                projectTask.module.getService(WemiModuleService::class.java)?.wemiModuleType != null
            }
            else -> false
        }
    }

}