package com.darkyen.wemi.intellij.settings

import com.intellij.openapi.externalSystem.settings.ExternalSystemSettingsListener
import com.intellij.util.messages.Topic

/**
 * Listener for changes in [WemiProjectSettings].
 */
interface WemiSettingsListener : ExternalSystemSettingsListener<WemiProjectSettings> {

    fun onProjectSettingsChanged(old:WemiProjectSettings, current:WemiProjectSettings) {}

    companion object {
        val TOPIC: Topic<WemiSettingsListener> = Topic.create<WemiSettingsListener>("Wemi-specific settings", WemiSettingsListener::class.java)
    }

}