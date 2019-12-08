package com.darkyen.wemi.intellij.projectImport

import com.darkyen.wemi.intellij.wemiDirectoryToImport
import com.intellij.ide.util.projectWizard.ModuleWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.projectImport.ProjectImportProvider

/**
 * Provides 'import from external model' functionality.
 */
class ImportFromWemiProvider : ProjectImportProvider(ImportFromWemiBuilder()) {

    override fun getId(): String = "com.darkyen.wemi.intellij.projectImport.WemiProjectImportProvider"

    override fun canCreateNewProject(): Boolean = true

    override fun canImportModule(): Boolean = false

    override fun canImport(fileOrDirectory: VirtualFile, project: Project?): Boolean {
        return wemiDirectoryToImport(fileOrDirectory) != null
    }

    override fun canImportFromFile(file: VirtualFile?): Boolean {
        return wemiDirectoryToImport(file ?: return false) != null
    }

    override fun getPathToBeImported(file: VirtualFile): String {
        return (wemiDirectoryToImport(file) ?: file).path
    }

    override fun createSteps(context: WizardContext?): Array<ModuleWizardStep>? {
        return arrayOf(ConfigureWemiProjectStep(context!!))
    }

    override fun getFileSample(): String = "<b>WEMI</b> project directory (wemi, build/*.kt)"
}