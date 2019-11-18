package com.darkyen.wemi.intellij.execution

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.darkyen.wemi.intellij.execution.configurationEditor.WemiRunConfigurationEditor
import com.intellij.diagnostic.logging.LogConfigurationPanel
import com.intellij.execution.ExecutionBundle
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.SettingsEditorGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key

/**
 * Used when creating Wemi task run to run as IDE Configuration
 */
class WemiRunConfiguration(project: Project,
                                   factory: ConfigurationFactory) : ExternalSystemRunConfiguration(WemiProjectSystemId, project, factory, "") {

    override fun getConfigurationEditor(): SettingsEditor<ExternalSystemRunConfiguration> {
        val group = SettingsEditorGroup<ExternalSystemRunConfiguration>()
        group.addEditor(ExecutionBundle.message("run.configuration.configuration.tab.title"), WemiRunConfigurationEditor(project))
        group.addEditor(ExecutionBundle.message("logs.tab.title"), LogConfigurationPanel())

        @Suppress("UNCHECKED_CAST")
        return group
    }

    override fun getState(executor: Executor, env: ExecutionEnvironment): RunProfileState? {
        val state = super.getState(executor, env) as MyRunnableState
        state.putUserData(EXECUTION_DEFER_DEBUG_TO_WEMI, settings.scriptParameters == WEMI_CONFIGURATION_ARGUMENT_SUPPRESS_DEBUG)
        return state
    }

    companion object {

        /**
         * If true, debug (through vmParameters) should not be applied to the Wemi process but to the processes launched by Wemi.
         */
        val EXECUTION_DEFER_DEBUG_TO_WEMI = Key.create<Boolean>("EXECUTION_DEFER_DEBUG_TO_WEMI")

        /**
         * Value to which [ExternalSystemTaskExecutionSettings.getScriptParameters] is set,
         * if debug agent should not be applied to the Wemi launcher directly.
         *
         * Then, if, for example, 'run' task is executed with the debugger enabled, IDE will connect to the project
         * that is being run, not to the Wemi launcher which runs it.
         *
         * In that case, IDE will launch Wemi with environment variable [WEMI_SUPPRESS_DEBUG_ENVIRONMENT_VARIABLE_NAME]
         * (= "WEMI_RUN_DEBUG_PORT") so that the project can be run with the debugger on the correct port.
         */
        const val WEMI_CONFIGURATION_ARGUMENT_SUPPRESS_DEBUG = "wemi.defer-debug"

        const val WEMI_SUPPRESS_DEBUG_ENVIRONMENT_VARIABLE_NAME = "WEMI_RUN_DEBUG_PORT"
    }
}