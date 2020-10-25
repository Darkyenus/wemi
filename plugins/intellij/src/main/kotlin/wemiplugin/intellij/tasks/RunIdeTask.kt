package wemiplugin.intellij.tasks

import Keys
import org.slf4j.LoggerFactory
import wemi.EvalScope
import wemi.key
import wemi.run.runForegroundProcess
import wemi.util.div
import wemiplugin.intellij.utils.Utils
import wemiplugin.intellij.utils.Utils.ideSdkDirectory
import java.nio.file.Path

/**
 *
 */

object RunIdeTask {

	private val LOG = LoggerFactory.getLogger(RunIdeTask.javaClass)

	fun EvalScope.runIde(runAlways:Boolean = true, extraArguments:List<String> = emptyList()): Int {
		val ideDirectory = ideSdkDirectory()
		val executable = Keys.javaExecutable.get()

		val classpath = ArrayList<Path>()
		val toolsJar = Utils.resolveToolsJar(executable)
		if (toolsJar != null) {
			classpath.add(toolsJar)
		}
		classpath.add(ideDirectory / "lib/idea_rt.jar")
		classpath.add(ideDirectory / "lib/idea.jar")
		classpath.add(ideDirectory / "lib/bootstrap.jar")
		classpath.add(ideDirectory / "lib/extensions.jar")
		classpath.add(ideDirectory / "lib/util.jar")
		classpath.add(ideDirectory / "lib/openapi.jar")
		classpath.add(ideDirectory / "lib/trove4j.jar")
		classpath.add(ideDirectory / "lib/jdom.jar")
		classpath.add(ideDirectory / "lib/log4j.jar")

		val processBuilder = wemi.run.prepareJavaProcess(
				executable, ideDirectory / "bin", classpath,
				"com.intellij.idea.Main", Keys.runOptions.get(), extraArguments)

		return runForegroundProcess(processBuilder)
	}

}




