package wemiplugin.intellij.utils

import org.slf4j.LoggerFactory
import wemi.ActivityListener
import wemi.EvalScope
import wemi.dependency
import wemi.dependency.Repository
import wemi.dependency.TypeChooseByPackaging
import wemi.dependency.resolveDependencyArtifacts
import wemi.util.isDirectory
import wemi.util.name
import wemiplugin.intellij.DEFAULT_INTELLIJ_IDE_VERSION
import wemiplugin.intellij.IntelliJ
import wemiplugin.intellij.IntelliJIDE
import wemiplugin.intellij.getZipCacheDirectory
import wemiplugin.intellij.intellijIDERepository
import wemiplugin.intellij.unzipDependencyFile
import java.nio.file.Path
import java.util.stream.Collectors


private val LOG = LoggerFactory.getLogger("ExtraIntelliJDependency")

/**
 * A resolved dependency on something packaged like an IDE dependency, but not an IDE.
 * Honestly, I don't even know what this is.
 * If you need this, let me know so that I can test and document it properly.
 */
class ResolvedIntelliJExtraDependency(val name:String, val classes: Path) {

	val jarFiles:Collection<Path> = if (classes.isDirectory()) {
		Utils.collectJars(classes).collect(Collectors.toList())
	} else {
		setOf(classes)
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is ResolvedIntelliJExtraDependency) return false

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
		return "ResolvedIntelliJExtraDependency(name='$name', classes=$classes, jarFiles=$jarFiles)"
	}
}

fun EvalScope.resolveExtraIntelliJDependencies(extraDependencies: List<String>):List<ResolvedIntelliJExtraDependency> {
	val version = (IntelliJ.intellijIdeDependency.get() as? IntelliJIDE.External)?.version ?: DEFAULT_INTELLIJ_IDE_VERSION
	val repository = intellijIDERepository(IntelliJ.intellijIdeRepository.get(), version)

	val resolvedExtraDependencies = ArrayList<ResolvedIntelliJExtraDependency>()
	for (name in extraDependencies) {
		val dependencyFile = resolveExtraIntelliJDependency(version, name, repository, progressListener) ?: continue
		val extraDependency = ResolvedIntelliJExtraDependency(name, dependencyFile)
		LOG.debug("IDE extra dependency $name in $dependencyFile files: ${extraDependency.jarFiles}")
		resolvedExtraDependencies.add(extraDependency)
	}
	return resolvedExtraDependencies
}

fun resolveExtraIntelliJDependency(version: String, name: String, repository: Repository, progressListener: ActivityListener?) : Path? {
	val dependency = dependency("com.jetbrains.intellij.idea", name, version, type = TypeChooseByPackaging)
	val files = resolveDependencyArtifacts(listOf(dependency), listOf(repository), progressListener)
	if (files == null || files.isEmpty()) {
		LOG.warn("Cannot resolve IntelliJ extra dependency {}:{}", name, version)
		return null
	}

	if (files.size > 1) {
		LOG.warn("Resolving IntelliJ extra dependency {}:{} yielded more than one file - using only the first ({})", name, version, files)
	}

	val depFile = files.first()
	if (depFile.name.endsWith(".zip")) {
		val cacheDirectory = getZipCacheDirectory(depFile, "IC")
		LOG.debug("IDE extra dependency {}: {}", name, cacheDirectory)
		return unzipDependencyFile(cacheDirectory, depFile, "IC", version.endsWith("-SNAPSHOT"))
	} else {
		LOG.debug("IDE extra dependency {}: {}", name, depFile)
		return depFile
	}
}
