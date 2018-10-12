package com.darkyen.wemi.intellij.settings

import com.darkyen.wemi.intellij.WemiLauncher
import com.darkyen.wemi.intellij.execution.WemiRunConfiguration
import com.intellij.openapi.externalSystem.model.settings.ExternalSystemExecutionSettings
import com.intellij.openapi.util.Key
import java.nio.file.Paths

/**
 * Settings to be used when invoking the wemi(.jar) file for information retrieval or other requests.
 */
class WemiExecutionSettings(val wemiLauncher: String,
                            val javaVmExecutable:String,
                            val downloadDocs:Boolean,
                            val downloadSources:Boolean,
                            private val prefixConfigurations:String,
                            val projectName:String?,
                            val allowBrokenBuildScripts:Boolean) : ExternalSystemExecutionSettings() {

    constructor(wemiLauncher: WemiLauncher,
                javaVmExecutable: String,
                downloadDocs: Boolean,
                downloadSources: Boolean,
                prefixConfigurations: String,
                projectName: String?,
                allowBrokenBuildScripts:Boolean):this(
            wemiLauncher.file.toString(),
            javaVmExecutable,
            downloadDocs,
            downloadSources,
            prefixConfigurations,
            projectName,
            allowBrokenBuildScripts)

    val launcher:WemiLauncher
        get() = WemiLauncher(Paths.get(wemiLauncher))

    var deferDebugToWemi:Boolean? = null

    fun prefixConfigurationsArray():Array<String> = when {
        prefixConfigurations.isBlank() -> emptyArray()
        else -> prefixConfigurations.split(':').toTypedArray()
    }

    override fun <U : Any?> putUserData(key: Key<U>, value: U?) {
        if (key == WemiRunConfiguration.EXECUTION_DEFER_DEBUG_TO_WEMI) {
            deferDebugToWemi = value as? Boolean
        } else {
            super.putUserData(key, value)
        }
    }
}