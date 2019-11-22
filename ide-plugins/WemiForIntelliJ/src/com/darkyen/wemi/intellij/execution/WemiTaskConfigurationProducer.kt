package com.darkyen.wemi.intellij.execution

import com.intellij.execution.JavaExecutionUtil
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.configurations.ConfigurationFactory
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
		val mainClass = getMainClassName(context.psiLocation ?: return false) ?: return false
		return configuration.options.tasks == buildRunMainTaskForClassName(mainClass)
	}

	private fun buildRunMainTaskForClassName(className:String):List<Array<String>> {
		return listOf(arrayOf("runMain", className))
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
		val mainClass = getMainClassName(sourceElement.get() ?: return false) ?: return false
		configuration.options.tasks = buildRunMainTaskForClassName(mainClass)
		configuration.useSuggestedName()
		return true
	}


}