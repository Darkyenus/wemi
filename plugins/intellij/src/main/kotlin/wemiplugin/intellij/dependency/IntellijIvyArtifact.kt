package wemiplugin.intellij.dependency

import java.nio.file.Path

/**
 *
 */
class IntellijIvyArtifact(
		val file: Path,
		var name:String,
		var extension:String,
		var type:String,
		var classifier:String?
) {

	val buildDependencies = DefaultTaskDependency()
	var conf:String? = null


	fun builtBy(vararg tasks:Any) {
		buildDependencies.add(tasks)
	}

	override fun toString(): String {
		return String.format("%s %s:%s:%s:%s", this.javaClass.simpleName, name, type, extension, classifier)
	}
}