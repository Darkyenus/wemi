package wemiplugin.intellij.dependency

import wemiplugin.intellij.PluginDependencyNotation
import java.nio.file.Path

/**
 *
 */
class PluginDependencyImpl(
		override var id:String,
		override var version:String,
		override var artifact:Path,
		override var isBuiltin:Boolean = false,
		override var isMaven:Boolean = false
) : PluginDependency {

	override var channel:String? = null

	var sinceBuild:String? = null
	var untilBuild:String? = null

	override var classesDirectory: Path? = null
	override var metaInfDirectory: Path? = null
	override var sourcesDirectory: Path? = null

	override var jarFiles:Collection<Path> = emptySet()


	init {
		if (Utils.isJarFile(artifact)) {
			jarFiles = Collections.singletonList(artifact)
		}
		if (artifact.isDirectory()) {
			File lib = new File(artifact, "lib")
			if (lib.isDirectory()) {
				jarFiles = Utils.collectJars(lib, { file -> true })
			}
			File classes = new File(artifact, "classes")
			if (classes.isDirectory()) {
				classesDirectory = classes
			}
			File metaInf = new File(artifact, "META-INF")
			if (metaInf.isDirectory()) {
				metaInfDirectory = metaInf
			}
		}
	}

	override fun isCompatible(ideVersion: IdeVersion): Boolean {
		return sinceBuild == null ||
				IdeVersion.createIdeVersion(sinceBuild) <= ideVersion &&
				(untilBuild == null || ideVersion <= IdeVersion.createIdeVersion(untilBuild))
	}

	override val notation: PluginDependencyNotation
		get() = PluginDependencyNotation.External(id, version, channel)

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is PluginDependencyImpl) return false

		if (id != other.id) return false
		if (version != other.version) return false
		if (artifact != other.artifact) return false
		if (isBuiltin != other.isBuiltin) return false
		if (isMaven != other.isMaven) return false
		if (channel != other.channel) return false
		if (sinceBuild != other.sinceBuild) return false
		if (untilBuild != other.untilBuild) return false
		if (classesDirectory != other.classesDirectory) return false
		if (metaInfDirectory != other.metaInfDirectory) return false
		if (sourcesDirectory != other.sourcesDirectory) return false
		if (jarFiles != other.jarFiles) return false

		return true
	}

	override fun hashCode(): Int {
		var result = id.hashCode()
		result = 31 * result + version.hashCode()
		result = 31 * result + artifact.hashCode()
		result = 31 * result + isBuiltin.hashCode()
		result = 31 * result + isMaven.hashCode()
		result = 31 * result + (channel?.hashCode() ?: 0)
		result = 31 * result + (sinceBuild?.hashCode() ?: 0)
		result = 31 * result + (untilBuild?.hashCode() ?: 0)
		result = 31 * result + (classesDirectory?.hashCode() ?: 0)
		result = 31 * result + (metaInfDirectory?.hashCode() ?: 0)
		result = 31 * result + (sourcesDirectory?.hashCode() ?: 0)
		result = 31 * result + jarFiles.hashCode()
		return result
	}

	override fun toString(): String {
		return "PluginDependencyImpl(id='$id', version='$version', artifact=$artifact, isBuiltin=$isBuiltin, isMaven=$isMaven, channel=$channel, sinceBuild=$sinceBuild, untilBuild=$untilBuild, classesDirectory=$classesDirectory, metaInfDirectory=$metaInfDirectory, sourcesDirectory=$sourcesDirectory, jarFiles=$jarFiles)"
	}


}