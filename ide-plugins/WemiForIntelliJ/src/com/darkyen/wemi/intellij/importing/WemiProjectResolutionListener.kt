package com.darkyen.wemi.intellij.importing

import com.darkyen.wemi.intellij.WemiNotificationGroup
import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListenerAdapter
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemNotificationManager
import com.intellij.openapi.externalSystem.service.notification.NotificationCategory
import com.intellij.openapi.externalSystem.service.notification.NotificationData
import com.intellij.openapi.externalSystem.service.notification.NotificationSource
import java.lang.Exception

/**
 * Gets notified when the project import fails for Wemi project and shows a helpful notification
 */
class WemiProjectResolutionListener : ExternalSystemTaskNotificationListenerAdapter() {

    override fun onSuccess(taskId: ExternalSystemTaskId) {
        if (WemiProjectSystemId != taskId.projectSystemId || taskId.type != ExternalSystemTaskType.RESOLVE_PROJECT) {
            return
        }

        ExternalSystemNotificationManager.getInstance(taskId.findProject() ?: return)
                .showNotification(taskId.projectSystemId,
                        NotificationData("Wemi - Success",
                                "Project imported successfully",
                                NotificationCategory.INFO,
                                NotificationSource.PROJECT_SYNC, null, -1, -1, true).apply {
                            balloonGroup = WemiNotificationGroup.displayId
                        })
    }

    override fun onFailure(taskId: ExternalSystemTaskId, e: Exception) {
        if (WemiProjectSystemId != taskId.projectSystemId || taskId.type != ExternalSystemTaskType.RESOLVE_PROJECT) {
            return
        }
        val project = taskId.findProject() ?: return

        val notification = NotificationData(
                "Wemi - Failure",
                "Failed to import the project",
                NotificationCategory.ERROR,
                NotificationSource.PROJECT_SYNC, null, -1, -1, true)
        notification.balloonGroup = WemiNotificationGroup.displayId
        ExternalSystemNotificationManager.getInstance(project).showNotification(taskId.projectSystemId, notification)
    }
}
