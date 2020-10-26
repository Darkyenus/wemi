package wemiplugin.intellij.dependency

import wemi.util.isDirectory
import wemiplugin.intellij.utils.Utils
import java.nio.file.Path
import java.util.stream.Collectors

/**
 *
 */
class IdeaExtraDependency(val name:String, val classes:Path) {

	val jarFiles:Collection<Path> = if (classes.isDirectory()) {
		Utils.collectJars(classes).collect(Collectors.toList())
	} else {
		setOf(classes)
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is IdeaExtraDependency) return false

		if (name != other.name) return false
		if (classes != other.classes) return false
		if (jarFiles != other.jarFiles) return false

		return true
	}

	override fun hashCode(): Int {
		var result = name.hashCode()
		result = 31 * result + classes.hashCode()
		result = 31 * result + jarFiles.hashCode()
		return result
	}

	override fun toString(): String {
		return "IdeaExtraDependency(name='$name', classes=$classes, jarFiles=$jarFiles)"
	}
}