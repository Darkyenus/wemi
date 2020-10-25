package wemiplugin.intellij

import org.slf4j.LoggerFactory
import wemi.boot.WemiCacheFolder
import wemi.dependency.internal.OS_ARCH
import wemi.dependency.internal.OS_FAMILY
import wemi.dependency.internal.OS_FAMILY_MAC
import wemi.dependency.internal.OS_FAMILY_WINDOWS
import wemi.util.Version
import wemi.util.deleteRecursively
import wemi.util.div
import wemi.util.exists
import wemi.util.httpGetFile
import wemi.util.isDirectory
import wemi.util.name
import wemi.util.toSafeFileName
import wemiplugin.intellij.utils.unTar
import java.net.URL
import java.nio.file.Path

/**
 *
 */
class Jbr(val version:String, val javaHome:Path, val javaExecutable: Path)

private val LOG = LoggerFactory.getLogger("Jbr")

private val JbrCacheFolder = WemiCacheFolder / "-intellij-jbr-cache"

fun resolveJbr(version:String, jreRepoUrl:String?):Jbr? {
	val jbrArtifact = JbrArtifact.from(if (version.startsWith('u')) "8$version" else version)
	val javaDir = JbrCacheFolder / jbrArtifact.name.toSafeFileName('_')
	if (javaDir.exists()) {
		if (javaDir.isDirectory()) {
			return fromDir(javaDir, version)
		}
		javaDir.deleteRecursively()
	}

	val javaArchive = getJavaArchive(jbrArtifact, jreRepoUrl)
	if (javaArchive != null) {
		unTar(javaArchive, javaDir)
		Files.deleteIfExists(javaArchive)
		return fromDir(javaDir, version)
	}
	return null
}

private fun fromDir(javaDir:Path, version:String):Jbr? {
	val javaExecutable = findJavaExecutable(javaDir)
	if (javaExecutable == null) {
		LOG.warn("Cannot find java executable in {}", javaDir)
		return null
	}
	return Jbr(version, javaDir, javaExecutable)
}

private fun getJavaArchive(jbrArtifact:JbrArtifact, jreRepoUrl:String?):Path? {
	val artifactName = jbrArtifact.name
	val archiveName = "${artifactName}.tar.gz"
	val javaArchive = JbrCacheFolder / archiveName.toSafeFileName('_')
	if (javaArchive.exists()) {
		return javaArchive
	}
	val url = "${jreRepoUrl ?: jbrArtifact.repoUrl}/$archiveName" // TODO(jp): Wtf is going on here with repo url
	if (httpGetFile(URL(url), javaArchive)) {
		return javaArchive
	} else {
		LOG.warn("Could not download JetBrains Java Runtime {} from {}", artifactName, url)
		return null
	}
}

private fun findJavaExecutable(javaHome:Path):Path? {
	val root = getJbrRoot(javaHome)
	val jre = root / "jre"
	val base = if (jre.exists()) jre else root
	val java = base / (if (OS_FAMILY == OS_FAMILY_WINDOWS) "bin/java.exe" else "bin/java")
	return if (java.exists()) {
		java.toAbsolutePath()
	} else null
}

private fun getJbrRoot(javaHome:Path):Path {
	val jbr = Files.list(javaHome).filter {
		val name = it.name
		(name.equals("jbr", true) || name.equals("jbrsdk", true)) && Files.isDirectory(it)
	}.findFirst().orElse(null)

	if (jbr != null) {
		return if (OS_FAMILY == OS_FAMILY_MAC) {
			jbr / "Contents/Home"
		} else {
			jbr
		}
	}

	return if (OS_FAMILY == OS_FAMILY_MAC) {
		javaHome / "jdk/Contents/Home"
	} else {
		javaHome
	}
}

private class JbrArtifact(val name:String, val repoUrl:String) {
	companion object {
		fun from(version:String):JbrArtifact {
			var prefix = getPrefix(version)
			val lastIndexOfB = version.lastIndexOf('b')
			val majorVersion = if (lastIndexOfB > -1) version.substring(prefix.length, lastIndexOfB) else version.substring(prefix.length)
			val buildNumberString = if (lastIndexOfB > -1) version.substring(lastIndexOfB + 1) else ""
			val buildNumber = Version(buildNumberString)
			val isJava8 = majorVersion.startsWith('8')

			val repoUrl = if (!isJava8 || buildNumber >= Version("1483.31")) IntelliJ.DEFAULT_NEW_JBR_REPO else IntelliJ.DEFAULT_JBR_REPO

			val oldFormat = prefix == "jbrex" || isJava8 && buildNumber < Version("1483.24")
			if (oldFormat) {
				return JbrArtifact("jbrex${majorVersion}b${buildNumberString}_${platform()}_${arch(false)}", repoUrl)
			}

			if (prefix.isEmpty()) {
				prefix = if (isJava8) "jbrx-" else "jbr-"
			}
			return JbrArtifact("$prefix${majorVersion}-${platform()}-${arch(true)}-b${buildNumberString}", repoUrl)
		}

		private fun getPrefix(version:String):String {
			if (version.startsWith("jbrsdk-")) {
				return "jbrsdk-"
			}
			if (version.startsWith("jbr_jcef-")) {
				return "jbr_jcef-"
			}
			if (version.startsWith("jbr-")) {
				return "jbr-"
			}
			if (version.startsWith("jbrx-")) {
				return "jbrx-"
			}
			if (version.startsWith("jbrex8")) {
				return "jbrex"
			}
			return ""
		}

		private fun platform(): String {
			if (OS_FAMILY == OS_FAMILY_WINDOWS) return "windows"
			if (OS_FAMILY == OS_FAMILY_MAC) return "osx"
			return "linux"
		}

		private fun arch(newFormat:Boolean):String {
			return if (OS_ARCH == "x86") {
				if (newFormat) "i586" else "x86"
			} else "x64"
		}
	}
}