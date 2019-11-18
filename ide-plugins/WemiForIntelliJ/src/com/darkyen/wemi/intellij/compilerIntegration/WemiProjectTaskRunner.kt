package com.darkyen.wemi.intellij.compilerIntegration

import com.darkyen.wemi.intellij.WemiNotificationGroup
import com.darkyen.wemi.intellij.module.WemiModuleComponent
import com.darkyen.wemi.intellij.module.WemiModuleType
import com.darkyen.wemi.intellij.showBalloon
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.task.*

/**
 * Catches requests to build modules/project and handles them.
 * These requests come from "make" button in top, right-click on module->build, etc.
 */
class WemiProjectTaskRunner : ProjectTaskRunner() {

    private val LOG = Logger.getInstance("#com.darkyen.wemi.intellij.compilerIntegration.WemiProjectTaskRunner")

    private fun ProjectTask.wemiModuleType(): WemiModuleType? {
        // We don't care about other task types yet: ExecuteRunConfigurationTask, ArtifactBuildTask
        return (this as? ModuleBuildTask)?.module?.getComponent(WemiModuleComponent::class.java)?.moduleType
    }

    //TODO Also support JUnit tasks

    override fun run(project: Project, context: ProjectTaskContext,
                     callback: ProjectTaskNotification?, tasks: MutableCollection<out ProjectTask>) {
        var onlyBadModuleBuilds:Boolean? = null
        var aborted = false
        var errors = 0
        var warnings = 0

        fun handleNextTask(from:Iterator<ProjectTask>) {
            if (from.hasNext()) {
                val task = from.next()
                if (task is ModuleBuildTask) {
                    val moduleType = task.wemiModuleType()
                    if (moduleType != WemiModuleType.PROJECT) {
                        if (onlyBadModuleBuilds == null) {
                            onlyBadModuleBuilds = true
                        }
                    } else {
                        onlyBadModuleBuilds = false

                        // Build it!
                        //TODO: http://www.jetbrains.org/intellij/sdk/docs/basics/run_configurations/run_configuration_execution.html
                        WemiNotificationGroup.showBalloon(project,
                                "Wemi",
                                "Implicit compilation is not supported yet. Create an explicit run task or invoke Wemi from Terminal.",
                                NotificationType.INFORMATION)
                        // This messy indirection is because we will invoke the compilation asynchronously and we have to
                        // handle next task after the previous one completes.
                        handleNextTask(from)
                    }
                } else {
                    // Unknown type
                    LOG.error("Unsupported task: $task")
                    handleNextTask(from)
                }
            } else {
                if (onlyBadModuleBuilds == true) {
                    WemiNotificationGroup.showBalloon(project,
                            "Wemi",
                            "Can't explicitly build non-Wemi-project modules. Those are built automatically.",
                            NotificationType.WARNING)
                    warnings++
                }

                callback?.finished(ProjectTaskResult(aborted, errors, warnings))
            }
        }

        handleNextTask(tasks.iterator())
    }

    override fun canRun(projectTask: ProjectTask): Boolean {
        return when (projectTask) {
            is ModuleBuildTask -> {
                projectTask.module.getComponent(WemiModuleComponent::class.java)?.moduleType != null
            }
            is ExecuteRunConfigurationTask -> {
                false
            }
            else -> false
        }
    }

}