package com.darkyen.wemi.intellij.importing

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.project.AbstractExternalEntityData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService
import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.Freezable
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.idea.compiler.configuration.BaseKotlinCompilerSettings
import org.jetbrains.kotlin.idea.compiler.configuration.Kotlin2JvmCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder

/**
 * Manages setting up Kotlin compiler settings for the project
 */
class KotlinCompilerSettingsDataService : AbstractProjectDataService<KotlinCompilerSettingsData, Void>() {

    override fun getTargetDataKey(): Key<KotlinCompilerSettingsData> = WEMI_KOTLIN_COMPILER_SETTINGS_KEY

    private fun <T: Freezable> settings(from:BaseKotlinCompilerSettings<T>, cache:T?):T {
        @Suppress("UNCHECKED_CAST")
        if (cache == null) {
            return from.settings.unfrozen() as T
        } else {
            return cache
        }
    }

    override fun importData(toImport: MutableCollection<DataNode<KotlinCompilerSettingsData>>, projectData: ProjectData?, project: Project, modelsProvider: IdeModifiableModelsProvider) {
        val commonHolder by lazy { KotlinCommonCompilerArgumentsHolder.getInstance(project) }
        val jvmHolder by lazy { Kotlin2JvmCompilerArgumentsHolder.getInstance(project) }

        // Workaround because they froze their values
        var commonSettings:CommonCompilerArguments? = null
        var jvmSettings:K2JVMCompilerArguments? = null

        for (dataNode in toImport) {
            for ((k, v) in dataNode.data.items) {
                // Process recognized patterns
                when (k) {
                // Common
                    "languageVersion" -> {
                        commonSettings = settings(commonHolder, commonSettings)
                        commonSettings.languageVersion = v
                    }
                    "apiVersion" -> {
                        commonSettings = settings(commonHolder, commonSettings)
                        commonSettings.apiVersion = v
                    }
                // JVM
                    "jdkHome" -> {
                        jvmSettings = settings(jvmHolder, jvmSettings)
                        jvmSettings.jdkHome = v
                    }
                    "jvmTarget" -> {
                        jvmSettings = settings(jvmHolder, jvmSettings)
                        jvmSettings.jvmTarget = v
                    }
                }
            }
        }

        if (commonSettings != null) {
            commonHolder.settings = commonSettings
        }
        if (jvmSettings != null) {
            jvmHolder.settings = jvmSettings
        }
    }
}

/**
 * Key for [KotlinCompilerSettingsData] that is added to [DataNode]<[ProjectData]>
 */
val WEMI_KOTLIN_COMPILER_SETTINGS_KEY = Key.create(KotlinCompilerSettingsData::class.java, 1000)

class KotlinCompilerSettingsData(val items:Map<String, String>) : AbstractExternalEntityData(WemiProjectSystemId)