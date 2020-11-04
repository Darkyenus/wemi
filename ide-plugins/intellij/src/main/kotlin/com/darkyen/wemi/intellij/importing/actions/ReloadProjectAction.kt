package com.darkyen.wemi.intellij.importing.actions

import com.darkyen.wemi.intellij.file.isWemiScriptSource
import com.darkyen.wemi.intellij.importing.isImportable
import com.darkyen.wemi.intellij.importing.isWemiLinked
import com.darkyen.wemi.intellij.projectImport.importWemiProject
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import icons.WemiIcons
import org.jetbrains.kotlin.idea.refactoring.psiElement

/** Action to re-import the project explicitly. */
class ReloadProjectAction : AnAction("Reload Wemi Project",
        "Re-import Wemi project in the project's root into the IDE",
        WemiIcons.ACTION) {

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || !isImportable(project) || !isWemiLinked(project)) {
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

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        if (project == null || !isImportable(project) || !isWemiLinked(project)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        ApplicationManager.getApplication().invokeLater({
            FileDocumentManager.getInstance().saveAllDocuments()
            importWemiProject(project, initial = false)
        }, ModalityState.NON_MODAL)
    }
}