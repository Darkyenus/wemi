package com.darkyen.wemi.intellij.file

import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ResolveScopeProvider
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.idea.util.projectStructure.allModules

/**
 * Provides autocomplete scope of the build module to the build files.
 *
 * At least in theory. TODO fix
 */
class WemiResolveScopeProvider : ResolveScopeProvider() {

    override fun getResolveScope(file: VirtualFile, project: Project?): GlobalSearchScope? {
        if (project != null && file.fileType == WemiFileType) {
            val projectModule = ModuleUtil.findModuleForFile(file, project) ?: return null
            val buildScriptModuleName = projectModule.name+"-build"
            val buildScriptModule = project.allModules().find { it.name == buildScriptModuleName } ?: return null
            return buildScriptModule.getModuleWithDependenciesAndLibrariesScope(false)
        }
        return null
    }
}