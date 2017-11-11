package com.darkyen.wemi.intellij.importing

import com.darkyen.wemi.intellij.WemiNotificationGroup
import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.esotericsoftware.jsonbeans.JsonException
import com.esotericsoftware.jsonbeans.JsonReader
import com.esotericsoftware.jsonbeans.JsonValue
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListenerAdapter
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemNotificationManager
import com.intellij.openapi.externalSystem.service.notification.NotificationCategory
import com.intellij.openapi.externalSystem.service.notification.NotificationData
import com.intellij.openapi.externalSystem.service.notification.NotificationSource
import java.lang.Exception
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.UndeclaredThrowableException
import java.util.regex.Pattern

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
                        NotificationData("Wemi Success",
                                "Project imported successfully",
                                NotificationCategory.INFO,
                                NotificationSource.PROJECT_SYNC, null, -1, -1, true).apply {
                            balloonGroup = WemiNotificationGroup.displayId
                        })
    }

    private fun unwrapException(e:Exception):String? {
        var ex:Throwable? = e
        if (ex is UndeclaredThrowableException) {
            ex = ex.undeclaredThrowable
        }
        if (ex is InvocationTargetException) {
            ex = ex.targetException
        }
        if (ex !is ExternalSystemException && ex?.cause != null) {
            ex = ex.cause
        }

        if (ex is ExternalSystemException) {
            return ex.message
        }
        return null
    }

    override fun onFailure(taskId: ExternalSystemTaskId, e: Exception) {
        if (WemiProjectSystemId != taskId.projectSystemId || taskId.type != ExternalSystemTaskType.RESOLVE_PROJECT) {
            return
        }
        val project = taskId.findProject() ?: return

        var failMessage = unwrapException(e) ?: return

        val json:JsonValue? =
                JsonReader().let {
                    try {
                        it.parse(failMessage)
                    } catch (e:JsonException) {
                        null
                    }
                }

        if (json != null) {
            val status = json.getString("status",null)?.toLowerCase()?.replace('_', ' ')?.capitalize()
            val output = json.getString("output",null)

            if (status != null && output != null) {
                failMessage = status + '\n' + output
            } else if (status != null) {
                failMessage = status
            } else if (output != null) {
                failMessage = output
            }
        }

        var notification: NotificationData? = null

        if (json != null && json.getString("status", null) == "BUILD_SCRIPT_COMPILATION_ERROR") {
            val output = json.getString("output")

            var filePath:String? = null
            var fileLine = -1
            var fileColumn = -1
            ERROR_FILE_PATTERN.matcher(output).let {
                if (it.find()) {
                    filePath = it.group(1)
                    fileLine = it.group(2).toInt()
                    fileColumn = it.group(3).toInt()
                }
            }

            notification = NotificationData(
                    "Failed to compile Wemi build script",
                    failMessage,
                    NotificationCategory.ERROR,
                    NotificationSource.PROJECT_SYNC, filePath, fileLine, fileColumn, true)
        }

        if (notification == null) {
            notification = NotificationData(
                    "Failed to resolve Wemi project",
                    failMessage,
                    NotificationCategory.ERROR,
                    NotificationSource.PROJECT_SYNC, null, -1, -1, true)
        }
        notification.balloonGroup = WemiNotificationGroup.displayId
        ExternalSystemNotificationManager.getInstance(project).showNotification(taskId.projectSystemId, notification)
    }
}

// build.wemi:13:5
private val ERROR_FILE_PATTERN = Pattern.compile("(\\w+\\.wemi):(\\d+):(\\d+)", Pattern.CASE_INSENSITIVE)
