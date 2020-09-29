package com.darkyen.wemi.intellij.projectImport

import com.darkyen.wemi.intellij.WemiLauncher
import com.darkyen.wemi.intellij.WemiLauncherFileName
import com.darkyen.wemi.intellij.importing.reinstallWemiLauncher
import com.darkyen.wemi.intellij.options.ProjectImportOptions
import com.darkyen.wemi.intellij.util.executable
import com.darkyen.wemi.intellij.util.getWemiCompatibleSdk
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.application.WriteAction
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
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CancellationException

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
        return WriteAction.compute<List<Module>, Nothing> {
            importProjectStructureToIDE(this.projectNode!!, project, model)
        }
    }

    /**
     * Asks the current builder to ensure that target external project is defined.
     *
     * @param wizardContext             current wizard context
     * @throws ConfigurationException   if external project is not defined and can't be constructed
     */
    @Throws(ConfigurationException::class)
    fun ensureProjectIsDefined(wizardContext: WizardContext, projectImportOptions:ProjectImportOptions) {
        val project = wizardContext.project

        val projectFileDirectory = Paths.get(wizardContext.projectFileDirectory)
        val launcher = run {
            val wemiJar = projectFileDirectory.resolve(WemiLauncherFileName).toAbsolutePath()
            if (!Files.exists(wemiJar)) {
                return@run null
            }
            if (!Files.isRegularFile(wemiJar)) {
                throw ConfigurationException("Wemi launcher exists (file named \"wemi\"), but is not a file. Remove it to continue.")
            }
            try {
                wemiJar.executable = true
            } catch (e : Exception) {
                // Filesystem may not support the executable permission
            }

            val windowsShell = projectImportOptions.getWindowsShellExecutable(project)
                    ?: throw ConfigurationException("POSIX shell is not configured. Set it up to continue.")

            WemiLauncher(wemiJar, windowsShell)
        } ?: reinstallWemiLauncher(projectFileDirectory, "Failed to put Wemi launcher in the project directory", project)?.second
        ?: return

        val projectNode:ProjectNode = try {
            importWemiProjectStructure(project, launcher, projectImportOptions, activateToolWindow = false, modal = true).get()
        } catch (cancelled: CancellationException) {
            throw ConfigurationException("Cancelled")
        }
        this.projectNode = projectNode

        wizardContext.projectName = projectNode.name
        wizardContext.setProjectFileDirectory(projectNode.root.toAbsolutePath(), true)
        wizardContext.compilerOutputDirectory = projectNode.compileOutputPath?.toAbsolutePath()?.toString()
        getWemiCompatibleSdk(projectNode.javaTargetVersion)?.let { wizardContext.projectJdk = it }
    }

    // Not relevant
    override fun getList(): List<ProjectNode>? = projectNode?.let { listOf(it) } ?: emptyList()
    override fun setList(list: List<ProjectNode>?) {}
    override fun isMarked(element: ProjectNode?): Boolean = true
}