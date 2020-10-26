package wemiplugin.intellij.tasks

import Keys
import com.jetbrains.plugin.structure.intellij.version.IdeVersion
import org.slf4j.LoggerFactory
import wemi.BindingHolder
import wemi.KeyDefaults
import wemi.Value
import wemi.key
import wemi.util.FileSet
import wemi.util.LocatedPath
import wemi.util.div
import wemi.util.plus
import wemiplugin.intellij.IntelliJ
import wemiplugin.intellij.utils.Patch
import wemiplugin.intellij.utils.parseXml
import wemiplugin.intellij.utils.patchInPlace
import wemiplugin.intellij.utils.saveXml


/**
 * See https://jetbrains.org/intellij/sdk/docs/basics/plugin_structure/plugin_configuration_file.html
 */
object PatchPluginXmlTask {

	private val LOG = LoggerFactory.getLogger(PatchPluginXmlTask.javaClass)

	val intelliJPluginXmlFiles by key<List<LocatedPath>>("plugin.xml files that should be patched and added to classpath", emptyList())
	val intelliJPluginXmlPatches by key<List<Patch>>("Values to change in plugin.xml. Later added values override previous patches, unless using the ADD mode.", emptyList())
	val intelliJPatchedPluginXmlFiles by key<List<LocatedPath>>("Patched variants of intelliJPluginXmlFiles")

	val PatchedPluginXmlFiles : Value<List<LocatedPath>> = v@{
		val filesToPatch = intelliJPluginXmlFiles.get()
		if (filesToPatch.isEmpty()) {
			return@v emptyList()
		}
		val root = KeyDefaults.outputClassesDirectory("patched-plugin-xml")()
		val patchedPaths = ArrayList<LocatedPath>()
		for (unpatched in filesToPatch) {
			val pluginXml = parseXml(unpatched.file) ?: continue

			pluginXml.patchInPlace("idea-plugin", intelliJPluginXmlPatches.get())

			val patchedPath = root / "META-INF" / unpatched.path
			if (saveXml(patchedPath, pluginXml)) {
				patchedPaths.add(LocatedPath(root, patchedPath))
			}
		}
		patchedPaths
	}

	internal fun BindingHolder.setupPatchPluginXmlTask() {
		intelliJPluginXmlPatches addAll  {
			val namePatch = Keys.projectName.getOrElse(null)?.let { Patch("name", content = it) }
			val idPatch = Keys.projectGroup.getOrElse(null)?.let { Patch("id", content = it) }
			val versionPatch = Keys.projectVersion.getOrElse(null)?.let { Patch("version", content = it) }
			val ideVersion = IdeVersion.createIdeVersion(IntelliJ.resolvedIntellijIdeDependency.get().buildNumber)

			val sinceBuildPatch = Patch("idea-version", attribute = "since-build", content = "${ideVersion.baselineVersion}.${ideVersion.build}")
			val untilBuildPatch = Patch("idea-version", attribute = "since-build", content = "${ideVersion.baselineVersion}.*")

			// TODO(jp): Also handle module dependencies

			arrayOf(namePatch, idPatch, versionPatch, sinceBuildPatch, untilBuildPatch).filterNotNull()
		}
		intelliJPatchedPluginXmlFiles set PatchedPluginXmlFiles

		// Add the patched xml files into resources
		Keys.resources modify { it + intelliJPatchedPluginXmlFiles.get().fold(null as FileSet?) { left, next -> left + FileSet(next) } }
	}

}