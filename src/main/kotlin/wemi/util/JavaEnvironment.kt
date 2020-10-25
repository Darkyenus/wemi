package wemi.util

import java.nio.file.Path
import java.util.*

/**
 * When java is run from "jre" part of the JDK install, "tools.jar" is not in the classpath by default.
 * This can locate the tools.jar in [wemi.Keys.javaHome] for explicit loading.
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