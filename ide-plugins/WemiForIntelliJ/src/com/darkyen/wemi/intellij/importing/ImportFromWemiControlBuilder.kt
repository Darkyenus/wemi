package com.darkyen.wemi.intellij.importing

import com.darkyen.wemi.intellij.WemiLauncher
import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.intellij.externalSystem.JavaProjectData
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.service.project.wizard.AbstractExternalProjectImportBuilder
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.*
import com.intellij.openapi.roots.LanguageLevelProjectExtension
import icons.WemiIcons
import java.io.File

/**
 * Used when importing unlinked project.
 *
 * TODO Check if it does what it should
 */
class ImportFromWemiControlBuilder(dataManager: ProjectDataManager, val launcher: WemiLauncher)
    : AbstractExternalProjectImportBuilder<ImportFromWemiControl>(dataManager, ImportFromWemiControl(), WemiProjectSystemId) {

    override fun getIcon() = WemiIcons.WEMI

    override fun getName() = "WEMI"

    override fun doPrepare(context: WizardContext) {
        ""
    }

    /**
     * Called after the (preview?) sync is done and project will be updated with data from [com.darkyen.wemi.intellij.external.WemiProjectResolver]
     *
     * @param dataNode from project resolver
     * @param project that is being created (TODO: VERIFY!)
     */
    override fun beforeCommit(dataNode: DataNode<ProjectData>, project: Project) {
        // Setup language level to what is reported by the resolved data
        val javaProjectNode = ExternalSystemApiUtil.find(dataNode, JavaProjectData.KEY) ?: return
        val languageLevelExtension = LanguageLevelProjectExtension.getInstance(project)
        languageLevelExtension.languageLevel = javaProjectNode.data.languageLevel
    }

    override fun applyExtraSettings(context: WizardContext) {
        val node = externalProjectNode ?: return

        // Setup context with retrieved settings
        // Offers user sensible defaults in the wizard
        val javaProjectNode = ExternalSystemApiUtil.find(node, JavaProjectData.KEY)
        if (javaProjectNode != null) {
            val data = javaProjectNode.data
            context.compilerOutputDirectory = data.compileOutputPath
            val version = data.jdkVersion
            val jdk = findJdk(version)
            if (jdk != null) {
                context.projectJdk = jdk
            }
        }
    }

    override fun getExternalProjectConfigToUse(file: File): File {
        return if (file.isDirectory) {
            file
        } else {
            file.parentFile
        }
    }

    private fun findJdk(version: JavaSdkVersion): Sdk? {
        val javaSdk = JavaSdk.getInstance()
        val javaSdks = ProjectJdkTable.getInstance().getSdksOfType(javaSdk)
        var candidate: Sdk? = null
        for (sdk in javaSdks) {
            val v = javaSdk.getVersion(sdk)
            if (v == version) {
                return sdk
            } else if (candidate == null && v != null && v.maxLanguageLevel.isAtLeast(version.maxLanguageLevel)) {
                candidate = sdk
            }
        }
        return candidate
    }

    override fun isSuitableSdkType(sdkType: SdkTypeId?) = sdkType === JavaSdk.getInstance()

}