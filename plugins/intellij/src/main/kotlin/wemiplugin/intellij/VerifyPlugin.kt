package wemiplugin.intellij

import com.jetbrains.plugin.structure.base.plugin.PluginCreationFail
import com.jetbrains.plugin.structure.base.plugin.PluginCreationSuccess
import com.jetbrains.plugin.structure.base.plugin.PluginProblem
import com.jetbrains.plugin.structure.intellij.plugin.IdePluginManager
import org.slf4j.LoggerFactory
import wemi.Value
import wemi.util.div
import wemiplugin.intellij.IntelliJ.verifyPluginStrictness

private val LOG = LoggerFactory.getLogger("VerifyPlugin")

enum class Strictness {
	/** Validation will always pass */
	LENIENT,
	/** Validation will pass as long as there are no failures */
	ALLOW_WARNINGS,
	/** Validation will pass only if there are no warnings nor failures */
	STRICT,
}

// TODO(jp): Put this directly into pluginArchive creation
val DefaultVerifyIntellijPlugin: Value<Boolean> = {
	val pluginDirectory = IntelliJ.preparedIntellijIdeSandbox.get().plugins / IntelliJ.intellijPluginName.get() // TODO(jp): This is wrong, pluginName must be wrapped in toSafeFileName, but a complete refactor is in order
	val strictness = verifyPluginStrictness.get()
	val ignoreFailures = strictness <= Strictness.LENIENT
	val ignoreWarnings = strictness <= Strictness.ALLOW_WARNINGS

	val creationResult = IdePluginManager.createManager().createPlugin(pluginDirectory)
	if (creationResult is PluginCreationSuccess) {
		for (it in creationResult.warnings) {
			LOG.warn("{}", it.message)
		}
	} else if (creationResult is PluginCreationFail) {
		for (it in creationResult.errorsAndWarnings) {
			if (it.level == PluginProblem.Level.ERROR) {
				LOG.error("{}", it.message)
			} else {
				LOG.warn("{}", it.message)
			}
		}
	} else {
		LOG.error("{}", creationResult)
	}
	val failBuild = creationResult !is PluginCreationSuccess || !ignoreWarnings && creationResult.warnings.isNotEmpty()
	!(failBuild && !ignoreFailures)
}
