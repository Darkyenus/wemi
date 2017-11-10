package com.darkyen.wemi.intellij.external

import com.darkyen.wemi.intellij.WemiLauncherSession
import com.darkyen.wemi.intellij.settings.WemiExecutionSettings
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.task.ExternalSystemTaskManager
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * May be run in a different process from the rest of the IDE
 */
class WemiTaskManager : ExternalSystemTaskManager<WemiExecutionSettings> {

    private val taskThreadBinding = mutableMapOf<ExternalSystemTaskId, Thread>()

    /**
     * @param id of this task run
     * @param taskNames qualified task names to run
     * @param projectPath
     * @param settings to use
     * @param jvmAgentSetup
     * @param listener
     */
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
            val vmOptions = if (jvmAgentSetup == null) settings.vmOptions else settings.vmOptions + jvmAgentSetup

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
                launcher.createTaskSession(settings.env, settings.isPassParentEnvs, tasks)
            } else {
                var javaVmExecutable = settings.javaVmExecutable
                if (javaVmExecutable.isBlank()) {
                    javaVmExecutable = "java"
                    listener.onTaskOutput(id, "Using implicit java executable from PATH", false)
                }

                launcher.createTaskSession(javaVmExecutable, vmOptions, settings.env, settings.isPassParentEnvs, tasks)
            }

            session.readOutputInteractive(
                    StreamToListener(id, listener, true),
                    StreamToListener(id, listener, false),
                    System.`in`
            )
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

    private class StreamToListener(val id:ExternalSystemTaskId, val listener: ExternalSystemTaskNotificationListener, val stdout:Boolean) : OutputStream() {

        val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)

        val inputBuffer: ByteBuffer = ByteBuffer.allocate(1024)
        val outputBuffer: CharBuffer = CharBuffer.allocate(1024)
        val outputSB = StringBuilder()

        init {
            inputBuffer.clear()
            outputBuffer.clear()
        }

        private fun decode() {
            inputBuffer.flip()
            while (true) {
                val result = decoder.decode(inputBuffer, outputBuffer, false)
                outputBuffer.flip()
                for (i in 0 until outputBuffer.limit()) {
                    val c = outputBuffer[i]
                    outputSB.append(c)

                    if (c == '\n') {
                        // Flush outputSB!
                        listener.onTaskOutput(id, outputSB.toString(), stdout)
                        outputSB.setLength(0)
                    }
                }

                if (!result.isOverflow) {
                    break
                }
            }

            inputBuffer.compact()
        }

        override fun write(b: Int) {
            inputBuffer.put(b.toByte())
            decode()
        }

        override fun write(b: ByteArray) {
            write(b, 0, b.size)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var offset = off
            var remaining = len

            while (remaining > 0) {
                val toConsume = minOf(remaining, inputBuffer.remaining())
                inputBuffer.put(b, offset, toConsume)
                offset += toConsume
                remaining -= toConsume
                decode()
            }
        }
    }

    override fun cancelTask(id: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean {
        var removed: Thread? = null
        synchronized(taskThreadBinding) {
            removed = taskThreadBinding.remove(id)
        }

        val thread = removed ?: return false

        listener.onTaskOutput(id, "Wemi resolution task is being cancelled\n", false)
        listener.beforeCancel(id)

        // This is a hack, but will have to do for now
        @Suppress("DEPRECATION")
        thread.stop()
        return true
    }
}
