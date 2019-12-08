package com.darkyen.wemi.intellij.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.util.xmlb.XmlSerializerUtil

/** Modules with this component belong to Wemi. */
@Service
@State(name = "wemi.WemiModuleService")
class WemiModuleService : PersistentStateComponent<WemiModuleService> {

    /** Name of Wemi project corresponding to this module. */
    var wemiProjectName:String? = null

    /** Type of Wemi module this represents. Null if this module is not a wemi module. */
    var wemiModuleType: WemiModuleType? = null

    override fun getState(): WemiModuleService = this

    override fun loadState(state: WemiModuleService) {
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