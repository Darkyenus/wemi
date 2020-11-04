package com.darkyen.wemi.intellij

import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Notification group for the Wemi plugin
 */
val WemiNotificationGroup = NotificationGroup.balloonGroup("Wemi Notification Group")

fun NotificationGroup.showBalloon(project:Project?, title:String, message: String, type:NotificationType, listener:NotificationListener? = null) {
    this.createNotification(title, message, type, listener).notify(project)
}