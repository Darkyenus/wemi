package com.darkyen.wemi.intellij.manager

import com.darkyen.wemi.intellij.WemiBuildDirectoryName
import com.darkyen.wemi.intellij.WemiLauncherFileName
import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.darkyen.wemi.intellij.file.isWemiScriptSource
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.settings.ExternalSystemConfigLocator
import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

/**
 * Specifies which files are taken into consideration when configuring a Wemi project.
 */
class WemiConfigLocator : ExternalSystemConfigLocator {

    override fun getTargetExternalSystemId(): ProjectSystemId = WemiProjectSystemId

    override fun findAll(externalProjectSettings: ExternalProjectSettings): MutableList<VirtualFile> {
        val result = mutableListOf<VirtualFile>()

        val projectRoot = VirtualFileManager.getInstance()
                .getFileSystem("file")?.findFileByPath(externalProjectSettings.externalProjectPath)
                ?:return result

        val wemi = projectRoot.findChild(WemiLauncherFileName)
        if (wemi != null) {
            result.add(wemi)
        }

        val build = projectRoot.findChild(WemiBuildDirectoryName) ?: return result
        for (child in build.children) {
            if (child.isWemiScriptSource()) {
                result.add(child)
            }
        }

        return result
    }

    override fun adjust(configPath: VirtualFile): VirtualFile? {
        val build = configPath.findChild(WemiBuildDirectoryName)
        if (build != null) {
            for (child in build.children) {
                if (child.isWemiScriptSource()) {
                    return child
                }
            }
        }
        return configPath.findChild(WemiLauncherFileName)
    }
}