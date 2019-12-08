package com.darkyen.wemi.intellij.importing.actions

import com.darkyen.wemi.intellij.WemiLauncher
import com.darkyen.wemi.intellij.findWemiLauncher
import com.darkyen.wemi.intellij.projectImport.ImportFromWemiProvider
import com.darkyen.wemi.intellij.settings.WemiProjectService
import com.intellij.ide.actions.ImportModuleAction
import com.intellij.ide.util.newProjectWizard.AddModuleWizard
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import icons.WemiIcons

/**
 * Action to import the project.
 *
 * This is usually the first point at which user meets this plugin.
 */
class ImportProjectAction : AnAction("Import Wemi Project",
        "Import an unlinked Wemi project in the project's root into the IDE", WemiIcons.ACTION) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        if (project == null || !canOfferImportOfUnlinkedProject(project)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val launcher = findWemiLauncher(project)
        if (launcher == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        importUnlinkedProject(project, launcher)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
                project != null
                && canOfferImportOfUnlinkedProject(project)
                && findWemiLauncher(project) != null
    }

    companion object {

        fun canOfferImportOfUnlinkedProject(project: Project):Boolean {
            if (project.isDefault || project.getServiceIfCreated(WemiProjectService::class.java) != null || project.guessProjectDir() == null) {
                return false
            }
            return true
        }

        fun importUnlinkedProject(project: Project, launcher: WemiLauncher) {
            val projectDirectory = launcher.file.parent
            val wizard = AddModuleWizard(project, projectDirectory.toString(), ImportFromWemiProvider())
            if (wizard.stepCount <= 0 || wizard.showAndGet()) {
                ImportModuleAction.createFromWizard(project, wizard)
            }
        }
    }
}