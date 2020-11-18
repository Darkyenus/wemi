package wemiplugin.intellij

import Files
import Keys
import org.slf4j.LoggerFactory
import wemi.EvalScope
import wemi.Value
import wemi.util.LocatedPath
import wemi.util.div
import wemiplugin.intellij.utils.Patch
import wemiplugin.intellij.utils.parseXml
import wemiplugin.intellij.utils.patchInPlace
import wemiplugin.intellij.utils.saveXml
import java.nio.file.Path

private val LOG = LoggerFactory.getLogger("PatchPluginXML")

// See https://jetbrains.org/intellij/sdk/docs/basics/plugin_structure/plugin_configuration_file.html

fun EvalScope.generatePatchedPluginXmlFiles(root: Path) {
	val filesToPatch = IntelliJ.intellijPluginXmlFiles.get()
	if (filesToPatch.isEmpty()) {
		return
	}
	val patchedPaths = ArrayList<LocatedPath>()
	for (unpatched in filesToPatch) {
		val pluginXml = parseXml(unpatched.file) ?: continue

		pluginXml.patchInPlace("idea-plugin", IntelliJ.intellijPluginXmlPatches.get())

		val patchedPath = root / "META-INF" / unpatched.path
		if (saveXml(patchedPath, pluginXml)) {
			patchedPaths.add(LocatedPath(root, patchedPath))
		}
	}
}

val DefaultIntelliJPluginXmlPatches : Value<Iterable<Patch>> = {
	val namePatch = Keys.projectName.getOrElse(null)?.let { Patch("name", content = it) }
	val idPatch = Keys.projectGroup.getOrElse(null)?.let { Patch("id", content = it) }
	val versionPatch = Keys.projectVersion.getOrElse(null)?.let { Patch("version", content = it) }
	val ideVersion = IntelliJ.intellijResolvedIdeDependency.get().version

	val sinceBuildPatch = pluginXmlSinceBuildPatch("${ideVersion.baselineVersion}.${ideVersion.build}")
	// Not added automatically, because smaller/less maintained plugins are generally better without it
	//val untilBuildPatch = pluginXmlUntilBuildPatch("${ideVersion.baselineVersion}.*")

	// TODO(jp): Also handle module dependencies?

	arrayOf(namePatch, idPatch, versionPatch, sinceBuildPatch).filterNotNull()
}

fun pluginXmlSinceBuildPatch(sinceBuildVersion:String):Patch = Patch("idea-version", attribute = "since-build", content = sinceBuildVersion)
fun pluginXmlUntilBuildPatch(untilBuildVersion:String):Patch = Patch("idea-version", attribute = "until-build", content = untilBuildVersion)
fun pluginXmlDescriptionPatch(description:Path):Patch = Patch("description", content = String(Files.readAllBytes(description), Charsets.UTF_8))
fun pluginXmlChangeNotesPatch(changeNotes:Path):Patch = Patch("change-notes", content = String(Files.readAllBytes(changeNotes), Charsets.UTF_8))

