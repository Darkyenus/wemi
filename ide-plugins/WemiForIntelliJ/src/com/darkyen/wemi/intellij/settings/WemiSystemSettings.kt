package com.darkyen.wemi.intellij.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemSettings
import com.intellij.openapi.externalSystem.settings.ExternalSystemSettingsListener
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.XCollection

/**
 * Wemi settings (mostly) boilerplate.
 *
 * Based on GradleSettings
 */
@State(name = "WemiSettings", storages = [(Storage("wemi.xml"))])
class WemiSystemSettings(project: Project) :
        AbstractExternalSystemSettings<WemiSystemSettings, WemiProjectSettings, WemiSettingsListener>
            (WemiSettingsListener.TOPIC, project),
        PersistentStateComponent<WemiSystemSettings.WemiSystemSettingsState> {

    //region State

    /**
     * JRE to use for launching of the wemi(.jar) launcher. Can be empty, then default system java will be used.
     */
    var wemiLauncherJre:String = ""

    //endregion

    override fun subscribe(listener: ExternalSystemSettingsListener<WemiProjectSettings>, parentDisposable: Disposable) {
        doSubscribe(WemiSettingsListenerDelegatingAdapter(listener), parentDisposable)
    }

    @Deprecated("(by Jetbrains) Present for backwards compatibility")
    override fun subscribe(listener: ExternalSystemSettingsListener<WemiProjectSettings>) {
        subscribe(listener, project)
    }

    override fun copyExtraSettingsFrom(settings: WemiSystemSettings) {
        wemiLauncherJre = settings.wemiLauncherJre
    }

    override fun getState(): WemiSystemSettingsState {
        //This is what GradleSettings does
        val state = WemiSystemSettingsState()
        fillState(state)
        state.wemiLauncherJre = wemiLauncherJre
        return state
    }

    @Suppress("RedundantOverride")//Not redundant, changes signature slightly
    override fun loadState(state: WemiSystemSettingsState) {
        super.loadState(state)
        wemiLauncherJre = state.wemiLauncherJre
    }

    override fun checkSettings(old: WemiProjectSettings, current: WemiProjectSettings) {
        // Notify listeners if anything changed
        if (old != current) {
            publisher.onProjectSettingsChanged(old, current)
        }
    }

    class WemiSystemSettingsState : State<WemiProjectSettings> {

        var wemiLauncherJre:String = ""
        private val projectSettings:java.util.TreeSet<WemiProjectSettings> = java.util.TreeSet()

        @Suppress("DEPRECATION") // @AbstractCollection is deprecated, but don't remove it for backwards compatibility
        @com.intellij.util.xmlb.annotations.AbstractCollection(surroundWithTag = false, elementTypes = [(WemiProjectSettings::class)])
        @XCollection(elementTypes = [(WemiProjectSettings::class)])
        override fun getLinkedExternalProjectsSettings(): Set<WemiProjectSettings> {
            return projectSettings
        }

        override fun setLinkedExternalProjectsSettings(settings: Set<WemiProjectSettings>?) {
            if (settings != null) {
                projectSettings.addAll(settings)
            }
        }
    }

    companion object {
        fun getInstance(project: Project): WemiSystemSettings {
            return ServiceManager.getService(project, WemiSystemSettings::class.java)
        }
    }
}
