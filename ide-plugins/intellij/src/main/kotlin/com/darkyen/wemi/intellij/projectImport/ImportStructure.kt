package com.darkyen.wemi.intellij.projectImport

import com.darkyen.wemi.intellij.settings.WemiModuleService
import com.darkyen.wemi.intellij.settings.WemiModuleType
import com.darkyen.wemi.intellij.settings.WemiProjectService
import com.darkyen.wemi.intellij.settings.isWemiModule
import com.darkyen.wemi.intellij.util.*
import com.esotericsoftware.jsonbeans.JsonValue
import com.intellij.compiler.CompilerConfiguration
import com.intellij.ide.highlighter.ModuleFileType
import com.intellij.openapi.module.ModifiableModuleModel
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.StdModuleTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectEx
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.projectRoots.JdkUtil
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.CompilerProjectExtension
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.LanguageLevelModuleExtension
import com.intellij.openapi.roots.LanguageLevelProjectExtension
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.impl.ModifiableModelCommitter
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTable
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.util.Disposer
import com.intellij.pom.java.LanguageLevel
import org.jetbrains.jps.model.java.JavaResourceRootProperties
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootProperties
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.java.JpsJavaSdkType
import org.jetbrains.jps.model.module.JpsModuleSourceRootType
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.arguments.Freezable
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.idea.compiler.configuration.BaseKotlinCompilerSettings
import org.jetbrains.kotlin.idea.compiler.configuration.Kotlin2JvmCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.util.projectStructure.version
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path


/**
 *
 */
class ProjectNode constructor(val name:String, val root: Path) {

	var javaSourceVersion: LanguageLevel = getWemiCompatibleSdk()?.version?.maxLanguageLevel ?: LanguageLevel.HIGHEST
	var javaTargetVersion: JavaSdkVersion = JavaSdkVersion.fromLanguageLevel(javaSourceVersion)
	var compileOutputPath:Path? = null

	var kotlinCompilerData: Map<String, JsonValue> = emptyMap()

	val modules:MutableList<ModuleNode> = ArrayList()
	val tasks:MutableList<TaskNode> = ArrayList()
	val libraries:MutableList<LibraryNode> = ArrayList()
}

class ModuleNode constructor(
		val wemiProjectName:String,
		val wemiModuleType: WemiModuleType,
		val rootPath:Path,
		val classOutput:Path,
		val classOutputTesting:Path?,
		val javaSourceVersion:LanguageLevel?,
		val javaTargetVersion:JavaSdkVersion?,
		val javaHome:Path?) {

	class SourceResource {
		var sourceRoots:List<Path> = emptyList()
		var resourceRoots:List<Path> = emptyList()

		fun isEmpty():Boolean = sourceRoots.isEmpty() && resourceRoots.isEmpty()
	}

	val roots = SourceResource()
	val testRoots = SourceResource()
	val generatedRoots = SourceResource()
	val generatedTestRoots = SourceResource()

	var excludedRoots:Set<Path> = emptySet()

	/** Source root is not added when there are no sources. */
	fun hasNoSources():Boolean = roots.isEmpty() && testRoots.isEmpty() && generatedRoots.isEmpty() && generatedTestRoots.isEmpty() && excludedRoots.isEmpty()

	val ideModuleType:String
		get() = StdModuleTypes.JAVA.id

	val moduleDependencies:MutableList<ModuleDependencyNode> = ArrayList()

	val libraryDependencies:MutableList<LibraryDependencyNode> = ArrayList()
}

class ModuleDependencyNode(
		val onModuleName: String,
		val scope: DependencyScope,
		val isExported: Boolean)

class TaskNode(
		val taskName:String,
		val taskDescription:String
)

class LibraryNode(val libraryName: String) {
	var artifacts:List<Path> = emptyList()
	var sources:List<Path> = emptyList()
	var documentation:List<Path> = emptyList()
}

class LibraryDependencyNode(
		val onLibrary:LibraryNode,
		val scope: DependencyScope,
		val isExported:Boolean)

private val LOG = LoggerFactory.getLogger("Wemi.ImportStructure")

private fun <T: Freezable> settings(from: BaseKotlinCompilerSettings<T>, cache:T?):T {
	@Suppress("UNCHECKED_CAST")
	return cache ?: from.settings.unfrozen() as T
}

private inline fun ModuleNode.SourceResource.importSourceResource(contentRootFor:(Path, Boolean) -> ContentEntry, sourceType: JpsModuleSourceRootType<*>, resourceType:JpsModuleSourceRootType<*>, generated:Boolean) {
	for (s in sourceRoots) {
		val folder = contentRootFor(s, false).addSourceFolder(s.toUrl(), sourceType)
		if (generated) {
			(folder.jpsElement.properties as? JavaSourceRootProperties)?.isForGeneratedSources = true
		}
	}
	for (s in resourceRoots) {
		val folder = contentRootFor(s, false).addSourceFolder(s.toUrl(), resourceType)
		if (generated) {
			(folder.jpsElement.properties as? JavaResourceRootProperties)?.isForGeneratedSources = true
		}
	}
}

private fun fillLibrary(library:Library, node:LibraryNode) {
	val lib = library.modifiableModel
	var success = false
	try {
		// Remove old classes (keep documentation and sources, if the user added any)
		for (classRoot in lib.getFiles(OrderRootType.CLASSES)) {
			lib.removeRoot(classRoot.url, OrderRootType.CLASSES)
		}
		for (file in node.artifacts) {
			lib.addRoot(file.toClasspathUrl(), OrderRootType.CLASSES)
		}
		for (file in node.documentation) {
			lib.addRoot(file.toClasspathUrl(), OrderRootType.DOCUMENTATION)
		}
		for (file in node.sources) {
			lib.addRoot(file.toClasspathUrl(), OrderRootType.SOURCES)
		}
		success = true
	} finally {
		if (success) {
			lib.commit()
		} else {
			lib.dispose()
		}
	}
}

private fun ProjectNode.importProjectNode(project: Project, modifiableModuleModel: ModifiableModuleModel, getRootModel:(Module) -> ModifiableRootModel, libraryModel: LibraryTable.ModifiableModel, importProjectName:Boolean):List<Module> {
	project.getService(WemiProjectService::class.java).tasks = tasks.map { it.taskName to it.taskDescription }.toMap()

	// Import Kotlin compiler data
	run {
		val commonHolder by lazy { KotlinCommonCompilerArgumentsHolder.getInstance(project) }
		val jvmHolder by lazy { Kotlin2JvmCompilerArgumentsHolder.getInstance(project) }

		// Workaround because they froze their values
		var commonSettings: CommonCompilerArguments? = null
		var jvmSettings: K2JVMCompilerArguments? = null

		for ((k, v) in kotlinCompilerData) {
			// Process recognized patterns
			when (k) {
				// Common
				"kotlincLanguageVersion" -> {
					commonSettings = settings(commonHolder, commonSettings)
					commonSettings.languageVersion = v.asString()
				}
				"kotlincApiVersion" -> {
					commonSettings = settings(commonHolder, commonSettings)
					commonSettings.apiVersion = v.asString()
				}
				// JVM
				"kotlincJdkHome" -> {
					jvmSettings = settings(jvmHolder, jvmSettings)
					jvmSettings.jdkHome = v.asString()
				}
				"kotlincJvmTarget" -> {
					jvmSettings = settings(jvmHolder, jvmSettings)
					jvmSettings.jvmTarget = v.asString()
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

	// Import Java compiler data
	CompilerConfiguration.getInstance(project).projectBytecodeTarget = JpsJavaSdkType.complianceOption(javaTargetVersion.maxLanguageLevel.toJavaVersion())
	ProjectRootManager.getInstance(project)?.let { projectRootManager ->
		var projectSdk = getWemiCompatibleSdk(javaTargetVersion)
		if (projectSdk == null) {
			// Create a new SDK from passed-in javaHomes, if any
			val firstJavaHome = modules.mapNotNull { it.javaHome }.firstOrNull()
			if (firstJavaHome != null) {
				projectSdk = createWemiCompatibleSdk(firstJavaHome)
			}
		}
		projectRootManager.projectSdk = projectSdk
	}
	LanguageLevelProjectExtension.getInstance(project).languageLevel = javaSourceVersion

	compileOutputPath?.let { compileOutPath ->
		CompilerProjectExtension.getInstance(project)?.let { compilerProjectExtension ->
			compilerProjectExtension.compilerOutputUrl = compileOutPath.toUrl()
		}
	}

	// Import project info
	if (importProjectName && project.name != this.name && project is ProjectEx) {
		try {
			project.setProjectName(name)
		} catch (e:Exception) {
			LOG.warn("Failed to rename project", e)
		}
	}

	// Import module info
	val ideModulesToRemove = modifiableModuleModel.modules.toMutableSet()
	val wemiModules = HashMap<String, Module>()
	val allExcludedRoots = modules.flatMap { it.excludedRoots }

	for (module in modules) {
		val moduleRootPath = module.rootPath
		val ideModule = modifiableModuleModel.findModuleByName(module.wemiProjectName) ?: run {
			Files.createDirectories(moduleRootPath)
			val moduleFilePath = moduleRootPath.toRealPath().resolve("wemi.${module.wemiProjectName}${ModuleFileType.DOT_DEFAULT_EXTENSION}").toString()

			val newModule = modifiableModuleModel.newModule(moduleFilePath, module.ideModuleType)
			modifiableModuleModel.renameModule(newModule, module.wemiProjectName)
			newModule
		}
		wemiModules[module.wemiProjectName] = ideModule

		ideModulesToRemove.remove(ideModule)

		ideModule.getService(WemiModuleService::class.java).let { wemiModuleService ->
			wemiModuleService.wemiModuleType = module.wemiModuleType
			wemiModuleService.wemiProjectName = module.wemiProjectName
		}

		getRootModel(ideModule).let { rootModel ->
			rootModel.clear()

			val moduleJdk = module.javaTargetVersion?.let { getWemiCompatibleSdk(it) }
			if (moduleJdk != null) {
				rootModel.sdk = moduleJdk
			} else if (module.javaHome != null) {
				rootModel.sdk = createWemiCompatibleSdk(module.javaHome)
			} else {
				rootModel.inheritSdk()
			}

			module.javaSourceVersion?.let { rootModel.getModuleExtension(LanguageLevelModuleExtension::class.java).languageLevel = it }
			module.javaTargetVersion?.let { javaTargetVersion ->
				CompilerConfiguration.getInstance(project).setBytecodeTargetLevel(ideModule, JpsJavaSdkType.complianceOption(javaTargetVersion.maxLanguageLevel.toJavaVersion()))
			}

			val compilerModule = rootModel.getModuleExtension(CompilerModuleExtension::class.java)
			compilerModule.setCompilerOutputPath(module.classOutput.toUrl())
			compilerModule.setCompilerOutputPathForTests(module.classOutputTesting?.toUrl())
			compilerModule.inheritCompilerOutputPath(false)
			if (!module.hasNoSources()) {
				val contentRoots = ArrayList<Pair<Path, ContentEntry>>()
				contentRoots.add(moduleRootPath.toAbsolutePath().normalize() to rootModel.addContentEntry(moduleRootPath.toUrl()))

				val contentRootFor:(Path, forExclusion:Boolean) -> ContentEntry = root@{ path, forExclusion ->
					val absPath = path.toAbsolutePath().normalize()
					findExisting@for ((contentRootPath, contentRoot) in contentRoots) {
						if (absPath.startsWith(contentRootPath)) {
							// If contentRootPath contains excluded dir of any project and absPath is in that,
							// it can't be used, because it confuses IntelliJ.
							// This does not matter for actual exclusion folders.
							if (!forExclusion) {
								for (excluded in allExcludedRoots) {
									if (excluded.startsWith(contentRootPath) && absPath.startsWith(excluded)) {
										break@findExisting
									}
								}
							}
							return@root contentRoot
						}
					}
					val newRoot = rootModel.addContentEntry(path.toUrl())
					contentRoots.add(absPath to newRoot)
					newRoot
				}
				module.roots.importSourceResource(contentRootFor, JavaSourceRootType.SOURCE, JavaResourceRootType.RESOURCE, false)
				module.testRoots.importSourceResource(contentRootFor, JavaSourceRootType.TEST_SOURCE, JavaResourceRootType.TEST_RESOURCE, false)
				module.generatedRoots.importSourceResource(contentRootFor, JavaSourceRootType.SOURCE, JavaResourceRootType.RESOURCE, true)
				module.generatedTestRoots.importSourceResource(contentRootFor, JavaSourceRootType.TEST_SOURCE, JavaResourceRootType.TEST_RESOURCE, true)

				for (excludedRoot in module.excludedRoots) {
					contentRootFor(excludedRoot, true).addExcludeFolder(excludedRoot.toUrl())
				}
			}
		}
	}

	for (module in ideModulesToRemove) {
		// Remove modules that previously belonged to Wemi
		if (module.isWemiModule()) {
			modifiableModuleModel.disposeModule(module)
		}
	}

	for (libraryNode in libraries) {
		val library = libraryModel.getLibraryByName(libraryNode.libraryName) ?: libraryModel.createLibrary(libraryNode.libraryName)
		fillLibrary(library, libraryNode)
	}

	for (module in modules) {
		val ideModule = wemiModules[module.wemiProjectName]!!

		getRootModel(ideModule).let { rootModel ->
			for (moduleDependency in module.moduleDependencies) {
				val dependentOn = modifiableModuleModel.findModuleByName(moduleDependency.onModuleName)
				if (dependentOn == null) {
					rootModel.addInvalidModuleEntry(moduleDependency.onModuleName)
				} else {
					rootModel.addModuleOrderEntry(dependentOn)
				}.apply {
					scope = moduleDependency.scope
					isExported = moduleDependency.isExported
				}
			}

			for (libraryDependency in module.libraryDependencies) {
				val libraryName = libraryDependency.onLibrary.libraryName
				val projectLevelLibrary = libraryModel.getLibraryByName(libraryName)
				if (projectLevelLibrary != null) {
					val orderEntry = rootModel.addLibraryEntry(projectLevelLibrary)
					orderEntry.scope = libraryDependency.scope
					orderEntry.isExported = libraryDependency.isExported
				} else {
					// Dependency (Order) is added implicitly - module automatically depends on all of its module-level libraries
					val moduleLevelLibrary = rootModel.moduleLibraryTable.getLibraryByName(libraryName)
							?: rootModel.moduleLibraryTable.createLibrary(libraryName)
					fillLibrary(moduleLevelLibrary, libraryDependency.onLibrary)
				}
			}
		}
	}

	return wemiModules.values.toList()
}

/** CALL IN TransactionGuard!!! */
fun importProjectStructureToIDE(node:ProjectNode, project:Project, providedModifiableModuleModel: ModifiableModuleModel? = null, importProjectName:Boolean = false): List<Module> {
	val moduleModel = providedModifiableModuleModel ?: ModuleManager.getInstance(project).modifiableModel
	val rootModels = HashMap<Module, ModifiableRootModel>()
	val getRootModel:(Module) -> ModifiableRootModel = { module ->
		rootModels.getOrPut(module) { ModuleRootManager.getInstance(module).modifiableModel }
	}

	val libraryModel = LibraryTablesRegistrar.getInstance().getLibraryTable(project).modifiableModel

	val result = ArrayList<Module>()

	var success = false
	try {
		val importedModules = node.importProjectNode(project, moduleModel, getRootModel, libraryModel, importProjectName)
		success = true
		result.addAll(importedModules)
	} finally {
		if (providedModifiableModuleModel == null) {
			if (success) {
				ModifiableModelCommitter.multiCommit(rootModels.values, moduleModel)
			} else {
				for (model in rootModels.values) {
					model.dispose()
				}
				moduleModel.dispose()
			}
		} else {
			if (success) {
				for (model in rootModels.values) {
					model.commit()
				}
			} else {
				for (model in rootModels.values) {
					model.dispose()
				}
			}
		}
		if (success) {
			libraryModel.commit()
		} else {
			Disposer.dispose(libraryModel)
		}

		project.save()
	}

	return result
}