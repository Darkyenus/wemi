package com.darkyen.wemi.intellij.execution

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.darkyen.wemi.intellij.execution.configurationEditor.WemiRunConfigurationEditor
import com.intellij.diagnostic.logging.LogConfigurationPanel
import com.intellij.execution.*
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.execution.ExternalSystemExecutionConsoleManager
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListenerAdapter
import com.intellij.openapi.externalSystem.service.execution.DefaultExternalSystemExecutionConsoleManager
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemExecuteTaskTask
import com.intellij.openapi.externalSystem.util.ExternalSystemBundle
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.SettingsEditorGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.StreamUtil
import com.intellij.util.ArrayUtil
import com.intellij.util.ExceptionUtil
import com.intellij.util.text.DateFormatUtil
import java.io.*

/**
 * Used when creating Wemi task run to run as IDE Configuration
 *
 * TODO Input piping to the task does not seem to work
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
        val runnableState = WemiRunnableState(settings, project, DefaultDebugExecutor.EXECUTOR_ID == executor.id, this, env)
        copyUserDataTo(runnableState)
        return runnableState
    }

    /** Based on [ExternalSystemRunConfiguration.MyRunnableState] of 172.4343
     *
     * Removed greeting/farewell folding because it was crashing. */
    private class WemiRunnableState(private val mySettings: ExternalSystemTaskExecutionSettings,
                                    private val myProject: Project,
                                    debug: Boolean,
                                    private val myConfiguration: WemiRunConfiguration,
                                    private val myEnv: ExecutionEnvironment) : ExternalSystemRunConfiguration.MyRunnableState(mySettings, myProject, debug, myConfiguration, myEnv) {

        @Throws(ExecutionException::class)
        override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult? {
            if (myProject.isDisposed) return null

            if (mySettings.taskNames.isEmpty()) {
                throw ExecutionException(ExternalSystemBundle.message("run.error.undefined.task"))
            }
            var jvmAgentSetup: String? = null
            if (debugPort > 0) {
                jvmAgentSetup = "-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=" + debugPort
            } else {
                val parametersList = myEnv.getUserData(ExternalSystemTaskExecutionSettings.JVM_AGENT_SETUP_KEY)
                if (parametersList != null) {
                    for (parameter in parametersList.list) {
                        if (parameter.startsWith("-agentlib:")) continue
                        if (parameter.startsWith("-agentpath:")) continue
                        if (parameter.startsWith("-javaagent:")) continue
                        throw ExecutionException(ExternalSystemBundle.message("run.invalid.jvm.agent.configuration", parameter))
                    }
                    jvmAgentSetup = parametersList.parametersString
                }
            }

            ApplicationManager.getApplication().assertIsDispatchThread()
            FileDocumentManager.getInstance().saveAllDocuments()

            val task = ExternalSystemExecuteTaskTask(myProject, mySettings, jvmAgentSetup)
            copyUserDataTo(task)

            val processHandler = WemiProcessHandler(task)
            @Suppress("UNCHECKED_CAST")
            val consoleManager: ExternalSystemExecutionConsoleManager<RunConfiguration, ExecutionConsole, ProcessHandler>
                    = ExternalSystemExecutionConsoleManager.EP_NAME.extensions.find { it.isApplicableFor(task) } ?: DefaultExternalSystemExecutionConsoleManager() as ExternalSystemExecutionConsoleManager<RunConfiguration, ExecutionConsole, ProcessHandler>

            val consoleView = consoleManager.attachExecutionConsole(task, myProject, myConfiguration, executor, myEnv, processHandler)
            Disposer.register(myProject, consoleView)

            ApplicationManager.getApplication().executeOnPooledThread {
                val startDateTime = DateFormatUtil.formatTimeWithSeconds(System.currentTimeMillis())
                val greeting: String = if (mySettings.taskNames.size > 1) {
                    ExternalSystemBundle.message("run.text.starting.multiple.task", startDateTime, mySettings.toString())
                } else {
                    ExternalSystemBundle.message("run.text.starting.single.task", startDateTime, mySettings.toString())
                }
                processHandler.notifyTextAvailable(greeting, ProcessOutputTypes.SYSTEM)
                val taskListener = object : ExternalSystemTaskNotificationListenerAdapter() {

                    private var myResetGreeting = true

                    override fun onTaskOutput(id: ExternalSystemTaskId, text: String, stdOut: Boolean) {
                        if (myResetGreeting) {
                            processHandler.notifyTextAvailable("\r", ProcessOutputTypes.SYSTEM)
                            myResetGreeting = false
                        }

                        consoleManager.onOutput(consoleView, processHandler, text, if (stdOut) ProcessOutputTypes.STDOUT else ProcessOutputTypes.STDERR)
                    }

                    override fun onFailure(id: ExternalSystemTaskId, e: Exception) {
                        val exceptionMessage = ExceptionUtil.getMessage(e)
                        val text = exceptionMessage ?: e.toString()
                        processHandler.notifyTextAvailable(text + '\n', ProcessOutputTypes.STDERR)
                        processHandler.notifyProcessTerminated(1)
                    }

                    override fun onEnd(id: ExternalSystemTaskId) {
                        val endDateTime = DateFormatUtil.formatTimeWithSeconds(System.currentTimeMillis())
                        val farewell: String
                        if (mySettings.taskNames.size > 1) {
                            farewell = ExternalSystemBundle.message("run.text.ended.multiple.task", endDateTime, mySettings.toString())
                        } else {
                            farewell = ExternalSystemBundle.message("run.text.ended.single.task", endDateTime, mySettings.toString())
                        }
                        processHandler.notifyTextAvailable(farewell, ProcessOutputTypes.SYSTEM)
                        processHandler.notifyProcessTerminated(0)
                    }
                }
                task.execute(*ArrayUtil.prepend(taskListener, ExternalSystemTaskNotificationListener.EP_NAME.extensions))
            }
            val result = DefaultExecutionResult(consoleView, processHandler)
            result.setRestartActions(*consoleManager.getRestartActions(consoleView))
            return result
        }
    }

    /** Based on [ExternalSystemRunConfiguration.MyProcessHandler] of 172.4343 */
    private class WemiProcessHandler(private val myTask: ExternalSystemExecuteTaskTask) : ProcessHandler(), AnsiEscapeDecoder.ColoredTextAcceptor {
        private val myAnsiEscapeDecoder = AnsiEscapeDecoder()
        private var myProcessInput: OutputStream? = null

        init {
            try {
                val inputStream = PipedInputStream()
                myProcessInput = PipedOutputStream(inputStream)
                myTask.putUserData<InputStream>(ExternalSystemRunConfiguration.RUN_INPUT_KEY, inputStream)
            } catch (e: IOException) {
                LOG.warn("Unable to setup process input", e)
            }

        }

        override fun notifyTextAvailable(text: String, outputType: Key<*>) {
            myAnsiEscapeDecoder.escapeText(text, outputType, this)
        }

        override fun destroyProcessImpl() {
            myTask.cancel()
            closeInput()
        }

        override fun detachProcessImpl() {
            notifyProcessDetached()
            closeInput()
        }

        override fun detachIsDefault(): Boolean {
            return false
        }

        override fun getProcessInput(): OutputStream? {
            return myProcessInput
        }

        public override fun notifyProcessTerminated(exitCode: Int) {
            super.notifyProcessTerminated(exitCode)
            closeInput()
        }

        override fun coloredTextAvailable(text: String, attributes: Key<*>) {
            super.notifyTextAvailable(text, attributes)
        }

        private fun closeInput() {
            StreamUtil.closeStream(myProcessInput)
            myProcessInput = null
        }
    }

    companion object {
        private val LOG = Logger.getInstance(WemiRunConfiguration::class.java)
    }
}