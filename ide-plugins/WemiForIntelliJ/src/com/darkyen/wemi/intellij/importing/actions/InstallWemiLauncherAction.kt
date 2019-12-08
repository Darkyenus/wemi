package com.darkyen.wemi.intellij.importing.actions

import com.darkyen.wemi.intellij.importing.defaultWemiRootPathFor
import com.darkyen.wemi.intellij.importing.getWemiLauncher
import com.darkyen.wemi.intellij.importing.isImportable
import com.darkyen.wemi.intellij.importing.reinstallWemiLauncher
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.vfs.LocalFileSystem
import icons.WemiIcons

/**
 * Action to convert foreign project to Wemi and import it.
 *
 * This is usually the first point at which user meets this plugin.
 */
class InstallWemiLauncherAction : AnAction(INSTALL_TITLE,
        "Place plugin's 'wemi' launcher file into the project's root, updating the existing one", WemiIcons.ACTION) {

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null || !isImportable(project)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        e.presentation.isEnabledAndVisible = true
        e.presentation.text = if (project.getWemiLauncher() == null) {
            INSTALL_TITLE
        } else {
            REINSTALL_TITLE
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = defaultWemiRootPathFor(project) ?: return
        reinstallWemiLauncher(basePath, "Could not (re)install Wemi launcher", project)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath.toString())
    }

    companion object {
        private const val INSTALL_TITLE = "Install Wemi launcher"
        private const val REINSTALL_TITLE = "Reinstall Wemi launcher"
    }
}