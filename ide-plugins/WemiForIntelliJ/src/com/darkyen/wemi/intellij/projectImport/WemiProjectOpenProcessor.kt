package com.darkyen.wemi.intellij.projectImport

import com.darkyen.wemi.intellij.util.getWemiCompatibleSdk
import com.darkyen.wemi.intellij.wemiDirectoryToImport
import com.intellij.CommonBundle
import com.intellij.ide.IdeBundle
import com.intellij.ide.highlighter.ProjectFileType
import com.intellij.ide.impl.ProjectUtil
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.StorageScheme
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.projectImport.ProjectOpenProcessor
import icons.WemiIcons
import java.io.File
import java.nio.file.Paths
import javax.swing.Icon

/**
 *
 */
class WemiProjectOpenProcessor : ProjectOpenProcessor() {

	override fun getName(): String = "Wemi"

	override fun getIcon(): Icon = WemiIcons.ACTION

	override fun canOpenProject(file: VirtualFile): Boolean = wemiDirectoryToImport(file) != null

	override fun doOpenProject(virtualFile: VirtualFile, projectToClose: Project?, forceOpenInNewFrame: Boolean): Project? {
		val directoryToImport = wemiDirectoryToImport(virtualFile)!!
		val wizardContext = WizardContext(null, null)
		wizardContext.projectFileDirectory = directoryToImport.path
		if (wizardContext.projectName == null) {
			if (wizardContext.projectStorageFormat == StorageScheme.DEFAULT) {
				wizardContext.projectName = IdeBundle.message("project.import.default.name", name) + ProjectFileType.DOT_DEFAULT_EXTENSION
			} else {
				wizardContext.projectName = IdeBundle.message("project.import.default.name.dotIdea", name)
			}
		}

		wizardContext.projectJdk = getWemiCompatibleSdk()
		val dotIdeaFilePath = wizardContext.projectFileDirectory + File.separator + Project.DIRECTORY_STORE_FOLDER
		val projectFilePath = wizardContext.projectFileDirectory + File.separator + wizardContext.projectName + ProjectFileType.DOT_DEFAULT_EXTENSION
		val dotIdeaFile = File(dotIdeaFilePath)
		val projectFile = File(projectFilePath)
		var pathToOpen:String = if (wizardContext.projectStorageFormat == StorageScheme.DEFAULT) {
			projectFilePath
		} else {
			dotIdeaFile.parent
		}
		var shouldOpenExisting = false
		var importToProject = true
		if (projectFile.exists() || dotIdeaFile.exists()) {
			if (ApplicationManager.getApplication().isHeadlessEnvironment) {
				shouldOpenExisting = true
				importToProject = true
			} else {
				val existingName: String
				if (dotIdeaFile.exists()) {
					existingName = "an existing project"
					pathToOpen = dotIdeaFile.parent
				} else {
					existingName = "'" + projectFile.name + "'"
					pathToOpen = projectFilePath
				}
				val result = Messages.showYesNoCancelDialog(
						projectToClose,
						IdeBundle.message("project.import.open.existing", existingName, projectFile.parent, directoryToImport.name),
						IdeBundle.message("title.open.project"),
						IdeBundle.message("project.import.open.existing.openExisting"),
						IdeBundle.message("project.import.open.existing.reimport"),
						CommonBundle.message("button.cancel"),
						Messages.getQuestionIcon())
				if (result == Messages.CANCEL) return null
				shouldOpenExisting = result == Messages.YES
				importToProject = !shouldOpenExisting
			}
		}
		val projectToOpen = (if (shouldOpenExisting) {
			try {
				ProjectManagerEx.getInstanceEx().loadProject(Paths.get(pathToOpen).toAbsolutePath())
			} catch (e: Exception) {
				return null
			}
		} else {
			ProjectManagerEx.getInstanceEx().newProject(wizardContext.projectName, pathToOpen, true, false)
		}) ?: return null
		if (!forceOpenInNewFrame) {
			closePreviousProject(projectToClose)
		}
		ProjectUtil.updateLastProjectLocation(pathToOpen)
		ProjectManagerEx.getInstanceEx().openProject(projectToOpen)

		if (importToProject) {
			projectToOpen.save()
			StartupManager.getInstance(projectToOpen).runWhenProjectIsInitialized {
				importWemiProject(projectToOpen, initial=true)
			}
		}
		projectToOpen.isInitialized

		return projectToOpen
	}

	private fun closePreviousProject(projectToClose:Project?) {
		// TODO(jp): Replace with ProjectUtil.closePreviousProject(projectToClose) after it becomes available

		val openProjects = ProjectUtil.getOpenProjects()
		if (openProjects.isNotEmpty()) {
			val exitCode = ProjectUtil.confirmOpenNewProject(true)
			if (exitCode == 1) {
				val project = projectToClose ?: openProjects[openProjects.size - 1]
				ProjectManagerEx.getInstanceEx().closeAndDispose(project)
			}
		}
	}
}