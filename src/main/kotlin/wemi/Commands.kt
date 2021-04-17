package wemi.commands

import wemi.Command
import wemi.*
import wemi.keys.mainClass
import wemi.keys.testParameters
import wemi.run.ExitCode
import wemi.test.TestReport
import wemi.test.prettyPrint

/* [Command]s that are included with Wemi. */

val run: Command<ExitCode> by command("Proxy for the run key, with ability to specify main class") {
	val mainClassName = read("main", "Main class to start", ClassNameValidator, ask = false)
	if (mainClassName != null) {
		mainClass put mainClassName
	}

	evaluate { wemi.keys.run.get() }
}

val test: Command<TestReport> by command("Proxy for test key with ability to specify class filter", prettyPrinter = { it, _ -> it.prettyPrint() }) {
	val classFilter = read("class", "Include classes, whose fully classified name match this regex", StringValidator, ask = false)
	if (classFilter != null) {
		testParameters modify {
			it.filterClassNamePatterns.included.add(classFilter)
			it
		}
	}

	evaluate { wemi.keys.test.get() }
}