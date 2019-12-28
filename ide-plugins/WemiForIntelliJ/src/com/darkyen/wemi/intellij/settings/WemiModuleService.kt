package com.darkyen.wemi.intellij.settings

import com.darkyen.wemi.intellij.util.StringXmlSerializer
import com.darkyen.wemi.intellij.util.XmlSerializable
import com.darkyen.wemi.intellij.util.enumXmlSerializer
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.module.Module

/** Modules with this component belong to Wemi. */
@Service
@State(name = "wemi.WemiModuleService")
class WemiModuleService : XmlSerializable() {

    /** Name of Wemi project corresponding to this module. */
    var wemiProjectName:String = ""

    /** Type of Wemi module this represents. Null if this module is not a wemi module. */
    var wemiModuleType: WemiModuleType = WemiModuleType.NON_WEMI_MODULE

    init {
        register(WemiModuleService::wemiProjectName, StringXmlSerializer::class)
        register(WemiModuleService::wemiModuleType, enumXmlSerializer())
    }
}

enum class WemiModuleType {
    /** This module does not belong to Wemi. */
    NON_WEMI_MODULE,
    /** Standard Wemi project module. */
    PROJECT,
    /** Build script-holding module.
     * For example, it is not possible to execute tasks on these modules. */
    BUILD_SCRIPT
}

fun Module.getWemiModuleType():WemiModuleType {
    return getService(WemiModuleService::class.java)?.wemiModuleType ?: WemiModuleType.NON_WEMI_MODULE
}

fun Module.isWemiModule():Boolean {
    return getWemiModuleType() != WemiModuleType.NON_WEMI_MODULE
}