package wemi.dependency.internal

import java.util.*

/*
 * Utilities for Maven's OS detection capabilities.
 * Based on https://github.com/codehaus-plexus/plexus-utils/blob/master/src/main/java/org/codehaus/plexus/util/Os.java
 */

const val OS_FAMILY_DOS = "dos"
const val OS_FAMILY_MAC = "mac"
const val OS_FAMILY_NETWARE = "netware"
const val OS_FAMILY_OS2 = "os/2"
const val OS_FAMILY_TANDEM = "tandem"
const val OS_FAMILY_UNIX = "unix"
const val OS_FAMILY_WINDOWS = "windows"
const val OS_FAMILY_WIN9X = "win9x"
const val OS_FAMILY_ZOS = "z/os"
const val OS_FAMILY_OS400 = "os/400"
const val OS_FAMILY_OPENVMS = "openvms"

val OS_NAME:String = System.getenv("WEMI_MAVEN_OS_NAME") ?: System.getProperty("os.name").toLowerCase(Locale.US)
val OS_ARCH:String = System.getenv("WEMI_MAVEN_OS_ARCH") ?: System.getProperty("os.arch").toLowerCase(Locale.US)
val OS_VERSION:String = System.getenv("WEMI_MAVEN_OS_VERSION") ?: System.getProperty("os.version").toLowerCase(Locale.US)

val OS_FAMILY:String = System.getenv("WEMI_MAVEN_OS_FAMILY") ?: run {
	val pathSeparator = System.getProperty("path.separator")
	val osName = OS_NAME

	if (osName.contains(OS_FAMILY_WINDOWS)) {
		if ((osName.contains("95") || osName.contains("98") || osName.contains("me") || osName.contains("ce"))) {
			OS_FAMILY_WIN9X
		} else {
			OS_FAMILY_WINDOWS
		}
	}

	else if (osName.contains(OS_FAMILY_OS2)) {
		OS_FAMILY_OS2
	} else if (osName.contains(OS_FAMILY_NETWARE)) {
		OS_FAMILY_NETWARE
	} else if (osName.contains(OS_FAMILY_OPENVMS)) {
		OS_FAMILY_OPENVMS
	} else if (osName.contains(OS_FAMILY_ZOS) || osName.contains("os/390")) {
		OS_FAMILY_ZOS
	} else if (osName.contains(OS_FAMILY_OS400)) {
		OS_FAMILY_OS400
	} else if (osName.contains("nonstop_kernel")) {
		OS_FAMILY_TANDEM
	}

	// Must be after OS_FAMILY_NETWARE, OS_FAMILY_WINDOWS and OS_FAMILY_WIN9X
	else if (pathSeparator == ";") {
		OS_FAMILY_DOS
	}

	// Must be after OS_FAMILY_OPENVMS
	else if (pathSeparator == ":" && (!osName.contains(OS_FAMILY_MAC) || osName.endsWith("x"))) {
		OS_FAMILY_UNIX
	}

	// Must be after OS_FAMILY_UNIX
	else if (osName.contains(OS_FAMILY_MAC)) {
		OS_FAMILY_MAC
	}

	osName
}