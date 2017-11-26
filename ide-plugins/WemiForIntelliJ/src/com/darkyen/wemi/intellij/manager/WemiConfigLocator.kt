package com.darkyen.wemi.intellij.manager

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.settings.ExternalSystemConfigLocator
import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileSystem
import java.io.File

/**
 * Specifies which files are taken into consideration when configuring a Wemi project.
 */
class WemiConfigLocator : ExternalSystemConfigLocator {

    override fun getTargetExternalSystemId(): ProjectSystemId = WemiProjectSystemId

    private fun VirtualFile.isWemiScriptFile():Boolean {
        val name = this.name
        return !name.startsWith(".") && name.endsWith(".wemi", ignoreCase = true)
    }

    override fun findAll(externalProjectSettings: ExternalProjectSettings): MutableList<VirtualFile> {
        val result = mutableListOf<VirtualFile>()

        val projectRoot = VirtualFileManager.getInstance()
                .getFileSystem("file")?.findFileByPath(externalProjectSettings.externalProjectPath)
                ?:return result

        val wemi = projectRoot.findChild("wemi")
        if (wemi != null) {
            result.add(wemi)
        }

        val build = projectRoot.findChild("build") ?: return result
        for (child in build.children) {
            if (child.isWemiScriptFile()) {
                result.add(child)
            }
        }

        return result
    }

    override fun adjust(configPath: VirtualFile): VirtualFile? {
        val build = configPath.findChild("build")
        if (build != null) {
            for (child in build.children) {
                if (child.isWemiScriptFile()) {
                    return child
                }
            }
        }
        return configPath.findChild("wemi")
    }
}