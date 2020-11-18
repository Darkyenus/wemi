package wemiplugin.intellij

import Files
import Keys
import org.slf4j.LoggerFactory
import wemi.ActivityListener
import wemi.Value
import wemi.boot.WemiSystemCacheFolder
import wemi.util.JavaHome
import wemi.util.SystemInfo
import wemi.util.Version
import wemi.util.deleteRecursively
import wemi.util.div
import wemi.util.exists
import wemi.util.httpGetFile
import wemi.util.isDirectory
import wemi.util.jdkToolsJar
import wemi.util.name
import wemi.util.toSafeFileName
import wemiplugin.intellij.utils.Utils
import wemiplugin.intellij.utils.unTar
import java.net.URL
import java.nio.file.Path

private val LOG = LoggerFactory.getLogger("ResolveJbr")

/**
 * JBR - JetBrains Runtime is a custom Java distribution by JetBrains to use with their IDEs.
 * [More info here.](https://confluence.jetbrains.com/display/JBR/JetBrains+Runtime)
 */
class Jbr(val version:String, val javaHome:Path, val javaExecutable: Path) {
	fun toJavaHome():JavaHome {
		val bin = javaExecutable.parent
		val home = if (SystemInfo.IS_MAC_OS) bin.parent.parent else bin.parent
		return JavaHome(javaHome, javaExecutable, jdkToolsJar(home))
	}
}

/** JBR name and where to find it */
private class JbrArtifact(val name:String, val defaultRepoUrl:URL)

val DefaultJbrJavaHome: Value<JavaHome> = v@{
	val jbrRepo = IntelliJ.intellijJbrRepository.get()
	val jbrVersion = IntelliJ.intellijJbrVersion.get()
	if (jbrVersion != null) {
		val jbr = resolveJbr(jbrVersion, jbrRepo, progressListener)
		if (jbr != null) {
			return@v jbr.toJavaHome()
		}
		LOG.warn("Cannot resolve JBR {}. Falling back to builtin JBR.", jbrVersion)
	}
	val builtinJbrVersion = Utils.getBuiltinJbrVersion(IntelliJ.intellijResolvedIdeDependency.get().homeDir)
	if (builtinJbrVersion != null) {
		val builtinJbr = resolveJbr(builtinJbrVersion, jbrRepo, progressListener)
		if (builtinJbr != null) {
			return@v builtinJbr.toJavaHome()
		}
		LOG.warn("Cannot resolve builtin JBR {}. Falling back to local Java.", builtinJbrVersion)
	}

	Keys.javaHome.get()
}

private val JbrCacheFolder = WemiSystemCacheFolder / "intellij-jbr-cache"

fun resolveJbr(version:String, overrideRepoUrl:URL?, progressListener: ActivityListener?):Jbr? {
	val jbrArtifact = jbrArtifactFrom(if (version.startsWith('u')) "8$version" else version) ?: return null
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
		val url = (overrideRepoUrl ?: jbrArtifact.defaultRepoUrl) / archiveName
		if (httpGetFile(url, javaArchive, progressListener)) {
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
		if (SystemInfo.IS_MAC_OS) {
			jbr / "Contents/Home"
		} else {
			jbr
		}
	} else {
		if (SystemInfo.IS_MAC_OS) {
			javaHome / "jdk/Contents/Home"
		} else {
			javaHome
		}
	}

	val jre = root / "jre"
	val base = if (jre.exists()) jre else root
	val java = base / (if (SystemInfo.IS_WINDOWS) "bin/java.exe" else "bin/java")
	return if (java.exists()) {
		java.toAbsolutePath()
	} else null
}

private val DEFAULT_JBR_REPO = URL("https://cache-redirector.jetbrains.com/jetbrains.bintray.com/intellij-jbr/")
private val DEFAULT_OLD_JBR_REPO = URL("https://cache-redirector.jetbrains.com/jetbrains.bintray.com/intellij-jdk/")

private fun jbrArtifactFrom(version:String):JbrArtifact? {
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

	val os = when {
		SystemInfo.IS_WINDOWS -> "windows"
		SystemInfo.IS_MAC_OS -> "osx"
		SystemInfo.IS_LINUX -> "linux"
		else -> {
			LOG.warn("JBR is not available for current operating system")
			return null
		}
	}

	val oldFormat = prefix == "jbrex" || isJava8 && buildNumber < Version("1483.24")
	val arch = when {
		SystemInfo.IS_X86 && SystemInfo.IS_64_BIT -> "x64"
		SystemInfo.IS_X86 && SystemInfo.IS_32_BIT -> if (oldFormat) "x86" else "i586"
		else -> {
			LOG.warn("JBR is not available for current processor architecture")
			return null
		}
	}

	if (oldFormat) {
		return JbrArtifact("jbrex${majorVersion}b${buildNumberString}_${os}_${arch}", repoUrl)
	}

	if (prefix.isEmpty()) {
		prefix = if (isJava8) "jbrx-" else "jbr-"
	}
	return JbrArtifact("$prefix$majorVersion-$os-$arch-b$buildNumberString", repoUrl)
}
