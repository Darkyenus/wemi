package com.darkyen.wemi.intellij.execution

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.externalSystem.service.execution.AbstractExternalSystemTaskConfigurationType
import com.intellij.openapi.project.Project

@Suppress("UNCHECKED_CAST")
/**
 * Provides Run Configuration for Wemi tasks
 */
class WemiTaskConfigurationType : AbstractExternalSystemTaskConfigurationType(WemiProjectSystemId) {

    /**
     * Displayed in the left list of configuration types
     */
    override fun getDisplayName(): String = "Wemi Task"

    /**
     * For persistency
     */
    override fun getId(): String = "WemiRunConfiguration"


    private val CONFIGURATION_FACTORIES:Array<ConfigurationFactory> = arrayOf(
            object : ConfigurationFactory(this) {
                override fun createTemplateConfiguration(project: Project): RunConfiguration {
                    return WemiRunConfiguration(project, this)
                }
            }
    )

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = CONFIGURATION_FACTORIES
}