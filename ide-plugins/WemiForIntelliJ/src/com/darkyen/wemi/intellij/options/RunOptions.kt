package com.darkyen.wemi.intellij.options

import com.darkyen.wemi.intellij.ui.PropertyEditorPanel
import com.darkyen.wemi.intellij.ui.TaskListPropertyEditor
import com.darkyen.wemi.intellij.util.ListOfStringArraysXmlSerializer

/** General base for configurations that run tasks. */
abstract class RunOptions : WemiLauncherOptions() {

    var tasks:List<Array<String>> = emptyList()

    init {
        register(RunOptions::tasks, ListOfStringArraysXmlSerializer::class)
    }

    fun shortTaskSummary():String {
        val tasks = tasks
        return if (tasks.isEmpty()) {
            "No Wemi tasks"
        } else if (tasks.size == 1) {
            tasks[0].joinToString(" ")
        } else {
            "${tasks[0].joinToString(" ")}; (${tasks.size - 1} more)"
        }
    }

    fun copyTo(o: RunOptions) {
        o.tasks = tasks
        copyTo(o as WemiLauncherOptions)
    }

    override fun createUi(panel: PropertyEditorPanel) {
        panel.edit(TaskListPropertyEditor(this::tasks))
        panel.gap(5)
        super.createUi(panel)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RunOptions) return false
        if (!super.equals(other)) return false

        if (tasks != other.tasks) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + tasks.hashCode()
        return result
    }
}