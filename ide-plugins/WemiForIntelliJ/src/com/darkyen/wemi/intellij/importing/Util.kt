package com.darkyen.wemi.intellij.importing

import com.darkyen.wemi.intellij.WemiLauncher
import com.darkyen.wemi.intellij.WemiLauncherFileName
import com.darkyen.wemi.intellij.WemiNotificationGroup
import com.darkyen.wemi.intellij.importing.actions.InstallWemiLauncherAction
import com.darkyen.wemi.intellij.settings.WemiProjectService
import com.darkyen.wemi.intellij.settings.isWemiModule
import com.darkyen.wemi.intellij.showBalloon
import com.darkyen.wemi.intellij.util.executable
import com.darkyen.wemi.intellij.util.getWindowsShellExe
import com.darkyen.wemi.intellij.wemiDirectoryToImport
import com.google.common.html.HtmlEscapers
import com.intellij.notification.Notification
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import javax.swing.event.HyperlinkEvent

private val LOG = Logger.getInstance("wemi.importing")

/** Check if this project can be imported. */
fun isImportable(project:Project):Boolean {
	return !project.isDefault && project.isInitialized
}

/** Check if this project is (at least partially) imported with Wemi */
fun isWemiLinked(project: Project):Boolean {
	if (project.isDefault) {
		return false
	}

	return allWemiModules(project).firstOrNull() != null

}

fun hasNonWemiLinkedModules(project:Project):Boolean {
	if (project.isDefault) {
		return false
	}

	return allWemiModules(project, wemiModules = false).firstOrNull() != null
}

/** Check whether the project has some traces of Wemi files (launcher or build scripts) */
fun hasWemiFiles(project: Project):Boolean {
	if (project.isDefault) {
		return false
	}

	if (project.getService(WemiProjectService::class.java)?.wemiLauncher != null) {
		return true
	}

	return wemiDirectoryToImport(LocalFileSystem.getInstance().findFileByPath(project.basePath ?: return false) ?: return false) != null
}

/** Return all modules which belong to Wemi.
 * @param wemiModules set to `false` to instead return those modules, that do NOT belong to Wemi. */
fun allWemiModules(project:Project?, wemiModules:Boolean = true):Sequence<Module> {
	if (project == null) {
		return emptySequence()
	}
	return ModuleManager.getInstance(project).modules.asSequence().filter { wemiModules == it.isWemiModule() }
}

fun defaultWemiRootPathFor(project:Project): Path? {
	val basePath = project.basePath ?: return null
	return Paths.get(basePath).toAbsolutePath().normalize()
}

/** Get Wemi launcher set for this project, if any. */
fun Project.getWemiLauncher(): WemiLauncher? {
	return getService(WemiProjectService::class.java)?.wemiLauncher
}

/** Using the Wemi launcher of this project, perform action.
 * If there is no launcher, user will be asked to install it.
 * If they refuse, [action] will not be called. */
fun Project.withWemiLauncher(operation:String, action:(WemiLauncher) -> Unit) {
	val launcher = getWemiLauncher()
	if (launcher != null) {
		action(launcher)
		return
	}

	val HREF_CREATE = "create"
	val HREF_CANCEL = "cancel"

	val basePath = defaultWemiRootPathFor(this) ?: return

	WemiNotificationGroup.showBalloon(this,
			"Wemi launcher is missing",
			"""<a href=${HREF_CREATE}>Create and continue</a> or <a href=${HREF_CANCEL}>cancel</a>.<br>
				|Launcher would be created in <code>${HtmlEscapers.htmlEscaper().escape(basePath.toString())}</code>.""".trimMargin(),
			NotificationType.INFORMATION, object : NotificationListener.Adapter() {

		override fun hyperlinkActivated(notification: Notification, e: HyperlinkEvent) {
			notification.expire()
			if (e.description == HREF_CREATE) {
				action(reinstallWemiLauncher(
						basePath,
						"Launcher installation failed. $operation will not continue.",
						this@withWemiLauncher)?.second ?: return
				)
			}
		}
	})
}

fun reinstallWemiLauncher(projectBasePath:Path, failNotificationTitle:String, project:Project?):Pair<Path, WemiLauncher>? {
	val wemiLauncherStream = InstallWemiLauncherAction::class.java.classLoader.getResourceAsStream("wemi-launcher.sh")
			?: throw IllegalStateException("Corrupted Wemi plugin: wemi-launcher.sh resource does not exist")

	val wemiLauncherPath = projectBasePath.resolve(WemiLauncherFileName)

	try {
		Files.deleteIfExists(wemiLauncherPath)
		Files.newOutputStream(wemiLauncherPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { wemiFile ->
			wemiLauncherStream.use {
				it.copyTo(wemiFile)
			}
		}
	} catch (e:Exception) {
		LOG.error("Failed to copy Wemi launcher to $wemiLauncherPath", e)
		WemiNotificationGroup.showBalloon(project, failNotificationTitle,
				"Failed to create \"wemi\" file",
				NotificationType.ERROR)
		return null
	}
	try {
		wemiLauncherPath.executable = true
	} catch (e:Exception) {
		LOG.error("Failed to make Wemi launcher ($wemiLauncherPath) executable", e)
		WemiNotificationGroup.showBalloon(project, failNotificationTitle,
				"Failed to make \"wemi\" launcher executable, you may need to make it manually",
				NotificationType.WARNING)
	}

	val shellExe = project.getWindowsShellExe()

	if (shellExe == null) {
		WemiNotificationGroup.showBalloon(project, failNotificationTitle,
				"Wemi launcher installed, but POSIX shell executable not found. Open Wemi preferences and set it up.",
				NotificationType.WARNING)
		return null
	}

	return Pair(projectBasePath, WemiLauncher(wemiLauncherPath, shellExe))
}