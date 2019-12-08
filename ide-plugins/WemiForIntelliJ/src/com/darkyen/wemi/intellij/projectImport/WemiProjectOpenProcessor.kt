package com.darkyen.wemi.intellij.projectImport

import com.darkyen.wemi.intellij.wemiDirectoryToImport
import com.intellij.CommonBundle
import com.intellij.ide.IdeBundle
import com.intellij.ide.highlighter.ProjectFileType
import com.intellij.ide.impl.NewProjectUtil
import com.intellij.ide.impl.ProjectUtil
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.StorageScheme
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.CompilerProjectExtension
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.ModulesProvider
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.projectImport.ProjectImportBuilder
import com.intellij.projectImport.ProjectOpenProcessor
import icons.WemiIcons
import org.jetbrains.annotations.NonNls
import java.io.File
import java.io.IOException
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
		val builder = ProjectImportBuilder.EXTENSIONS_POINT_NAME.findExtensionOrFail(ImportFromWemiBuilder::class.java)
		return try {
			builder.isUpdate = false
			val wizardContext = WizardContext(null, null)
			wizardContext.projectFileDirectory = directoryToImport.path
			if (wizardContext.projectName == null) {
				if (wizardContext.projectStorageFormat == StorageScheme.DEFAULT) {
					wizardContext.projectName = IdeBundle.message("project.import.default.name", name) + ProjectFileType.DOT_DEFAULT_EXTENSION
				} else {
					wizardContext.projectName = IdeBundle.message("project.import.default.name.dotIdea", name)
				}
			}
			val defaultProject = ProjectManager.getInstance().defaultProject
			var jdk = ProjectRootManager.getInstance(defaultProject).projectSdk
			if (jdk == null) {
				jdk = ProjectJdkTable.getInstance().findMostRecentSdkOfType(JavaSdk.getInstance())
			}
			wizardContext.projectJdk = jdk
			val dotIdeaFilePath = wizardContext.projectFileDirectory + File.separator + Project.DIRECTORY_STORE_FOLDER
			val projectFilePath = wizardContext.projectFileDirectory + File.separator + wizardContext.projectName +
					ProjectFileType.DOT_DEFAULT_EXTENSION
			val dotIdeaFile = File(dotIdeaFilePath)
			val projectFile = File(projectFilePath)
			var pathToOpen: String?
			pathToOpen = if (wizardContext.projectStorageFormat == StorageScheme.DEFAULT) {
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
			val projectToOpen: Project?
			projectToOpen = if (shouldOpenExisting) {
				try {
					ProjectManagerEx.getInstanceEx().loadProject(Paths.get(pathToOpen).toAbsolutePath())
				} catch (e: Exception) {
					return null
				}
			} else {
				ProjectManagerEx.getInstanceEx().newProject(wizardContext.projectName, pathToOpen!!, true, false)
			}
			if (projectToOpen == null) return null
			if (importToProject) {
				if (!builder.validate(projectToClose, projectToOpen)) {
					return null
				}
				projectToOpen.save()
				ApplicationManager.getApplication().runWriteAction {
					val jdk1 = wizardContext.projectJdk
					if (jdk1 != null) {
						NewProjectUtil.applyJdkToProject(projectToOpen, jdk1)
					}
					val projectDirPath = wizardContext.projectFileDirectory
					val path = projectDirPath + if (StringUtil.endsWithChar(projectDirPath, '/')) "classes" else "/classes"
					val extension = CompilerProjectExtension.getInstance(projectToOpen)
					extension?.compilerOutputUrl = getUrl(path)
				}
				builder.commit(projectToOpen, null, ModulesProvider.EMPTY_MODULES_PROVIDER)
			}
			if (!forceOpenInNewFrame) {
				NewProjectUtil.closePreviousProject(projectToClose)
			}
			ProjectUtil.updateLastProjectLocation(pathToOpen)
			ProjectManagerEx.getInstanceEx().openProject(projectToOpen)
			projectToOpen
		} finally {
			builder.cleanup()
		}
	}










	// --

	fun getUrl(@NonNls path: String): String {
		var path = path
		try {
			path = FileUtil.resolveShortWindowsName(path)
		} catch (ignored: IOException) {
		}
		return VfsUtilCore.pathToUrl(path)
	}
}