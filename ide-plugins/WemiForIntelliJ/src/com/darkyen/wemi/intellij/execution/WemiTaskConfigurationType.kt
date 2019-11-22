package com.darkyen.wemi.intellij.execution

import com.darkyen.wemi.intellij.settings.WemiProjectSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import icons.WemiIcons
import javax.swing.Icon

/** Provides Run Configuration for Wemi tasks */
class WemiTaskConfigurationType : ConfigurationType {

    /** Displayed in the left list of configuration types */
    override fun getDisplayName(): String = "Wemi Task"

    /** For persistency */
    override fun getId(): String = "WemiRunConfiguration"

    override fun getIcon(): Icon = WemiIcons.ACTION

    override fun getConfigurationTypeDescription(): String = "Wemi build system task"

    val taskConfigurationFactory = object : ConfigurationFactory(this) {

        override fun getId(): String = this@WemiTaskConfigurationType.id

        override fun isApplicable(project: Project): Boolean {
            return WemiProjectSettings.getInstance(project) != null
        }

        override fun createTemplateConfiguration(project: Project): RunConfiguration {
            return WemiTaskConfiguration(project, this, "")
        }
    }

    private val CONFIGURATION_FACTORIES:Array<ConfigurationFactory> = arrayOf(taskConfigurationFactory)

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = CONFIGURATION_FACTORIES

    companion object {
        val INSTANCE:WemiTaskConfigurationType
            get() = ConfigurationTypeUtil.findConfigurationType(WemiTaskConfigurationType::class.java)
    }
}