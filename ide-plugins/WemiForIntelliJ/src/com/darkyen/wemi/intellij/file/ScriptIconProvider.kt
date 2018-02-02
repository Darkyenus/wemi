package com.darkyen.wemi.intellij.file

import com.intellij.ide.FileIconProvider
import com.intellij.ide.IconProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import icons.WemiIcons
import javax.swing.Icon

/**
 * Provides script icons for Wemi build scripts
 */
class ScriptIconProvider : IconProvider(), FileIconProvider, DumbAware  {

    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        if ((element as? PsiFile)?.virtualFile?.isWemiScriptSource(true) == true) {
            return WemiIcons.SCRIPT
        }
        return null
    }

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        if (file.isWemiScriptSource(true)) {
            return WemiIcons.SCRIPT
        }

        return null
    }
}