package com.darkyen.wemi.intellij.importing

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.darkyen.wemi.intellij.module.WemiModuleComponent
import com.darkyen.wemi.intellij.module.WemiModuleType
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.project.AbstractExternalEntityData
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project

/**
 * Manages setting up Kotlin compiler settings for the project
 */
class WemiModuleComponentDataService : AbstractProjectDataService<WemiModuleComponentData, Module>() {

    override fun getTargetDataKey(): Key<WemiModuleComponentData> = WEMI_MODULE_DATA_KEY

    override fun importData(toImport: MutableCollection<DataNode<WemiModuleComponentData>>, projectData: ProjectData?, project: Project, modelsProvider: IdeModifiableModelsProvider) {
        for (dataNode in toImport) {
            val moduleData = dataNode.parent?.data as? ModuleData ?: continue
            val module = modelsProvider.findIdeModule(moduleData) ?: continue
            val wemiComponent = module.getComponent(WemiModuleComponent::class.java)
            wemiComponent.moduleType = dataNode.data.moduleType
        }
    }
}

/**
 * Key for [WemiModuleComponentData] that is added to [DataNode]<[ModuleData]>
 */
val WEMI_MODULE_DATA_KEY = Key.create(WemiModuleComponentData::class.java, 1000)

class WemiModuleComponentData(val moduleType: WemiModuleType) : AbstractExternalEntityData(WemiProjectSystemId)