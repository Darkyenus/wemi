package com.darkyen.wemi.intellij.importing.actions

import com.darkyen.wemi.intellij.WemiLauncher
import com.darkyen.wemi.intellij.importing.hasWemiFiles
import com.darkyen.wemi.intellij.importing.isImportable
import com.darkyen.wemi.intellij.importing.isWemiLinked
import com.darkyen.wemi.intellij.importing.withWemiLauncher
import com.darkyen.wemi.intellij.projectImport.ImportFromWemiProvider
import com.intellij.ide.actions.ImportModuleAction
import com.intellij.ide.util.newProjectWizard.AddModuleWizard
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import icons.WemiIcons

/**
 * Action to import the project.
 *
 * This is usually the first point at which user meets this plugin.
 */
class ImportProjectAction : AnAction("Import Wemi Project",
        "Import an unlinked Wemi project in the project's root into the IDE", WemiIcons.ACTION) {

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && isImportable(project) && !isWemiLinked(project) && hasWemiFiles(project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        if (!(project != null && isImportable(project) && !isWemiLinked(project) && hasWemiFiles(project))) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        project.withWemiLauncher("Wemi project import") { launcher ->
            importUnlinkedProject(project, launcher)
        }
    }

    companion object {
        fun importUnlinkedProject(project: Project, launcher: WemiLauncher) {
            val projectDirectory = launcher.file.parent
            val wizard = AddModuleWizard(project, projectDirectory.toString(), ImportFromWemiProvider())
            if (wizard.stepCount <= 0 || wizard.showAndGet()) {
                ImportModuleAction.createFromWizard(project, wizard)
            }
        }
    }
}