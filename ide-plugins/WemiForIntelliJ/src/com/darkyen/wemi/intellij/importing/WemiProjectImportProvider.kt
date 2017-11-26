package com.darkyen.wemi.intellij.importing

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.intellij.openapi.externalSystem.service.project.wizard.AbstractExternalProjectImportProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Provides 'import from external model' functionality.
 *
 * TODO Implement
 */
class WemiProjectImportProvider(builder: ImportFromWemiControlBuilder) : AbstractExternalProjectImportProvider(builder, WemiProjectSystemId) {

    override fun canCreateNewProject(): Boolean {
        return true
    }

    override fun canImportModule(): Boolean {
        return true
    }

    override fun canImport(fileOrDirectory: VirtualFile, project: Project?): Boolean {
        return super.canImport(fileOrDirectory, project)
    }

    override fun getFileSample(): String {
        return "<b>WEMI</b> build script (build.wemi)"
    }
}