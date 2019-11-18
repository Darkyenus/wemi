package com.darkyen.wemi.intellij.importing

import com.darkyen.wemi.intellij.*
import com.darkyen.wemi.intellij.util.toPath
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import icons.WemiIcons
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Action to convert foreign project to Wemi and import it.
 *
 * This is usually the first point at which user meets this plugin.
 */
class InstallWemiLauncherAction : AnAction(INSTALL_TITLE,
        "Place plugin's 'wemi' launcher file into the project's root, updating the existing one", WemiIcons.ACTION) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (project.isDefault) return
        reinstallWemiLauncher(project, "Could not (re)install Wemi launcher")
        project.guessProjectDir()?.refresh(true, false)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project != null) {
            e.presentation.isEnabledAndVisible = true
            e.presentation.text = if (findWemiLauncher(project) == null) {
                INSTALL_TITLE
            } else {
                REINSTALL_TITLE
            }
        } else {
            e.presentation.isEnabledAndVisible = false
        }
    }

    companion object {
        private val LOG = Logger.getInstance(InstallWemiLauncherAction::class.java)

        private const val INSTALL_TITLE = "Install Wemi launcher"
        private const val REINSTALL_TITLE = "Reinstall Wemi launcher"

        fun reinstallWemiLauncher(project:Project, failNotificationTitle:String):Pair<Path, WemiLauncher>? {
            val wemiLauncherStream: InputStream? = InstallWemiLauncherAction::class.java.classLoader
                    .getResourceAsStream("wemi-launcher")

            if (wemiLauncherStream == null) {
                LOG.error("wemi-launcher resource does not exist")
                WemiNotificationGroup.showBalloon(project, failNotificationTitle,
                        "Plugin installation is corrupted - no bundled launcher",
                        NotificationType.ERROR)
                return null
            }

            val projectBaseDir = project.guessProjectDir()
            val projectBasePath = projectBaseDir?.toPath()
            val wemiLauncherPath = projectBasePath?.resolve(WemiLauncherFileName)?.toAbsolutePath() ?: run {
                LOG.error("Project $project does not have baseDir convertible to Path")
                WemiNotificationGroup.showBalloon(project, failNotificationTitle,
                        "Project's directory is in a strange place",
                        NotificationType.ERROR)
                return null
            }

            try {
                Files.newOutputStream(wemiLauncherPath).use { wemiFile ->
                    wemiLauncherStream.use {
                        it.copyTo(wemiFile)
                    }
                }
            } catch (e:Exception) {
                LOG.error("Failed to copy Wemi binary to $wemiLauncherPath", e)
                WemiNotificationGroup.showBalloon(project, failNotificationTitle,
                        "Failed to create \"wemi\" file",
                        NotificationType.ERROR)
                return null
            }
            try {
                val options = Files.getPosixFilePermissions(wemiLauncherPath).toMutableSet()
                options.add(PosixFilePermission.OWNER_EXECUTE)
                options.add(PosixFilePermission.GROUP_EXECUTE)
                options.add(PosixFilePermission.OTHERS_EXECUTE)
                Files.setPosixFilePermissions(wemiLauncherPath, options)
            } catch (e:Exception) {
                LOG.error("Failed to make Wemi binary ($wemiLauncherPath) executable", e)
                WemiNotificationGroup.showBalloon(project, failNotificationTitle,
                        "Failed to make \"wemi\" launcher executable, you may need to make it manually",
                        NotificationType.WARNING)
            }

            return Pair(projectBasePath, WemiLauncher(wemiLauncherPath))
        }
    }
}