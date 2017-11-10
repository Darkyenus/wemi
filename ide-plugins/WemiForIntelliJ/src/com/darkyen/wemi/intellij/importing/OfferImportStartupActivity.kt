package com.darkyen.wemi.intellij.importing

import com.darkyen.wemi.intellij.WemiNotificationGroup
import com.darkyen.wemi.intellij.findWemiLauncher
import com.darkyen.wemi.intellij.showBalloon
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import javax.swing.event.HyperlinkEvent

/**
 * Runs at IDE startup and notifies the user if there is a project to import.
 *
 * TODO Add functionality to open the Wemi project directly as Intellij project
 */
class OfferImportStartupActivity : StartupActivity {

    override fun runActivity(project: Project) {
        if (!PropertiesComponent.getInstance(project).getBoolean(KEY_ASK_ABOUT_IMPORT, true)) {
            return
        }

        if (!ImportProjectAction.canOfferImportOfUnlinkedProject(project)) {
            return
        }

        val launcher = findWemiLauncher(project) ?: return

        WemiNotificationGroup.showBalloon(project,
                "Detached WEMI project found",
                """<a href=$HREF_IMPORT>Import</a> WEMI-backed project.
                    |<br><a href=$HREF_HIDE>Don't ask again.</a>
                    |<br>You can import manually later from Tools -> Import Wemi Project""".trimMargin(),
                NotificationType.INFORMATION, object : NotificationListener.Adapter() {

            override fun hyperlinkActivated(notification: Notification, e: HyperlinkEvent) {
                notification.expire()
                if (HREF_IMPORT == e.description) {
                    ImportProjectAction.importUnlinkedProject(project, launcher)
                } else if (HREF_HIDE == e.description) {
                    PropertiesComponent.getInstance(project).setValue(KEY_ASK_ABOUT_IMPORT, false, true)
                }
            }
        })
    }

    companion object {
        private val KEY_ASK_ABOUT_IMPORT = "com.darkyen.wemi.askedAboutImport"
        private val HREF_IMPORT = "import"
        private val HREF_HIDE = "hide"
    }
}

