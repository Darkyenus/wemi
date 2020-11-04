package com.darkyen.wemi.intellij.util

import com.intellij.openapi.util.Key

interface SessionActivityTracker {
    fun stageBegin(name:String)
    fun stageProgress(done:Int, outOf:Int)
    fun stageEnd()

    fun taskBegin(name:String)
    fun taskEnd(output:String?, success:Boolean)

    fun sessionOutput(text:String, outputType: Key<*>)
}

inline fun <T> SessionActivityTracker.stage(name:String, action:()->T):T {
    stageBegin(name)
    try {
        return action()
    } finally {
        stageEnd()
    }
}

inline fun <T> SessionActivityTracker.stagesFor(name:String, items:Collection<T>, itemName:(T)->String, action:(T) -> Unit) {
    stage(name) {
        val total = items.size
        stageProgress(0, total)
        for ((index, item) in items.withIndex()) {
            stage(itemName(item)) {
                action(item)
            }
            stageProgress(index + 1, total)
        }
    }
}
