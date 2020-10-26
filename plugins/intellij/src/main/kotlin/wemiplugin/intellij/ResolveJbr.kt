package wemiplugin.intellij

import Files
import Keys
import org.slf4j.LoggerFactory
import wemi.Value
import wemi.boot.WemiSystemCacheFolder
import wemi.dependency.internal.OS_ARCH
import wemi.dependency.internal.OS_FAMILY
import wemi.dependency.internal.OS_FAMILY_MAC
import wemi.dependency.internal.OS_FAMILY_WINDOWS
import wemi.run.javaExecutable
import wemi.util.Version
import wemi.util.deleteRecursively
import wemi.util.div
import wemi.util.exists
import wemi.util.httpGetFile
import wemi.util.isDirectory
import wemi.util.name
import wemi.util.toSafeFileName
import wemiplugin.intellij.utils.Utils
import wemiplugin.intellij.utils.Utils.ideSdkDirectory
import wemiplugin.intellij.utils.unTar
import java.net.URL
import java.nio.file.Path

private val LOG = LoggerFactory.getLogger("ResolveJbr")

/**
 * JBR - JetBrains Runtime is a custom Java distribution by JetBrains to use with their IDEs.
 * [More info here.](https://confluence.jetbrains.com/display/JBR/JetBrains+Runtime)
 */
class Jbr(val version:String, val javaHome:Path, val javaExecutable: Path)

/** JBR name and where to find it */
private class JbrArtifact(val name:String, val defaultRepoUrl:URL)

val DefaultJavaExecutable: Value<Path> = v@{
	val jbrRepo = IntelliJ.intellijJbrRepository.get()
	val jbrVersion = IntelliJ.intellijJbrVersion.get()
	if (jbrVersion != null) {
		val jbr = resolveJbr(jbrVersion, jbrRepo)
		if (jbr != null) {
			return@v jbr.javaExecutable
		}
		LOG.warn("Cannot resolve JBR {}. Falling back to builtin JBR.", jbrVersion)
	}
	val builtinJbrVersion = Utils.getBuiltinJbrVersion(ideSdkDirectory())
	if (builtinJbrVersion != null) {
		val builtinJbr = resolveJbr(builtinJbrVersion, jbrRepo)
		if (builtinJbr != null) {
			return@v builtinJbr.javaExecutable
		}
		LOG.warn("Cannot resolve builtin JBR {}. Falling back to local Java.", builtinJbrVersion)
	}

	javaExecutable(Keys.javaHome.get())
}

private val JbrCacheFolder = WemiSystemCacheFolder / "intellij-jbr-cache"

fun resolveJbr(version:String, overrideRepoUrl:URL?):Jbr? {
	val jbrArtifact = jbrArtifactFrom(if (version.startsWith('u')) "8$version" else version)
	val javaDir = JbrCacheFolder / jbrArtifact.name.toSafeFileName('_')
	if (javaDir.exists()) {
		if (javaDir.isDirectory()) {
			return fromDir(javaDir, version)
		}
		javaDir.deleteRecursively()
	}

	val javaArchive = run {
		val artifactName = jbrArtifact.name
		val archiveName = "${artifactName}.tar.gz"
		val javaArchive = JbrCacheFolder / archiveName.toSafeFileName('_')
		if (javaArchive.exists()) {
			return@run javaArchive
		}
		val url = (overrideRepoUrl ?: jbrArtifact.defaultRepoUrl) / "archiveName"
		if (httpGetFile(url, javaArchive)) {
			return@run javaArchive
		} else {
			LOG.warn("Could not download JetBrains Java Runtime {} from {}", artifactName, url)
			return@run null
		}
	}

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

private fun findJavaExecutable(javaHome:Path):Path? {
	val jbr = Files.list(javaHome).filter {
		val name = it.name
		(name.equals("jbr", true) || name.equals("jbrsdk", true)) && Files.isDirectory(it)
	}.findFirst().orElse(null)

	val root = if (jbr != null) {
		if (OS_FAMILY == OS_FAMILY_MAC) {
			jbr / "Contents/Home"
		} else {
			jbr
		}
	} else {
		if (OS_FAMILY == OS_FAMILY_MAC) {
			javaHome / "jdk/Contents/Home"
		} else {
			javaHome
		}
	}

	val jre = root / "jre"
	val base = if (jre.exists()) jre else root
	val java = base / (if (OS_FAMILY == OS_FAMILY_WINDOWS) "bin/java.exe" else "bin/java")
	return if (java.exists()) {
		java.toAbsolutePath()
	} else null
}

private val DEFAULT_JBR_REPO = URL("https://cache-redirector.jetbrains.com/jetbrains.bintray.com/intellij-jbr/")
private val DEFAULT_OLD_JBR_REPO = URL("https://cache-redirector.jetbrains.com/jetbrains.bintray.com/intellij-jdk/")

private fun jbrArtifactFrom(version:String):JbrArtifact {
	var prefix = when {
		version.startsWith("jbrsdk-") -> "jbrsdk-"
		version.startsWith("jbr_jcef-") -> "jbr_jcef-"
		version.startsWith("jbr-") -> "jbr-"
		version.startsWith("jbrx-") -> "jbrx-"
		version.startsWith("jbrex8") -> "jbrex"
		else -> ""
	}
	val lastIndexOfB = version.lastIndexOf('b')
	val majorVersion = if (lastIndexOfB > -1) version.substring(prefix.length, lastIndexOfB) else version.substring(prefix.length)
	val buildNumberString = if (lastIndexOfB > -1) version.substring(lastIndexOfB + 1) else ""
	val buildNumber = Version(buildNumberString)
	val isJava8 = majorVersion.startsWith('8')

	val repoUrl = if (!isJava8 || buildNumber >= Version("1483.31")) {
		DEFAULT_JBR_REPO
	} else {
		DEFAULT_OLD_JBR_REPO
	}

	val os = when (OS_FAMILY) {
		OS_FAMILY_WINDOWS -> "windows"
		OS_FAMILY_MAC -> "osx"
		else -> "linux"
	}

	val oldFormat = prefix == "jbrex" || isJava8 && buildNumber < Version("1483.24")
	if (oldFormat) {
		val arch = when (OS_ARCH) {
			"x86" -> "x86"
			else -> "x64"
		}
		return JbrArtifact("jbrex${majorVersion}b${buildNumberString}_${os}_${arch}", repoUrl)
	}

	if (prefix.isEmpty()) {
		prefix = if (isJava8) "jbrx-" else "jbr-"
	}
	val arch = when (OS_ARCH) {
		"x86" -> "i586"
		else -> "x64"
	}
	return JbrArtifact("$prefix$majorVersion-$os-$arch-b$buildNumberString", repoUrl)
}
