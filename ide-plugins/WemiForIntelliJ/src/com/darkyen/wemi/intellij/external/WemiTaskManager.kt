package com.darkyen.wemi.intellij.external

import com.darkyen.wemi.intellij.ExternalStatusTracker
import com.darkyen.wemi.intellij.WemiLauncherSession
import com.darkyen.wemi.intellij.execution.WemiTaskConfiguration
import com.darkyen.wemi.intellij.settings.WemiExecutionSettings
import com.darkyen.wemi.intellij.util.LineReadingOutputStream
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration.RUN_INPUT_KEY
import com.intellij.openapi.externalSystem.task.ExternalSystemTaskManager
import java.io.InputStream
import java.util.regex.Pattern

/**
 * May be run in a different process from the rest of the IDE
 */
class WemiTaskManager : ExternalSystemTaskManager<WemiExecutionSettings> {

    private val taskThreadBinding = mutableMapOf<ExternalSystemTaskId, Thread>()

    private val JVM_AGENT_PORT_PATTERN = Pattern.compile("-agentlib:jdwp=.*address=(\\d+).*")

    /**
     * @param id of this task run
     * @param taskNames qualified task names to run
     * @param projectPath
     * @param settings to use
     * @param jvmAgentSetup
     * @param listener
     */
    @Throws(ExternalSystemException::class)
    override fun executeTasks(id: ExternalSystemTaskId,
                              taskNames: List<String>,
                              projectPath: String,
                              settings: WemiExecutionSettings?,
                              jvmAgentSetup: String?,
                              listener: ExternalSystemTaskNotificationListener) {
        var beingThrown:Throwable? = null
        val tracker = ExternalStatusTracker(id, listener)
        tracker.stage = "Executing"

        var session: WemiLauncherSession? = null
        try {
            synchronized(taskThreadBinding) {
                taskThreadBinding.put(id, Thread.currentThread())
            }

            val launcher = settings?.launcher
                    ?: throw IllegalStateException("Wemi launcher is missing")

            val env = settings.env.toMutableMap()

            val deferDebug = settings.deferDebugToWemi
            if (deferDebug == null) {
                listener.onTaskOutput(id, "WEMI: Defer debug flag is not set", false)
            }

            var jvmAgent = jvmAgentSetup
            if (jvmAgent.isNullOrBlank()) {
                jvmAgent = null
            }

            if (deferDebug != false && jvmAgent != null) {
                val matcher = JVM_AGENT_PORT_PATTERN.matcher(jvmAgent)
                if (matcher.matches()) {
                    val port = matcher.group(1)

                    env[WemiTaskConfiguration.WEMI_FORCE_DEBUG_IN_RUN_ENV] = port
                    jvmAgent = null
                } else {
                    listener.onTaskOutput(id, "WEMI: Wanted to defer debug, but failed to extract port from: $jvmAgent\n", false)
                }
            }

            val vmOptions = if (jvmAgent == null) settings.jvmArguments else settings.jvmArguments + jvmAgent

            val prefixConfigurations = settings.prefixConfigurationsArray()
            val tasks = taskNames.map {
                val sb = StringBuilder()
                settings.projectName?.let { projectName ->
                    sb.append(projectName).append('/')
                }
                for (prefixConfiguration in prefixConfigurations) {
                    sb.append(prefixConfiguration).append(':')
                }
                sb.append(it)
                sb.toString()
            }

            val javaExecutable = if (settings.javaVmExecutable.isBlank()) "java" else settings.javaVmExecutable
            session = launcher.createTaskSession(javaExecutable, vmOptions, env, settings.isPassParentEnvs, tasks, tracker)

            val stdout = LineReadingOutputStream { line -> listener.onTaskOutput(id, line.toString(), true) }
            val stderr = LineReadingOutputStream { line -> listener.onTaskOutput(id, line.toString(), false) }
            session.readOutputInteractive(
                    stdout,
                    stderr,
                    settings.getUserData(RUN_INPUT_KEY) as InputStream
            )
            stdout.close()
            stderr.close()

        } catch (e:ThreadDeath) {
            return
        } catch (e:Throwable) {
            throw ExternalSystemException(e).apply { beingThrown = this }
        } finally {
            try {
                synchronized(taskThreadBinding) {
                    taskThreadBinding.remove(id)
                }
                session?.done()
            } catch (e:Throwable) {
                beingThrown?.addSuppressed(e)
            }
        }
    }

    override fun cancelTask(id: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean {
        var removed: Thread? = null
        synchronized(taskThreadBinding) {
            removed = taskThreadBinding.remove(id)
        }

        val thread = removed ?: return false

        listener.onTaskOutput(id, "WEMI: Task is being cancelled\n", false)
        listener.beforeCancel(id)

        // This is a hack, but will have to do for now
        @Suppress("DEPRECATION")
        thread.stop()
        return true
    }
}
