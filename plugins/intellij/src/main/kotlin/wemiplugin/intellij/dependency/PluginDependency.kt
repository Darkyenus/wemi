package wemiplugin.intellij.dependency

import wemiplugin.intellij.PluginDependencyNotation
import java.nio.file.Path

/**
 *
 */
// TODO(jp): extends Serializable
interface PluginDependency {

	val id:String

	val version:String

	val channel:String?

	val artifact: Path

	val jarFiles:Collection<Path>

	val classesDirectory:Path?

	val metaInfDirectory:Path?

	val sourcesDirectory:Path?

	val isBuiltin:Boolean

	val isMaven:Boolean

	fun isCompatible(ideVersion:IdeVersion):Boolean

	val notation: PluginDependencyNotation
}