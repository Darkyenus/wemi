package com.darkyen.wemi.intellij.settings

import com.intellij.openapi.externalSystem.settings.DelegatingExternalSystemSettingsListener
import com.intellij.openapi.externalSystem.settings.ExternalSystemSettingsListener

/**
 * Boilerplate for [WemiSettingsListener]
 */
class WemiSettingsListenerDelegatingAdapter(delegate: ExternalSystemSettingsListener<WemiProjectSettings>)
    : DelegatingExternalSystemSettingsListener<WemiProjectSettings>(delegate), WemiSettingsListener