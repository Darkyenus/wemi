package com.darkyen.wemi.intellij.execution

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.externalSystem.service.execution.AbstractExternalSystemRunConfigurationProducer
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement

/**
 * Provides ability to right-click a file/main method/something (?) and have "run" configuration automatically
 * generated.
 *
 * TODO This is currently not implemented.
 */
class WemiRunConfigurationProducer(type: WemiTaskConfigurationType)
    : AbstractExternalSystemRunConfigurationProducer(type) {

    override fun setupConfigurationFromContext(configuration: ExternalSystemRunConfiguration?,
                                               context: ConfigurationContext?,
                                               sourceElement: Ref<PsiElement>?): Boolean {
        return false
    }
}