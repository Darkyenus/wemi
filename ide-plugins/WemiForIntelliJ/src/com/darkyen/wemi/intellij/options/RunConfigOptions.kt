package com.darkyen.wemi.intellij.options

import com.darkyen.wemi.intellij.ui.BooleanPropertyEditor
import com.darkyen.wemi.intellij.ui.PropertyEditorPanel
import com.darkyen.wemi.intellij.util.BooleanXmlSerializer

/** Options for Run configurations. */
class RunConfigOptions : RunOptions() {
    // Bookkeeping settings
    var allowRunningInParallel:Boolean = false
    var generatedName:Boolean = false

    // Run settings
    var debugWemiItself:Boolean = false

    init {
        register(RunConfigOptions::allowRunningInParallel, BooleanXmlSerializer::class)
        register(RunConfigOptions::generatedName, BooleanXmlSerializer::class)
        register(RunConfigOptions::debugWemiItself, BooleanXmlSerializer::class)
    }

    fun copyTo(o: RunConfigOptions) {
        copyTo(o as RunOptions)
        o.allowRunningInParallel = allowRunningInParallel
        o.generatedName = generatedName
        o.debugWemiItself = debugWemiItself
    }

    override fun createUi(panel: PropertyEditorPanel) {
        super.createUi(panel)
        panel.edit(BooleanPropertyEditor(this::debugWemiItself, "Debug build scripts", "Debugger will be attached to the Wemi process itself", "Debugger will be attached to any forked process"))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RunConfigOptions) return false
        if (!super.equals(other)) return false

        if (allowRunningInParallel != other.allowRunningInParallel) return false
        if (generatedName != other.generatedName) return false
        if (debugWemiItself != other.debugWemiItself) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + allowRunningInParallel.hashCode()
        result = 31 * result + generatedName.hashCode()
        result = 31 * result + debugWemiItself.hashCode()
        return result
    }
}