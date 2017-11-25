package com.darkyen.wemi.intellij.execution

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.darkyen.wemi.intellij.execution.configurationEditor.WemiRunConfigurationEditor
import com.intellij.diagnostic.logging.LogConfigurationPanel
import com.intellij.execution.*
import com.intellij.execution.configurations.*
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.*
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

            var settings = mySettings
            if (settings.taskNames.isEmpty()) {
                throw ExecutionException(ExternalSystemBundle.message("run.error.undefined.task"))
            }

            var jvmAgentSetup: String? = null
            if (debugPort > 0) {
                if (settings.scriptParameters == WEMI_CONFIGURATION_ARGUMENT_SUPPRESS_DEBUG) {
                    settings = settings.clone()
                    val newEnv = settings.env.toMutableMap()
                    newEnv[WEMI_SUPPRESS_DEBUG_ENVIRONMENT_VARIABLE_NAME] = debugPort.toString()
                    settings.env = newEnv
                } else {
                    jvmAgentSetup = "-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=" + debugPort
                }
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

            val task = ExternalSystemExecuteTaskTask(myProject, settings, jvmAgentSetup)
            copyUserDataTo(task)

            val processHandler = WemiProcessHandler(task)
            @Suppress("UNCHECKED_CAST")
            val consoleManager: ExternalSystemExecutionConsoleManager<RunConfiguration, ExecutionConsole, ProcessHandler>
                    = ExternalSystemExecutionConsoleManager.EP_NAME.extensions.find { it.isApplicableFor(task) } ?: DefaultExternalSystemExecutionConsoleManager() as ExternalSystemExecutionConsoleManager<RunConfiguration, ExecutionConsole, ProcessHandler>

            val consoleView = consoleManager.attachExecutionConsole(task, myProject, myConfiguration, executor, myEnv, processHandler)
            Disposer.register(myProject, consoleView)

            ApplicationManager.getApplication().executeOnPooledThread {
                val startDateTime = DateFormatUtil.formatTimeWithSeconds(System.currentTimeMillis())
                val taskName = settings.taskNames?.joinToString(" ")?:"" + settings.vmOptions?:""

                val greeting: String = if (settings.taskNames.size > 1) {
                    ExternalSystemBundle.message("run.text.starting.multiple.task", startDateTime, taskName)
                } else {
                    ExternalSystemBundle.message("run.text.starting.single.task", startDateTime, taskName)
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
                        if (settings.taskNames.size > 1) {
                            farewell = ExternalSystemBundle.message("run.text.ended.multiple.task", endDateTime, taskName)
                        } else {
                            farewell = ExternalSystemBundle.message("run.text.ended.single.task", endDateTime, taskName)
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
        /**
         * This stream receives user input
         */
        private var userInput: OutputStream? = try {
            val inputStream = PipedInputStream()
            val userInput = PipedOutputStream(inputStream)
            myTask.putUserData<InputStream>(ExternalSystemRunConfiguration.RUN_INPUT_KEY, inputStream)
            userInput
        } catch (e: IOException) {
            LOG.warn("Unable to setup process input", e)
            null
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

        override fun detachIsDefault(): Boolean = false

        //TODO Input piping to the task does not work, waiting for https://intellij-support.jetbrains.com/hc/en-us/community/posts/115000708024-How-to-implement-user-input-in-the-ExternalSystem-API-
        override fun getProcessInput(): OutputStream? = //userInput
                object : OutputStream() {
                    override fun write(b: Int) {}

                    override fun flush() {
                        notifyTextAvailable("<User input through plugin not supported yet>\n", ProcessOutputTypes.SYSTEM)
                    }
                }

        public override fun notifyProcessTerminated(exitCode: Int) {
            super.notifyProcessTerminated(exitCode)
            closeInput()
        }

        override fun coloredTextAvailable(text: String, attributes: Key<*>) {
            super.notifyTextAvailable(text, attributes)
        }

        private fun closeInput() {
            StreamUtil.closeStream(userInput)
            userInput = null
        }
    }

    companion object {
        private val LOG = Logger.getInstance(WemiRunConfiguration::class.java)

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
        val WEMI_CONFIGURATION_ARGUMENT_SUPPRESS_DEBUG = "wemi.suppress-debug"

        val WEMI_SUPPRESS_DEBUG_ENVIRONMENT_VARIABLE_NAME = "WEMI_RUN_DEBUG_PORT"
    }
}