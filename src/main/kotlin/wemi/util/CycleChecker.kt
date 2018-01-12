package wemi.util

import java.util.*
import kotlin.collections.ArrayList

/**
 * Checks that the thread won't enter the same block twice with the same token.
 *
 * Basically a single-thread [kotlin.synchronized].
 */
internal class CycleChecker<in Token> {

    private val tokens:MutableMap<Thread, MutableList<Token>> = Collections.synchronizedMap(HashMap())

    internal fun enter(token:Token):Boolean {
        val thread = Thread.currentThread()
        val tokens = tokens.getOrPut(thread) { ArrayList() }
        if (tokens.contains(token)) {
            return false
        }
        tokens.add(token)
        return true
    }

    internal fun leave() {
        val thread = Thread.currentThread()
        val tokenStack = tokens[thread] ?: throw IllegalStateException("Unbalanced enter-leave")
        tokenStack.removeAt(tokenStack.lastIndex)
        if (tokenStack.isEmpty()) {
            tokens.remove(thread)
        }
    }

    internal inline fun <Result> block(token: Token, failure:() -> Result, action:()->Result):Result {
        return if (enter(token)) {
            try {
                action()
            } finally {
                leave()
            }
        } else {
            failure()
        }
    }


}