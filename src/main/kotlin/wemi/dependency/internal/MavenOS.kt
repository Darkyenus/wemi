package wemi.dependency.internal

import java.util.*

/*
 * Utilities for Maven's OS detection capabilities.
 * Based on https://github.com/codehaus-plexus/plexus-utils/blob/master/src/main/java/org/codehaus/plexus/util/Os.java
 */

private const val FAMILY_DOS = "dos"
private const val FAMILY_MAC = "mac"
private const val FAMILY_NETWARE = "netware"
private const val FAMILY_OS2 = "os/2"
private const val FAMILY_TANDEM = "tandem"
private const val FAMILY_UNIX = "unix"
private const val FAMILY_WINDOWS = "windows"
private const val FAMILY_WIN9X = "win9x"
private const val FAMILY_ZOS = "z/os"
private const val FAMILY_OS400 = "os/400"
private const val FAMILY_OPENVMS = "openvms"

val OS_NAME:String = System.getProperty("os.name").toLowerCase(Locale.US)
val OS_ARCH:String = System.getProperty("os.arch").toLowerCase(Locale.US)
val OS_VERSION:String = System.getProperty("os.version").toLowerCase(Locale.US)

val OS_FAMILY:String = run {
	val pathSeparator = System.getProperty("path.separator")
	val osName = OS_NAME

	if (osName.contains(FAMILY_WINDOWS)) {
		if ((osName.contains("95") || osName.contains("98") || osName.contains("me") || osName.contains("ce"))) {
			FAMILY_WIN9X
		} else {
			FAMILY_WINDOWS
		}
	}

	else if (osName.contains(FAMILY_OS2)) {
		FAMILY_OS2
	} else if (osName.contains(FAMILY_NETWARE)) {
		FAMILY_NETWARE
	} else if (osName.contains(FAMILY_OPENVMS)) {
		FAMILY_OPENVMS
	} else if (osName.contains(FAMILY_ZOS) || osName.contains("os/390")) {
		FAMILY_ZOS
	} else if (osName.contains(FAMILY_OS400)) {
		FAMILY_OS400
	} else if (osName.contains("nonstop_kernel")) {
		FAMILY_TANDEM
	}

	// Must be after FAMILY_NETWARE, FAMILY_WINDOWS and FAMILY_WIN9X
	else if (pathSeparator == ";") {
		FAMILY_DOS
	}

	// Must be after FAMILY_OPENVMS
	else if (pathSeparator == ":" && (!osName.contains(FAMILY_MAC) || osName.endsWith("x"))) {
		FAMILY_UNIX
	}

	// Must be after FAMILY_UNIX
	else if (osName.contains(FAMILY_MAC)) {
		FAMILY_MAC
	}

	osName
}