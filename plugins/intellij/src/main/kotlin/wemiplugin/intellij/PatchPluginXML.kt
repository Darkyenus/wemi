package wemiplugin.intellij

import org.slf4j.LoggerFactory
import wemi.KeyDefaults
import wemi.Value
import wemi.util.LocatedPath
import wemi.util.div
import wemiplugin.intellij.utils.Patch
import wemiplugin.intellij.utils.parseXml
import wemiplugin.intellij.utils.patchInPlace
import wemiplugin.intellij.utils.saveXml

private val LOG = LoggerFactory.getLogger("PatchPluginXML")

// See https://jetbrains.org/intellij/sdk/docs/basics/plugin_structure/plugin_configuration_file.html

val PatchedPluginXmlFiles : Value<List<LocatedPath>> = v@{
	val filesToPatch = IntelliJ.intelliJPluginXmlFiles.get()
	if (filesToPatch.isEmpty()) {
		return@v emptyList()
	}
	val root = KeyDefaults.outputClassesDirectory("patched-plugin-xml")()
	val patchedPaths = ArrayList<LocatedPath>()
	for (unpatched in filesToPatch) {
		val pluginXml = parseXml(unpatched.file) ?: continue

		pluginXml.patchInPlace("idea-plugin", IntelliJ.intelliJPluginXmlPatches.get())

		val patchedPath = root / "META-INF" / unpatched.path
		if (saveXml(patchedPath, pluginXml)) {
			patchedPaths.add(LocatedPath(root, patchedPath))
		}
	}
	patchedPaths
}

val DefaultIntelliJPluginXmlPatches : Value<Iterable<Patch>> = {
	val namePatch = Keys.projectName.getOrElse(null)?.let { Patch("name", content = it) }
	val idPatch = Keys.projectGroup.getOrElse(null)?.let { Patch("id", content = it) }
	val versionPatch = Keys.projectVersion.getOrElse(null)?.let { Patch("version", content = it) }
	val ideVersion = IntelliJ.resolvedIntellijIdeDependency.get().version

	val sinceBuildPatch = Patch("idea-version", attribute = "since-build", content = "${ideVersion.baselineVersion}.${ideVersion.build}")
	val untilBuildPatch = Patch("idea-version", attribute = "since-build", content = "${ideVersion.baselineVersion}.*")

	// TODO(jp): Also handle module dependencies

	arrayOf(namePatch, idPatch, versionPatch, sinceBuildPatch, untilBuildPatch).filterNotNull()
}

