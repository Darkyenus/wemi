package com.darkyen.wemi.intellij.importing.actions

import com.darkyen.wemi.intellij.file.isWemiScriptSource
import com.darkyen.wemi.intellij.findWemiLauncher
import com.darkyen.wemi.intellij.projectImport.ProjectNode
import com.darkyen.wemi.intellij.projectImport.import
import com.darkyen.wemi.intellij.projectImport.refreshProject
import com.darkyen.wemi.intellij.settings.WemiProjectService
import com.intellij.concurrency.ResultConsumer
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.util.concurrency.EdtExecutorService
import icons.WemiIcons
import org.jetbrains.kotlin.idea.refactoring.psiElement

/** Action to re-import the project explicitly. */
class ReloadProjectAction : AnAction("Reload Wemi Project",
        "Re-import Wemi project in the project's root into the IDE",
        WemiIcons.ACTION) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val basePath = project?.basePath
        if (project == null || basePath == null || !canOfferReload(project)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val launcher = findWemiLauncher(project)
        if (launcher == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val future = refreshProject(project, launcher,
                project.getService(WemiProjectService::class.java)?.state?.options ?: return)
        future.addConsumer(EdtExecutorService.getInstance(), object : ResultConsumer<ProjectNode> {
            override fun onSuccess(value: ProjectNode) {
                import(value, project)
            }

            override fun onFailure(t: Throwable) {}
        })
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || !canOfferReload(project)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        if (e.place == ActionPlaces.PROJECT_VIEW_POPUP) {
            // Right-clicking a file - is it a Wemi script file?
            e.presentation.isEnabledAndVisible = e.dataContext.psiElement
                    ?.containingFile?.originalFile?.virtualFile.isWemiScriptSource(false)
        } else {
            // Elsewhere, possibly Tools
            e.presentation.isEnabledAndVisible = true
        }
    }

    private companion object {

        fun canOfferReload(project: Project):Boolean {
            if (project.isDefault) {
                return false
            }
            if (project.getServiceIfCreated(WemiProjectService::class.java) != null && project.guessProjectDir() != null) {
                return true
            }
            return false
        }
    }
}