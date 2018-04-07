package com.darkyen.wemi.intellij.module

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleComponent
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Modules with this component belong to Wemi.
 */
@com.intellij.openapi.components.State(name = "wemi.WemiModuleComponent", reloadable = true, defaultStateAsResource = false)
class WemiModuleComponent(val module: Module) : ModuleComponent, PersistentStateComponent<WemiModuleComponent> {

    /**
     * Type of Wemi module this represents. Null if this module is not a wemi module.
     */
    var moduleType:WemiModuleType? = null

    override fun getState(): WemiModuleComponent = this

    override fun loadState(state: WemiModuleComponent) {
        XmlSerializerUtil.copyBean(state, this)
    }
}

enum class WemiModuleType {
    /**
     * Standard Wemi project module.
     */
    PROJECT,
    /**
     * Build script-holding module.
     * For example, it is not possible to execute tasks on these modules.
     */
    BUILD_SCRIPT
}