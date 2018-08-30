package wemi.util

import wemi.BindingHolder
import wemi.Key
import wemi.Scope
import wemi.WemiKeyEvaluationListener
import wemi.boot.CLI
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

/**
 * [WemiKeyEvaluationListener] that stores relevant information about key evaluation and then
 * produces a human readable tree report about it.
 */
class TreeBuildingKeyEvaluationListener(private val printValues: Boolean) : WemiKeyEvaluationListener {

    private val roots = ArrayList<TreeNode<KeyData>>()
    private val stack = ArrayDeque<TreeNode<KeyData>>()
    private var cacheWrites = 0
    private var cacheReads = 0
    private var evaluations = 0

    override fun keyEvaluationStarted(fromScope: Scope, key: Key<*>) {
        val keyData = KeyData()
        keyData.fromScope = fromScope
        keyData.heading
                .format(foreground = Color.Black)
                .append(fromScope)
                .format(foreground = Color.Blue, format = Format.Bold)
                .append(key)
                .format()

        val node = TreeNode(keyData)
        if (stack.isEmpty()) {
            roots.add(node)
        } else {
            stack.peekLast().add(node)
        }
        stack.addLast(node)
    }

    override fun keyEvaluationFeature(feature: String) {
        stack.peekLast().value.features().add(feature)
        when (feature) {
            WemiKeyEvaluationListener.FEATURE_READ_FROM_CACHE -> cacheReads++
            WemiKeyEvaluationListener.FEATURE_WRITTEN_TO_CACHE -> cacheWrites++
        }
    }

    override fun keyEvaluationHasModifiers(modifierFromScope: Scope, modifierFromHolder: BindingHolder, amount: Int) {
        val keyData = stack.peekLast().value

        val sb = keyData.body()
        sb.append("\n")
                .format(foreground = Color.Cyan)
                .append("Modified at ")
                .format()
                .append(modifierFromScope)
                .format(foreground = Color.Cyan)
                .append(" by ")
                .format(foreground = Color.Cyan, format = Format.Underline)
                .append(modifierFromHolder)

        if (amount != 1) {
            sb.format(foreground = Color.White)
                    .append(' ')
                    .append(amount)
                    .append('×')
        }
        sb.format()
    }

    private fun popAndIndent(): TreeNode<KeyData> {
        val node = stack.removeLast()
        val keyData = node.value
        keyData.heading.append("  ")
        return node
    }

    override fun <Value> keyEvaluationSucceeded(key: Key<Value>, bindingFoundInScope: Scope?, bindingFoundInHolder: BindingHolder?, result: Value) {
        val node = popAndIndent()
        val keyData = node.value
        val h = keyData.heading
        h.append(CLI.ICON_SUCCESS).format(Color.White).append(" from ")
        when {
            bindingFoundInScope == null && bindingFoundInHolder == null ->
                h.format(foreground = Color.Magenta).append("default value").format()
            bindingFoundInScope != null && bindingFoundInHolder != null -> {
                h.format().append(bindingFoundInScope)
                if (bindingFoundInScope.scopeBindingHolders.last() !== bindingFoundInHolder) {
                    // Specify which holder only if it isn't nominal
                    h.format(Color.White).append(" in ").format(format = Format.Underline).append(bindingFoundInHolder).format()
                }
                evaluations++
            }
            else -> {
                // This is unexpected...
                h.format().append(bindingFoundInScope).append(" - ").append(bindingFoundInHolder)
            }
        }

        keyData.features?.let { features ->
            if (features.isEmpty()) {
                return@let
            }

            h.append(' ').format(Color.Magenta)
            var first = true
            for (feature in features) {
                if (first) {
                    first = false
                } else {
                    h.format().append(", ").format(Color.Magenta)
                }
                h.append(feature)
            }
            h.format()
        }

        if (printValues) {
            val body = keyData.body()
            val originalLength = body.length
            body.append('\n') // Body convention
            body.appendKeyResultLn(key, result)
            body.setLength(body.length - 1) // Strip newline appended by previous statement

            if (body.length == originalLength + 1) {
                // Key result is empty, abort
                body.setLength(originalLength)
            }
        }

        keyData.endTimeAndAppendTiming(node)
    }

    override fun keyEvaluationFailedByNoBinding(withAlternative: Boolean, alternativeResult: Any?) {
        val node = popAndIndent()
        val keyData = node.value
        keyData.heading.append(CLI.ICON_FAILURE).format(Color.Yellow)
        if (withAlternative) {
            keyData.heading.append(" used alternative")
        } else {
            keyData.heading.append(" failed with KeyNotAssignedException")
        }
        keyData.heading.format()
        keyData.endTimeAndAppendTiming(node)
    }

    override fun keyEvaluationFailedByError(exception: Throwable, fromKey: Boolean) {
        val node = popAndIndent()
        val keyData = node.value
        keyData.heading.append(CLI.ICON_EXCEPTION)
        keyData.heading.format(Color.Yellow)
        if (fromKey) {
            keyData.heading.append(" key evaluation failed")
        } else {
            keyData.heading.append(" modifier evaluation failed")
        }
        keyData.heading.format()

        keyData.exception = exception

        // Do not print the exception if it was thrown by a key deeper in the stack and we already printed it
        if (node.all { it.value.exception !== exception }) {
            val body = keyData.body()
            body.append('\n')
            body.format(Color.Red)
            body.appendWithStackTrace(exception)
            body.format()
        }

        keyData.endTimeAndAppendTiming(node)
    }

    fun appendResultTo(sb: StringBuilder) {
        printTree(roots, sb) { out ->
            out.append(this.heading)
            val body = this.body
            if (body != null) {
                out.append(body)
            }
        }
        sb.format(Color.White)
                .append("(cache reads: ").append(cacheReads)
                .append(", cache writes: ").append(cacheWrites)
                .append(", key evaluations: ").append(evaluations).append(')')
                .format()
    }

    fun reset() {
        roots.clear()
        stack.clear()
        cacheWrites = 0
        cacheReads = 0
        evaluations = 0
    }

    private class KeyData {

        val heading = StringBuilder()

        var body: StringBuilder? = null

        var features: ArrayList<String>? = null

        var fromScope:Scope? = null

        var exception: Throwable? = null

        private val startTime = System.nanoTime()

        var durationMs: Long = -1

        fun endTimeAndAppendTiming(node: TreeNode<KeyData>) {
            durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime)

            var ownMs = durationMs
            for (n in node) {
                ownMs -= n.value.durationMs
            }

            if (ownMs >= 1) {
                heading.append(' ').format(Color.Cyan).appendTimeDuration(ownMs).format()
            }
        }

        fun body(): StringBuilder {
            var b = body
            if (b == null) {
                b = StringBuilder()
                body = b
            }
            return b
        }

        fun features(): ArrayList<String> {
            var f = features
            if (f == null) {
                f = ArrayList()
                features = f
            }
            return f
        }

    }
}