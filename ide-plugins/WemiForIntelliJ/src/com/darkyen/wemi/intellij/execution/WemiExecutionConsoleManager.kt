package com.darkyen.wemi.intellij.execution

import com.darkyen.wemi.intellij.WemiProjectSystemId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTask
import com.intellij.openapi.externalSystem.service.execution.DefaultExternalSystemExecutionConsoleManager

/**
 * Manages the execution console, which looks like a standard Run panel.
 *
 * Doesn't do much differently from standard, except for showing that a Wemi task is running.
 */
class WemiExecutionConsoleManager : DefaultExternalSystemExecutionConsoleManager() {

    override fun getExternalSystemId() = WemiProjectSystemId

    override fun isApplicableFor(task: ExternalSystemTask) = task.id.projectSystemId == WemiProjectSystemId
}