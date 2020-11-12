package wemi.util

import wemi.WemiException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*


/**
 * Represents a combination of Java home directory
 * and a path of corresponding java executable for the current platform.
 */
data class JavaHome(val home:Path, val javaExecutable: Path = javaExecutable(home), val toolsJar:Path? = jdkToolsJar(home))

/**
 * Java home, as set by the java.home property.
 */
val CurrentProcessJavaHome:JavaHome = JavaHome(Paths.get(
		System.getProperty("java.home", null)
				?: throw WemiException("java.home property is not set, can't find java executable")
).toAbsolutePath())

/**
 * Retrieve path to the java executable (starter of JVM), assuming that [javaHome] is the path to a valid JAVA_HOME.
 */
fun javaExecutable(javaHome: Path): Path {
	val windowsFile = (javaHome / "bin/java.exe").toAbsolutePath()
	val unixFile = (javaHome / "bin/java").toAbsolutePath()
	val winExists = Files.exists(windowsFile)
	val unixExists = Files.exists(unixFile)

	if (winExists && !unixExists) {
		return windowsFile
	} else if (!winExists && unixExists) {
		return unixFile
	} else if (!winExists && !unixExists) {
		if (SystemInfo.IS_WINDOWS) {
			throw WemiException("Java executable should be at $windowsFile, but it does not exist")
		} else {
			throw WemiException("Java executable should be at $unixFile, but it does not exist")
		}
	} else {
		return if (SystemInfo.IS_WINDOWS) {
			windowsFile
		} else {
			unixFile
		}
	}
}

/**
 * When java is run from "jre" part of the JDK install, "tools.jar" is not in the classpath by default.
 * This can locate the tools.jar in for standard Java homes for explicit loading.
 */
fun jdkToolsJar(javaHome: Path): Path? {
	// Gradle logic: https://github.com/gradle/gradle/blob/master/subprojects/base-services/src/main/java/org/gradle/internal/jvm/Jvm.java#L330
	return javaHome.resolve("lib/tools.jar").takeIf { it.exists() }
			?: (if (javaHome.name == "jre") javaHome.resolve("../lib/tools.jar").takeIf { it.exists() } else null)
			?: (if (javaHome.name.startsWith("jre")) {
				val matchingVersion = javaHome.parent.resolve("jdk${javaHome.name.removePrefix("jre")}/lib/tools.jar")
				if (matchingVersion.exists()) {
					matchingVersion
				} else {
					(Files.list(javaHome.parent)
							.filter { it.name.startsWith("jdk") }
							.map { it.resolve("lib/tools.jar") }
							.filter { it.exists() }
							.findAny() as Optional<Path?> /* because otherwise next line gives warnings */)
							.orElse(null)
				}
			} else null)
}

/** Return URL at which Java SE Javadoc is hosted for given [javaVersion]. */
fun javadocUrl(javaVersion:Int?):String {
	val version = javaVersion ?: 14 // = newest
	return when {
		// These versions don't have API uploaded, so fall back to 1.5 (first one that is online)
		version <= 5 -> "https://docs.oracle.com/javase/1.5.0/docs/api/"
		version in 6..10 -> "https://docs.oracle.com/javase/${version}/docs/api/"
		else -> "https://docs.oracle.com/en/java/javase/${version}/docs/api/"
	}
}