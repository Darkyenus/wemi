package com.darkyen.wemi.intellij.manager

import com.darkyen.wemi.intellij.WemiBuildFileExtensions
import com.darkyen.wemi.intellij.WemiLauncherFileName
import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.darkyen.wemi.intellij.external.WemiProjectResolver
import com.darkyen.wemi.intellij.external.WemiTaskManager
import com.darkyen.wemi.intellij.file.pathHasExtension
import com.darkyen.wemi.intellij.findWemiLauncher
import com.darkyen.wemi.intellij.settings.*
import com.esotericsoftware.jsonbeans.JsonReader
import com.intellij.execution.configurations.SimpleJavaParameters
import com.intellij.openapi.externalSystem.ExternalSystemAutoImportAware
import com.intellij.openapi.externalSystem.ExternalSystemConfigurableAware
import com.intellij.openapi.externalSystem.ExternalSystemManager
import com.intellij.openapi.externalSystem.ExternalSystemUiAware
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdkType
import com.intellij.openapi.projectRoots.JdkUtil
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.CharsetToolkit
import com.intellij.util.Function
import com.intellij.util.PathUtil
import com.intellij.util.containers.ContainerUtilRt
import icons.WemiIcons
import java.io.File
import java.net.URL

/**
 * Core of the import boilerplate.
 */
class WemiManager : ExternalSystemUiAware,
        ExternalSystemConfigurableAware,
        ExternalSystemAutoImportAware,
        ExternalSystemManager<WemiProjectSettings,
                WemiSettingsListener,
                WemiSystemSettings,
                WemiLocalSettings,
                WemiExecutionSettings> {

    //region ExternalSystemManager

    override fun getSystemId() = WemiProjectSystemId

    /**
     * Called when:
     * - opening Wemi in IDE menu
     */
    override fun getSettingsProvider(): Function<Project, WemiSystemSettings> = Function { project ->
        WemiSystemSettings.getInstance(project)
    }

    /**
     * Called when:
     * - IDE wants to access settings, for example during sync
     */
    override fun getLocalSettingsProvider(): Function<Project, WemiLocalSettings> = Function { project ->
        WemiLocalSettings.getInstance(project)
    }

    override fun getExecutionSettingsProvider(): Function<Pair<Project, String/*linked project path*/>, WemiExecutionSettings> {
        return Function { pair ->
            // Project may be template project!
            val project = pair.first

            val systemSettings = WemiSystemSettings.getInstance(project)
            val projectSettings = WemiProjectSettings.getInstance(project)

            val javaVmExecutablePath = systemSettings.wemiLauncherJre.let { jreName ->
                if (jreName.isBlank()) {
                    ""
                } else {
                    val jre = ProjectJdkTable.getInstance().findJdk(jreName) ?: throw RuntimeException("JRE $jreName not found")
                    val jreHomePath = jre.homePath ?: ""
                    if (!JdkUtil.checkForJre(jreHomePath)) {
                        throw RuntimeException("There is no JRE at '$jreHomePath'")
                    }
                    val javaSdkType = jre.sdkType as? JavaSdkType
                    javaSdkType?.getVMExecutablePath(jre) ?: throw RuntimeException("Could not retrieve VM executable path of JRE at '$jreHomePath'")
                }
            }


            WemiExecutionSettings(
                    findWemiLauncher(project, pair.second) ?: throw RuntimeException("Project $project does not have wemi launcher!"),
                    javaVmExecutablePath,
                    projectSettings?.downloadDocs ?: true,
                    projectSettings?.downloadSources ?: true,
                    projectSettings?.prefixConfigurations ?: "",
                    null //TODO
                    )
        }
    }

    /** Project resolver that may get executed from a different process */
    override fun getProjectResolverClass() = WemiProjectResolver::class.java

    /** @see [getProjectResolverClass] */
    override fun getTaskManagerClass() = WemiTaskManager::class.java

    /**
     * Used by [com.darkyen.wemi.intellij.importing.ImportFromWemiControl].
     *
     * Selects a folder with [WemiLauncherFileName] inside.
     * Title and description is ignored.
     *
     * !!! Currently is not used, probably due to a bug in intelliJ !!!
     */
    override fun getExternalProjectDescriptor(): FileChooserDescriptor {
        return FileChooserDescriptorFactory.createSingleFolderDescriptor()
    }

    //endregion

    /**
     * Called when:
     */
    override fun enhanceLocalProcessing(urls: MutableList<URL>) {
        urls.add(File(PathUtil.getJarPathForClass(Unit::class.java)).toURI().toURL())
        urls.add(File(PathUtil.getJarPathForClass(JsonReader::class.java)).toURI().toURL())
    }

    /**
     * Called when:
     * - starting [WemiProjectResolver] or [WemiTaskManager]
     */
    override fun enhanceRemoteProcessing(parameters: SimpleJavaParameters) {
        // Add Kotlin runtime. This is a workaround because at the moment RemoteExternalSystemCommunicationManager
        // has classpath without Kotlin and cannot call ProjectResolver
        val additionalClasspath = mutableListOf<String>()
        ContainerUtilRt.addIfNotNull(additionalClasspath, PathUtil.getJarPathForClass(Unit::class.java))
        ContainerUtilRt.addIfNotNull(additionalClasspath, PathUtil.getJarPathForClass(JsonReader::class.java))
        parameters.classPath.addAll(additionalClasspath)
        parameters.charset = CharsetToolkit.UTF8_CHARSET
        parameters.vmParametersList.addProperty("file.encoding", CharsetToolkit.UTF8)
        parameters.vmParametersList.addProperty(ExternalSystemConstants.EXTERNAL_SYSTEM_ID_KEY, WemiProjectSystemId.id)

        // To debug the retrieval of dependencies, add param:
        // -Dexternal.system.remote.communication.manager.debug.port=5009
    }

    //region ExternalSystemConfigurableAware

    /**
     * Called when:
     */
    override fun getConfigurable(project: Project): WemiConfigurable = WemiConfigurable(project)

    //endregion

    //region ExternalSystemAutoImportAware
    /**
     * Called when:
     * - File changes
     *
     * @param changedFileOrDirPath path to the changed file, sometimes relative, sometimes absolute
     */
    override fun getAffectedExternalProjectPath(changedFileOrDirPath: String, project: Project): String? {
        if (changedFileOrDirPath.pathHasExtension(WemiBuildFileExtensions)) {
            val start = changedFileOrDirPath.lastIndexOf('/')
            // Ignore hidden files
            if (start != -1 && start + 1 != changedFileOrDirPath.length
                    && changedFileOrDirPath[start+1] != '.') {
                return project.basePath
            }
        }
        return null
    }
    //endregion

    //region ExternalSystemUiAware

    /**
     * - WEMI Run Task -
     * Used when selected module in which the task will run
     * (not called for folders picked with [getExternalProjectConfigDescriptor]).
     *
     * @param targetProjectPath path of the wemi module selected
     */
    override fun getProjectRepresentationName(targetProjectPath: String, rootProjectPath: String?): String {
        // This will return the Project name, not module name, but this is the best we got
        return ExternalSystemApiUtil.getProjectRepresentationName(targetProjectPath, rootProjectPath)
    }

    /**
     * - WEMI Run Task -
     * Used when selecting folder with Wemi module in which the task will run (not home dir!)
     */
    override fun getExternalProjectConfigDescriptor(): FileChooserDescriptor? {
        return FileChooserDescriptor(false, true, false, false, false, false)
                .withDescription("Choose Wemi project folder")
                .withHideIgnored(true)
                .withShowFileSystemRoots(true)
                .withShowHiddenFiles(false)
    }

    override fun getProjectIcon() = WemiIcons.ACTION

    override fun getTaskIcon() = WemiIcons.ACTION

    //endregion
}

