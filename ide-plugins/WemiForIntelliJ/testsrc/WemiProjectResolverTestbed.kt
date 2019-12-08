@file:Suppress("ConstantConditionIf")

import com.darkyen.wemi.intellij.WemiLauncherFileName
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationEvent
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import java.io.File
import java.lang.Exception

/**
 *
 */
/*

val DEBUG_WEMI_LAUNCHER = false

fun main(args: Array<String>) {
    val resolver = WemiProjectResolver()
    val taskId = ExternalSystemTaskId.create(WemiProjectSystemId, ExternalSystemTaskType.RESOLVE_PROJECT, "0")
    val projectRoot = File("../../test repositories/hello").canonicalFile

    val settings: WemiExecutionSettings
    if (DEBUG_WEMI_LAUNCHER) {
        settings = WemiExecutionSettings(File(projectRoot, WemiLauncherFileName).absolutePath, "/usr/bin/java", true, true, "", null, false)
        settings.withVmOption("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005")
    } else {
        settings = WemiExecutionSettings(File(projectRoot, WemiLauncherFileName).absolutePath, "java", true, true, "", null, false)
    }

    val externalSystemTaskNotificationListener = object : ExternalSystemTaskNotificationListener {
        override fun onSuccess(id: ExternalSystemTaskId) {
            System.err.println("onSuccess: $id")
        }

        override fun onFailure(id: ExternalSystemTaskId, e: Exception) {
            System.err.println("onFailure: $id")
            e.printStackTrace(System.err)
        }

        override fun onTaskOutput(id: ExternalSystemTaskId, text: String, stdOut: Boolean) {
            System.err.println("onTaskOutput: $id")
            if (stdOut) {
                System.out.print("OUTPUT START\n"+text+"OUTPUT END\n")
            } else {
                System.err.print("OUTPUT START\n"+text+"OUTPUT END\n")
            }
        }

        override fun onStatusChange(event: ExternalSystemTaskNotificationEvent) {
            System.err.println("onStatusChange: $event")
        }

        override fun onCancel(id: ExternalSystemTaskId) {
            System.err.println("onCacnel: $id")
        }

        override fun onEnd(id: ExternalSystemTaskId) {
            System.err.println("onEnd: $id")
        }

        override fun beforeCancel(id: ExternalSystemTaskId) {
            System.err.println("beforeCancel: $id")
        }

        override fun onStart(id: ExternalSystemTaskId, workingDir: String?) {
            System.err.println("onStart: $id $workingDir")
        }

        override fun onStart(id: ExternalSystemTaskId) {
            TODO("not implemented")
        }
    }
    val project = resolver.resolveProjectInfo(taskId,
            projectRoot.absolutePath, false, settings, externalSystemTaskNotificationListener)

    println("Done: $project")
}*/
