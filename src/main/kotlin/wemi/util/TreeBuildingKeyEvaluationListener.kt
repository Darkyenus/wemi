package wemi.util

import wemi.BindingHolder
import wemi.Key
import wemi.Scope
import wemi.WemiKeyEvaluationListener
import wemi.boot.CLI
import java.util.*

/**
 * [WemiKeyEvaluationListener] that stores relevant information about key evaluation and then
 * produces a human readable tree report about it.
 */
class TreeBuildingKeyEvaluationListener : WemiKeyEvaluationListener {

    private val roots = ArrayList<TreeNode<KeyData>>()
    private val stack = ArrayDeque<TreeNode<KeyData>>()

    override fun keyEvaluationStarted(fromScope: Scope, key: Key<*>) {
        val keyData = KeyData()
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

    override fun keyEvaluationHasModifiers(modifierFromScope: Scope, modifierFromHolder: BindingHolder, amount: Int) {
        val keyData = stack.peekLast().value

        val sb = keyData.body()
        sb.append("\n\t")
                .format(foreground = Color.Cyan)
                .append("Modified at ")
                .append(modifierFromScope)
                .append(" by ")
                .format(foreground = Color.Cyan, format = Format.Underline)
                .append(modifierFromHolder)
                .format()

        if (amount != 1) {
            sb.format(foreground = Color.White)
                    .append(' ')
                    .append(amount)
                    .append('×')
                    .format()
        }
    }

    private fun popAndIndent():KeyData {
        val keyData = stack.removeLast().value
        keyData.heading.append("  ")
        return keyData
    }

    override fun <Value> keyEvaluationSucceeded(bindingFoundInScope: Scope?, bindingFoundInHolder: BindingHolder?, result: Value) {
        val keyData = popAndIndent()
        keyData.heading.append(CLI.ICON_SUCCESS).format(Color.White).append(" from ")
        when {
            bindingFoundInScope == null -> keyData.heading.format(foreground = Color.Magenta).append("default value").format()
            bindingFoundInHolder == null -> {
                keyData.heading.format(Color.Magenta).append("cache")
                        .format(Color.White).append(" in ")
                        .format().append(bindingFoundInScope)
            }
            else -> {
                keyData.heading.format().append(bindingFoundInScope)
                if (bindingFoundInScope.scopeBindingHolders.last() !== bindingFoundInHolder) {
                    // Specify which holder only if it isn't nominal
                    keyData.heading.format(Color.White).append(" in ").format(format = Format.Underline).append(bindingFoundInHolder).format()
                }
            }
        }
    }

    override fun keyEvaluationFailedByNoBinding(withAlternative: Boolean, alternativeResult: Any?) {
        val keyData = popAndIndent()
        keyData.heading.append(CLI.ICON_FAILURE).format(Color.Yellow)
        if (withAlternative) {
            keyData.heading.append(" used alternative")
        } else {
            keyData.heading.append(" failed with KeyNotAssignedException")
        }
        keyData.heading.format()
    }

    override fun keyEvaluationFailedByError(exception: Throwable, fromKey: Boolean) {
        val keyData = popAndIndent()
        keyData.heading.append(CLI.ICON_EXCEPTION)
        keyData.heading.format(Color.Yellow)
        if (fromKey) {
            keyData.heading.append(" key evaluation failed")
        } else {
            keyData.heading.append(" modifier evaluation failed")
        }
        keyData.heading.format()

        val body = keyData.body()
        body.append('\n')
        body.format(Color.Red)
        body.appendWithStackTrace(exception)
        body.format()
    }

    fun toTree(sb:StringBuilder) {
        printTree(roots, sb) { out ->
            out.append(this.heading)
            val body = this.body
            if (body != null) {
                out.append(body)
            }
        }
    }

    fun reset() {
        roots.clear()
        stack.clear()
    }

    private class KeyData {

        val heading = StringBuilder()

        var body:StringBuilder? = null

        fun body():StringBuilder {
            var b = body
            if (b == null) {
                b = StringBuilder()
                body = b
            }
            return b
        }

    }
}