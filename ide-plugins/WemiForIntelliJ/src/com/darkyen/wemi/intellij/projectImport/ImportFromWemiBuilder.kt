package com.darkyen.wemi.intellij.projectImport

import com.darkyen.wemi.intellij.findWemiLauncher
import com.darkyen.wemi.intellij.importing.reinstallWemiLauncher
import com.darkyen.wemi.intellij.options.ProjectImportOptions
import com.darkyen.wemi.intellij.util.getWemiCompatibleSdk
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.module.ModifiableModuleModel
import com.intellij.openapi.module.Module
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdkType
import com.intellij.openapi.projectRoots.SdkTypeId
import com.intellij.openapi.roots.ui.configuration.ModulesProvider
import com.intellij.packaging.artifacts.ModifiableArtifactModel
import com.intellij.projectImport.ProjectImportBuilder
import icons.WemiIcons
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Paths

/**
 * Used when importing (unlinked?) project.
 */
class ImportFromWemiBuilder : ProjectImportBuilder<ProjectNode>() {

    private var projectNode: ProjectNode? = null

    override fun getName() = "Wemi"

    override fun getIcon() = WemiIcons.ACTION

    override fun setOpenProjectSettingsAfter(on: Boolean) {}

    override fun isSuitableSdkType(sdkType: SdkTypeId?): Boolean {
        return sdkType is JavaSdkType && !(sdkType as JavaSdkType).isDependent
    }

    override fun commit(project: Project, model: ModifiableModuleModel?, modulesProvider: ModulesProvider?, artifactModel: ModifiableArtifactModel?): List<Module> {
        return import(this.projectNode!!, project, model)
    }

    /**
     * Asks current builder to ensure that target external project is defined.
     *
     * @param wizardContext             current wizard context
     * @throws ConfigurationException   if external project is not defined and can't be constructed
     */
    @Throws(ConfigurationException::class)
    fun ensureProjectIsDefined(wizardContext: WizardContext, projectImportOptions:ProjectImportOptions) {
        val project = wizardContext.project

        val projectFileDirectory = Paths.get(wizardContext.projectFileDirectory)
        val launcher = findWemiLauncher(projectFileDirectory)
                ?: reinstallWemiLauncher(projectFileDirectory, "Failed to put Wemi launcher in the project directory", project)?.second
                ?: return

        val projectNode:ProjectNode = refreshProject(project, launcher, projectImportOptions).get()
        this.projectNode = projectNode

        wizardContext.projectName = projectNode.name
        wizardContext.projectFileDirectory = projectNode.root.toAbsolutePath().toString()
        wizardContext.compilerOutputDirectory = projectNode.compileOutputPath?.toAbsolutePath()?.toString()
        getWemiCompatibleSdk(projectNode.javaTargetVersion)?.let { wizardContext.projectJdk = it }
    }

    // Not relevant
    override fun getList(): List<ProjectNode>? = projectNode?.let { listOf(it) } ?: emptyList()
    override fun setList(list: List<ProjectNode>?) {}
    override fun isMarked(element: ProjectNode?): Boolean = true

    private companion object {
        val LOG: Logger = LoggerFactory.getLogger(ImportFromWemiBuilder::class.java)
    }
}