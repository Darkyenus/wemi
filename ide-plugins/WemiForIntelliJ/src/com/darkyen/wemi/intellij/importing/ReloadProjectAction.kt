package com.darkyen.wemi.intellij.importing

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.darkyen.wemi.intellij.settings.WemiProjectSettings
import com.darkyen.wemi.intellij.settings.WemiSystemSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import icons.WemiIcons

/**
 * Action to re-import the project explicitly.
 */
class ReloadProjectAction : AnAction("Reload Wemi Project", "Re-import Wemi project in the project's root into the IDE", WemiIcons.WEMI) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        if (project == null || !canOfferReload(project)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val basePath = WemiProjectSettings.getInstance(project)?.externalProjectPath ?: project.basePath!!
        ExternalSystemUtil.refreshProject(project, WemiProjectSystemId, basePath, false, ProgressExecutionMode.START_IN_FOREGROUND_ASYNC)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && canOfferReload(project)
    }

    private companion object {

        fun canOfferReload(project: Project):Boolean {
            if (project.isDefault) {
                return false
            }
            if (!WemiSystemSettings.getInstance(project).linkedProjectsSettings.isEmpty() && project.baseDir != null) {
                return true
            }
            return false
        }
    }
}