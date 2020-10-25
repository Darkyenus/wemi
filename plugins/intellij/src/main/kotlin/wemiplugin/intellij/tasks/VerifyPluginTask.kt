package wemiplugin.intellij.tasks

import org.slf4j.LoggerFactory
import wemi.BindingHolder
import wemi.WemiException
import wemi.key
import wemi.util.div
import wemiplugin.intellij.IntelliJ

/**
 *
 */
object VerifyPluginTask {

	private val LOG = LoggerFactory.getLogger(VerifyPluginTask::class.java)

	enum class Strictness {
		/** Validation will always pass */
		LENIENT,
		/** Validation will pass as long as there are no failures */
		ALLOW_WARNINGS,
		/** Validation will pass only if there are no warnings nor failures */
		STRICT,
	}

	val verifyPlugin by key<Boolean>("Validates completeness and contents of plugin.xml descriptors as well as plugin’s archive structure.")
	val verifyPluginStrictness by key("How strict the plugin verification should be", Strictness.ALLOW_WARNINGS)

	internal fun BindingHolder.setupVerifyPluginTask() {
		verifyPlugin set {
			val pluginDirectory = IntelliJ.sandboxDirectory.get().plugins / IntelliJ.pluginName.get() // TODO(jp): This is wrong, pluginName must be wrapped in toSafeFileName, but a complete refactor is in order
			val strictness = verifyPluginStrictness.get()
			val ignoreFailures = strictness <= Strictness.LENIENT
			val ignoreWarnings = strictness <= Strictness.ALLOW_WARNINGS

			/* TODO Uncomment after dependencies are included or something
			val creationResult = IdePluginManager.createManager().createPlugin(pluginDirectory)
			if (creationResult instanceof PluginCreationSuccess) {
				creationResult.warnings.each {
					LOG.warn("{}", it.message)
				}
			} else if (creationResult instanceof PluginCreationFail) {
				creationResult.errorsAndWarnings.each {
					if (it.level == PluginProblem.Level.ERROR) {
						LOG.error("{}", it.message)
					} else {
						LOG.warn("{}", it.message)
					}
				}
			} else {
				LOG.error("{}", creationResult)
			}
			val failBuild = creationResult !is PluginCreationSuccess || !ignoreWarnings && !creationResult.warnings.empty
			if (failBuild && !ignoreFailures) {
				throw WemiException("Plugin verification failed.", false)
			}
			 */

			true
		}
	}
}