package com.darkyen.wemi.intellij.settings

import com.darkyen.wemi.intellij.WemiLauncher
import com.intellij.openapi.externalSystem.model.settings.ExternalSystemExecutionSettings
import java.nio.file.Paths

/**
 * Settings to be used when invoking the wemi(.jar) file for information retrieval or other requests.
 */
class WemiExecutionSettings(val wemiLauncher: String?,
                            val javaVmExecutable:String,
                            val downloadDocs:Boolean,
                            val downloadSources:Boolean,
                            private val prefixConfigurations:String,
                            val projectName:String?,
                            val allowBrokenBuildScripts:Boolean) : ExternalSystemExecutionSettings() {

    constructor(wemiLauncher: WemiLauncher?,
                javaVmExecutable: String,
                downloadDocs: Boolean,
                downloadSources: Boolean,
                prefixConfigurations: String,
                projectName: String?,
                allowBrokenBuildScripts:Boolean):this(
            wemiLauncher?.file?.toString(),
            javaVmExecutable,
            downloadDocs,
            downloadSources,
            prefixConfigurations,
            projectName,
            allowBrokenBuildScripts)

    val launcher:WemiLauncher?
        get() {
            return WemiLauncher(Paths.get(wemiLauncher?: return null))
        }

    var deferDebugToWemi:Boolean? = null

    fun prefixConfigurationsArray():Array<String> = when {
        prefixConfigurations.isBlank() -> emptyArray()
        else -> prefixConfigurations.split(':').toTypedArray()
    }
}