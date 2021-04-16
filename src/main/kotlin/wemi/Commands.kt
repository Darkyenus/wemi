package wemi

import wemi.run.ExitCode
import wemi.test.TestReport

/** [Command]s that are included with Wemi. */
object Commands {

	val run: Command<ExitCode> by command("Proxy for the run key, with ability to specify main class", {
		val mainClass = read("main", "Main class to start", ClassNameValidator, ask = false)
		if (mainClass != null) {
			Keys.mainClass set { mainClass }
		}
	}) { Keys.run.get() }

	val test: Command<TestReport> by command("Proxy for test key with ability to specify class filter", {
		val classFilter = read("class", "Include classes, whose fully classified name match this regex", StringValidator, ask=false)
		if (classFilter != null) {
			Keys.testParameters modify {
				it.filterClassNamePatterns.included.add(classFilter)
				it
			}
		}
	}) { Keys.test.get() }
}