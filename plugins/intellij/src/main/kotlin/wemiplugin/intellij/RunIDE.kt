package wemiplugin.intellij

import Keys
import org.slf4j.LoggerFactory
import wemi.EvalScope
import wemi.ValueModifier
import wemi.collections.toMutable
import wemi.dependency.internal.OS_FAMILY
import wemi.dependency.internal.OS_FAMILY_MAC
import wemi.dependency.internal.OS_FAMILY_UNIX
import wemi.run.runForegroundProcess
import wemi.util.Version
import wemi.util.absolutePath
import wemi.util.div
import wemi.util.exists
import wemi.util.jdkToolsJar
import wemiplugin.intellij.utils.Utils
import wemiplugin.intellij.utils.Utils.getPluginIds
import java.nio.file.Path

private val LOG = LoggerFactory.getLogger("RunIDE")

private val PREFIXES = mapOf(
		"IU" to null,
		"IC" to "Idea",
		"RM" to "Ruby",
		"PY" to "Python",
		"PC" to "PyCharmCore",
		"PE" to "PyCharmEdu",
		"PS" to "PhpStorm",
		"WS" to "WebStorm",
		"OC" to "AppCode",
		"CL" to "CLion",
		"DB" to "DataGrip",
		"AI" to "AndroidStudio",
		"GO" to "GoLand",
		"RD" to "Rider",
		"RS" to "Rider")

val DefaultModifySystemProperties : ValueModifier<Map<String, String>> = {
	val systemProperties = it.toMutableMap()
	val sandboxDir = IntelliJ.preparedIntellijIdeSandbox.get()
	val ideBuildNumber = IntelliJ.resolvedIntellijIdeDependency.get().buildNumber

	val configDirectory = sandboxDir.config
	val pluginsDirectory = sandboxDir.plugins
	val systemDirectory = sandboxDir.system
	val requiredPluginIds = getPluginIds()
	/*
	 * Enables auto-reload of dynamic plugins. Dynamic plugins will be reloaded automatically when their JARs are
	 * modified. This allows a much faster development cycle by avoiding a full restart of the development instance
	 * after code changes. Enabled by default in 2020.2 and higher.
	 */
	val autoReloadPlugins:Boolean = run {
		Version(ideBuildNumber.takeWhile { c -> c != '-' }) >= Version("202.0")
	}

	systemProperties.putAll(Utils.getIdeaSystemProperties(configDirectory, systemDirectory, pluginsDirectory, requiredPluginIds))
	if (OS_FAMILY == OS_FAMILY_MAC) {
		systemProperties.putIfAbsent("idea.smooth.progress", "false")
		systemProperties.putIfAbsent("apple.laf.useScreenMenuBar", "true")
		systemProperties.putIfAbsent("apple.awt.fileDialogForDirectories", "true")
	} else if (OS_FAMILY == OS_FAMILY_UNIX) {
		systemProperties.putIfAbsent("sun.awt.disablegrab", "true")
	}
	systemProperties.putIfAbsent("idea.classpath.index.enabled", "false")
	systemProperties.putIfAbsent("idea.is.internal", "true")
	systemProperties.putIfAbsent("idea.auto.reload.plugins", autoReloadPlugins.toString())

	if (!systemProperties.containsKey("idea.platform.prefix")) {
		val matcher = Utils.VERSION_PATTERN.matcher(ideBuildNumber)
		if (matcher.find()) {
			val abbreviation = matcher.group(1)
			val prefix = PREFIXES[abbreviation]
			if (prefix != null && prefix.isNotBlank()) {
				systemProperties["idea.platform.prefix"] = prefix

				if (abbreviation == "RD") {
					// Allow debugging Rider's out of process ReSharper host
					systemProperties.putIfAbsent("rider.debug.mono.debug", "true")
					systemProperties.putIfAbsent("rider.debug.mono.allowConnect", "true")
				}
			}
		}
	}

	systemProperties
}

val DefaultModifyRunOptions : ValueModifier<List<String>> = {
	val runOptions = it.toMutable()
	if (!runOptions.any { o -> o.startsWith("-Xmx") }) {
		runOptions.add("-Xmx512m")
	}
	if (!runOptions.any { o -> o.startsWith("-Xms") }) {
		runOptions.add("-Xms256m")
	}
	val bootJar = IntelliJ.resolvedIntellijIdeDependency.get().homeDir / "lib/boot.jar"
	if (bootJar.exists()) runOptions.add("-Xbootclasspath/a:${bootJar.absolutePath}")
	runOptions
}

fun EvalScope.runIde(extraArguments: List<String> = emptyList()): Int {
	val ideDirectory = IntelliJ.resolvedIntellijIdeDependency.get().homeDir
	val executable = Keys.javaExecutable.get()

	val classpath = ArrayList<Path>()
	// Apparently the IDE needs to have the tools.jar on classpath
	val toolsJar = run {
		val bin = executable.parent
		val home = if (OS_FAMILY == OS_FAMILY_MAC) bin.parent.parent else bin.parent
		jdkToolsJar(home)
	}
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