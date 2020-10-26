package wemiplugin.intellij

import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import wemi.EvalScope
import wemi.WemiException
import wemi.run.JavaHome
import wemi.util.absolutePath
import wemi.util.copyRecursively
import wemi.util.div
import wemi.util.jdkToolsJar
import wemi.util.toSafeFileName
import wemiplugin.intellij.utils.getFirstElement
import wemiplugin.intellij.utils.namedElements
import wemiplugin.intellij.utils.parseXml
import wemiplugin.intellij.utils.saveXml
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

private val LOG = LoggerFactory.getLogger("PrepareSandbox")

class IntelliJIDESandbox(val base:Path, val config:Path, val plugins:Path, val system:Path)

fun EvalScope.prepareIntelliJIDESandbox(sandboxDir: Path = Keys.cacheDirectory.get() / "idea-sandbox", testSuffix:String = "", vararg extraPluginDirectories: Path): IntelliJIDESandbox {
	val destinationDir = sandboxDir / "plugins$testSuffix"
	val librariesToIgnore = IntelliJ.resolvedIntellijIdeDependency.get().jarFiles.toMutableSet()// TODO(jp): There is probably a better way to do this, i.e. add these only on compiling:
	jdkToolsJar(JavaHome)?.let {  librariesToIgnore.add(it) }

	val pluginDependencies = IntelliJ.resolvedIntellijPluginDependencies.get()
	val pluginName = IntelliJ.intellijPluginName.get().toSafeFileName()
	val pluginJar = Keys.archive.get() ?: throw WemiException("Plugin must generate a jar on archive", false)

	val pluginDir = destinationDir / pluginName / "lib"
	val externalClasspath = using(Configurations.running) {
		Keys.externalClasspath.get().map { it.classpathEntry } - librariesToIgnore
	}.filterNot { external ->
		pluginDependencies.any { external.startsWith(it.artifact.absolutePath) }
	}

	// TODO(jp): Instead of copying, just soft-link them!
	pluginJar.copyRecursively(pluginDir)
	for (path in externalClasspath) {
		path.copyRecursively(pluginDir)
	}
	for (dependency in IntelliJ.resolvedIntellijPluginDependencies.get()) {
		if (dependency.isBuiltin) {
			continue
		}
		dependency.artifact.copyRecursively(pluginDir)
	}
	for (path in extraPluginDirectories) {
		path.copyRecursively(pluginDir)
	}

	// Disable IDE update
	val configDir = sandboxDir / "config$testSuffix"
	try {
		disableIdeUpdate(configDir)
	} catch (e:Exception) {
		LOG.warn("Failed to disallow IDE update check", e)
	}

	return IntelliJIDESandbox(sandboxDir, configDir, destinationDir, sandboxDir / "system$testSuffix")
}

private fun disableIdeUpdate(configDir: Path) {
	val optionsDir = configDir / "options"
	try {
		Files.createDirectories(optionsDir)
	} catch (e:Exception) {
		LOG.warn("Failed to disable update checking in host IDE", e)
		return
	}

	val updatesConfig = optionsDir / "updates.xml"
	val updatesXml = parseXml(updatesConfig) ?: DocumentBuilderFactory.newInstance().run {
		isNamespaceAware = false
		isValidating = false
		isXIncludeAware = false
		val document: Document = newDocumentBuilder().newDocument()
		document.appendChild(document.createElement("application"))
		document
	}

	val application = updatesXml.documentElement.getFirstElement("application")!!
	val updatesConfigurable = application.namedElements("component")
			.find { it.getAttribute("name") == "UpdatesConfigurable" }
			?: run {
				val uc: Element = updatesXml.createElement("component")
				uc.setAttribute("name", "UpdatesConfigurable")
				application.appendChild(uc)
				uc
			}
	val checkNeeded = updatesConfigurable.namedElements("option")
			.find { it.getAttribute("name") == "CHECK_NEEDED" }
			?: run {
				val cn: Element = updatesXml.createElement("option")
				cn.setAttribute("name", "CHECK_NEEDED")
				application.appendChild(cn)
				cn
			}

	checkNeeded.setAttribute("value", "false")

	saveXml(updatesConfig, updatesXml)
}