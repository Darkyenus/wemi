package com.darkyen.wemi.intellij.importing

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.intellij.openapi.externalSystem.service.project.wizard.AbstractExternalProjectImportProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Provides 'import from external model' functionality.
 */
class WemiProjectImportProvider(builder: ImportFromWemiControlBuilder)
    : AbstractExternalProjectImportProvider(builder, WemiProjectSystemId) {

    override fun canCreateNewProject(): Boolean {
        return true
    }

    override fun canImportModule(): Boolean {
        return false
    }

    override fun getId(): String = "com.darkyen.wemi.intellij.importing.WemiProjectImportProvider"

    private fun VirtualFile.directoryToImport():VirtualFile? {
        if (this.isDirectory) {
            // This is a wemi folder with wemi launcher?
            val wemiLauncher = this.findChild("wemi")
            if (wemiLauncher.isWemiLauncher()) {
                return this
            }
            // Ok, no launcher, but maybe build has some build scripts?
            val buildFolder = this.findChild("build")
            if (buildFolder != null && buildFolder.children.any { it.isWemiSource() }) {
                return this
            }

            // Maybe this is the build folder?
            if (this.name.equals("build", ignoreCase = true) && children.any { it.isWemiSource() }) {
                return this.parent
            }
        } else {
            // isFile
            // Maybe this is the wemi launcher?
            if (this.isWemiLauncher()) {
                return this.parent
            }
            // Maybe this is one of wemi sources?
            if (this.isWemiSource()) {
                val buildFolder = this.parent
                if (buildFolder.name.equals("build", ignoreCase = true)) {
                    return buildFolder.parent
                }
            }
        }
        return null
    }

    private fun VirtualFile?.isWemiLauncher():Boolean {
        if (this == null || this.isDirectory) {
            return false
        }
        return this.name == "wemi"
    }

    private fun VirtualFile?.isWemiSource():Boolean {
        if (this == null || this.isDirectory) {
            return false
        }
        return this.name.endsWith(".wemi", ignoreCase = true)
    }

    override fun canImport(fileOrDirectory: VirtualFile, project: Project?): Boolean {
        return fileOrDirectory.directoryToImport() != null
    }

    override fun canImportFromFile(file: VirtualFile?): Boolean {
        return file?.directoryToImport() != null
    }

    override fun getPathToBeImported(file: VirtualFile): String {
        return (file.directoryToImport()?: file).path
    }

    override fun getFileSample(): String {
        return "<b>WEMI</b> project"
    }
}