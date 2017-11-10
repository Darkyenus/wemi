package com.darkyen.wemi.intellij.execution

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemBeforeRunTask
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemBeforeRunTaskProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import icons.WemiIcons

/**
 * Provides ability to run Wemi task before any run configuration.
 *
 * The task currently looks like the Wemi Run Configuration. (TODO: Is this intentional? What is it caused by?)
 */
class WemiBeforeRunTaskProvider(project: Project) : ExternalSystemBeforeRunTaskProvider(WemiProjectSystemId, project, ID) {

    /** Icon used in the "Before launch" window in "Run/Debug Configurations" */
    override fun getIcon() = WemiIcons.WEMI

    /**
     * NOTE: We allow to create BeforeRunTask even for Wemi configurations. This is intentional, user might want to
     * create a sequence of before run tasks.
     *
     * @param configuration for which the task is created
     */
    override fun createTask(configuration: RunConfiguration) = ExternalSystemBeforeRunTask(ID, WemiProjectSystemId)

    companion object {
        val ID = Key.create<ExternalSystemBeforeRunTask>("Wemi.BeforeRunTask")
    }
}