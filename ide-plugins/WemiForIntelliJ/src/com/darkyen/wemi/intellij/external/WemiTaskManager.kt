package com.darkyen.wemi.intellij.external

import com.darkyen.wemi.intellij.WemiLauncherSession
import com.darkyen.wemi.intellij.execution.WemiRunConfiguration
import com.darkyen.wemi.intellij.settings.WemiExecutionSettings
import com.darkyen.wemi.intellij.util.LineReadingOutputStream
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.task.ExternalSystemTaskManager
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

        var session: WemiLauncherSession? = null
        try {
            synchronized(taskThreadBinding) {
                taskThreadBinding.put(id, Thread.currentThread())
            }

            val launcher = settings!!.launcher

            var env = settings.env.toMutableMap()

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

                    env[WemiRunConfiguration.WEMI_SUPPRESS_DEBUG_ENVIRONMENT_VARIABLE_NAME] = port
                    jvmAgent = null
                } else {
                    listener.onTaskOutput(id, "WEMI: Wanted to defer debug, but failed to extract port from: $jvmAgent\n", false)
                }
            }

            val vmOptions = if (jvmAgent == null) settings.vmOptions else settings.vmOptions + jvmAgent

            val prefixConfigurations = settings.prefixConfigurationsArray()
            val tasks = taskNames.map {
                val sb = StringBuilder()
                settings.projectName?.let {
                    sb.append(it).append('/')
                }
                for (prefixConfiguration in prefixConfigurations) {
                    sb.append(prefixConfiguration).append(':')
                }
                sb.append(it)
                sb.toString()
            }

            session = if (settings.javaVmExecutable.isBlank() && vmOptions.isEmpty()) {
                launcher.createTaskSession(env, settings.isPassParentEnvs, tasks)
            } else {
                var javaVmExecutable = settings.javaVmExecutable
                if (javaVmExecutable.isBlank()) {
                    javaVmExecutable = "java"
                    listener.onTaskOutput(id, "WEMI: Using implicit java executable from PATH\n", false)
                }

                launcher.createTaskSession(javaVmExecutable, vmOptions, env, settings.isPassParentEnvs, tasks)
            }

            val stdout = LineReadingOutputStream { line -> listener.onTaskOutput(id, line.toString(), true) }
            val stderr = LineReadingOutputStream { line -> listener.onTaskOutput(id, line.toString(), false) }
            session.readOutputInteractive(
                    stdout,
                    stderr,
                    System.`in`
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
