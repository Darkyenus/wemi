package wemi.util

import java.util.*
import kotlin.collections.ArrayList

/**
 * Checks that the thread won't enter the same block twice with the same token.
 *
 * Basically a single-thread [kotlin.synchronized].
 */
internal class CycleChecker<Token> {

    private val tokens: MutableMap<Thread, MutableList<Token>> = Collections.synchronizedMap(HashMap())

    internal fun enter(token: Token): List<Token>? {
        val thread = Thread.currentThread()
        val tokens = tokens.getOrPut(thread) { ArrayList() }
        val index = tokens.indexOf(token)
        if (index < 0) {
            tokens.add(token)
            return null
        }
        return tokens.subList(index, tokens.size)
    }

    internal fun leave() {
        val thread = Thread.currentThread()
        val tokenStack = tokens[thread] ?: throw IllegalStateException("Unbalanced enter-leave")
        tokenStack.removeAt(tokenStack.lastIndex)
        if (tokenStack.isEmpty()) {
            tokens.remove(thread)
        }
    }

    internal inline fun <Result> block(token: Token, failure: (List<Token>) -> Result, action: () -> Result): Result {
        val loop = enter(token)
        return if (loop == null) {
            try {
                action()
            } finally {
                leave()
            }
        } else {
            failure(loop)
        }
    }
}