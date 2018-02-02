package com.darkyen.wemi.intellij.settings

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project

/**
 * Settings specific to a project.
 */
class WemiProjectSettings(var downloadDocs: Boolean = true,
                          var downloadSources: Boolean = true,
                          var prefixConfigurations: String = "") : ExternalProjectSettings() {

    override fun clone(): WemiProjectSettings {
        val clone = WemiProjectSettings()
        copyTo(clone)
        clone.downloadDocs = downloadDocs
        clone.downloadSources = downloadSources
        clone.prefixConfigurations = prefixConfigurations
        return clone
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        if (!super.equals(other)) return false

        other as WemiProjectSettings

        if (downloadDocs != other.downloadDocs) return false
        if (downloadSources != other.downloadSources) return false
        if (prefixConfigurations != other.prefixConfigurations) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + downloadDocs.hashCode()
        result = 31 * result + downloadSources.hashCode()
        result = 31 * result + prefixConfigurations.hashCode()
        return result
    }

    companion object {
        fun getInstance(project: Project):WemiProjectSettings? {
            val settings = ExternalSystemApiUtil.getSettings(project, WemiProjectSystemId).getLinkedProjectsSettings()
            return settings.firstOrNull { it is WemiProjectSettings } as? WemiProjectSettings
        }
    }
}