package com.darkyen.wemi.intellij.execution

import com.darkyen.wemi.intellij.settings.WemiModuleService
import com.darkyen.wemi.intellij.settings.WemiModuleType
import com.intellij.execution.JavaExecutionUtil
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.idea.run.KotlinRunConfigurationProducer

/**
 * Provides ability to right-click a file/main method/something (?) and have "run" configuration automatically
 * generated.
 */
class WemiTaskConfigurationProducer : LazyRunConfigurationProducer<WemiTaskConfiguration>() {

	override fun getConfigurationFactory(): ConfigurationFactory {
		return WemiTaskConfigurationType.INSTANCE.taskConfigurationFactory
	}

	override fun isConfigurationFromContext(configuration: WemiTaskConfiguration, context: ConfigurationContext): Boolean {
		val psiElement = context.psiLocation ?: return false
		val moduleName = getWemiModuleName(psiElement) ?: return false
		val mainClass = getMainClassName(psiElement) ?: return false
		return configuration.options.tasks == buildRunMainTaskForClassName(moduleName, mainClass)
	}

	private fun buildRunMainTaskForClassName(moduleName:String, className:String):List<Array<String>> {
		return listOf(arrayOf("$moduleName/runMain", className))
	}

	private fun getWemiModuleName(from:PsiElement):String? {
		val moduleService = ModuleUtilCore.findModuleForPsiElement(from)?.getService(WemiModuleService::class.java) ?: return null
		if (moduleService.wemiModuleType == WemiModuleType.PROJECT) {
			return moduleService.wemiProjectName
		}
		return null
	}

	private fun getMainClassName(from:PsiElement):String? {
		// Java class
		ApplicationConfigurationType.getMainClass(from)
				?.let { return JavaExecutionUtil.getRuntimeQualifiedName(it) }

		// Kotlin file or class
		KotlinRunConfigurationProducer.getEntryPointContainer(from)
				?.let { KotlinRunConfigurationProducer.getStartClassFqName(it) }
				?.let { return it }

		// Unknown
		return null
	}

	override fun setupConfigurationFromContext(configuration: WemiTaskConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
		val psiElement = sourceElement.get() ?: return false
		val moduleName = getWemiModuleName(psiElement) ?: return false
		val mainClass = getMainClassName(psiElement) ?: return false
		configuration.options.tasks = buildRunMainTaskForClassName(moduleName, mainClass)
		configuration.useSuggestedName()
		return true
	}


}