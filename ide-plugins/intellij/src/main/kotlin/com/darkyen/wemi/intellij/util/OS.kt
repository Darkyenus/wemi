package com.darkyen.wemi.intellij.util

import com.darkyen.wemi.intellij.settings.WemiProjectService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.StreamUtil
import com.intellij.util.EnvironmentUtil
import com.sun.jna.Memory
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

private val LOG = Logger.getInstance("Wemi.OS")

/**
 * Find the absolute name of the program that the shell will execute under given name.
 * Similar to `which`, `where`, `command -v` and other commands.
 */
fun shellCommandExecutable(command:String): Path? {
	try {
		val process = if (SystemInfo.isWindows) {
			ProcessBuilder("C:\\Windows\\System32\\where.exe", command)
		} else {
			ProcessBuilder("command", "-v", command)
		}.run {
			environment().putAll(EnvironmentUtil.getEnvironmentMap())
			redirectError(ProcessBuilder.Redirect.PIPE)
			redirectOutput(ProcessBuilder.Redirect.PIPE)
			redirectInput(ProcessBuilder.Redirect.PIPE)
			start()
		}

		StreamUtil.closeStream(process.outputStream)
		val result = process.collectOutputLineAndKill(10, TimeUnit.SECONDS, true)
		if (result == null || result.isBlank()) {
			return null
		}
		val path = Paths.get(result).toAbsolutePath().normalize()
		if (!Files.exists(path)) {
			LOG.warn("shellCommandExecutable($command): Returned path does not exist ($path)")
			return null
		}

		return path
	} catch (e:Exception) {
		LOG.warn("shellCommandExecutable($command): Failed to determine process location", e)
		return null
	}
}

/** Try to find a POSIX compatible shell executable.
 * Defined only on MS Windows. */
fun findWindowsShellExe(): String? {
	if (!SystemInfo.isWindows) {
		return ""
	}

	return Windows.doFindShellExe()?.let { exe ->
		val normalized = exe.toAbsolutePath().normalize().toString()
		LOG.info("Found shell executable: $normalized")
		normalized
	}
}

fun Project?.getWindowsShellExe(): String? {
	if (!SystemInfo.isWindows) {
		return ""
	}

	return this?.getService(WemiProjectService::class.java)?.options?.getWindowsShellExecutable(this)
			?: findWindowsShellExe()
}

private object Windows {

	fun doFindShellExe(): Path? {
		// First try if it is in the PATH
		shellCommandExecutable("sh")
				?: shellCommandExecutable("bash")?.let { return it }

		// Maybe we can find where git is, in which case we might be able to infer where sh is as well.
		// This is common on Git for windows installations.
		shellCommandExecutable("git")?.let { git ->
			val gitDirectory = git.parent!!
			gitDirectory.resolveExisting("sh.exe")
					?: gitDirectory.resolveExisting("bash.exe")
					?: gitDirectory.resolveExisting("../bin/sh.exe")
					?: gitDirectory.resolveExisting("../bin/bash.exe")
		}?.let { return it }

		// Now try to find the application assigned to open `.sh` files.
		executableAssociatedTo("sh")
				?: executableAssociatedTo("bash")?.let { exe ->
			val name = exe.name.pathWithoutExtension()
			if ("sh".equals(name, ignoreCase = true) || "bash".equals(name, ignoreCase = true)) {
				return exe
			}
		}

		// and now we fail
		return null
	}

	private interface Shlwapi : com.sun.jna.platform.win32.Shlwapi {

		// ASSOCF: https://docs.microsoft.com/en-us/windows/win32/shell/assocf_str
		// ASSOCSTR: https://docs.microsoft.com/en-us/windows/win32/api/shlwapi/ne-shlwapi-assocstr
		fun AssocQueryStringW(
				flags: Int /*ASSOCF*/,
				str: Int /*ASSOCSTR*/,
				pszAssoc: WString /*LPCWSTR*/,
				pszExtra: WString? /*LPCWSTR*/,
				pszOut: WTypes.LPWSTR /*LPWSTR*/,
				pcchOut: IntByReference /*DWORD*/
		): WinNT.HRESULT
	}

	private val ShlwapiInstance = com.sun.jna.Native.load("Shlwapi", Shlwapi::class.java, com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS)

	private fun executableAssociatedTo(extension: String): Path? {
		val extensionW = WString(".$extension")
		val outMemory = Memory(2048) // Max path length is 255, but lets allocate whatever, just in case
		val out = WTypes.LPWSTR(outMemory)
		val size = IntByReference()
		val result = ShlwapiInstance.AssocQueryStringW(0/*ASSOCF_NONE*/, 1 /*ASSOCSTR_EXECUTABLE*/, extensionW, null, out, size)
		if (COMUtils.FAILED(result)) {
			LOG.warn("AssocQueryStringW failed: ${result.toInt()}")
			return null
		}

		val executablePath = out.value ?: return null
		if (executablePath.isEmpty()) {
			LOG.warn("AssocQueryStringW failed - result is empty: ${result.toInt()}")
			return null
		}

		try {
			return Paths.get(executablePath)
		} catch (e: InvalidPathException) {
			LOG.warn("Got invalid path from AssocQueryStringW", e)
			return null
		}
	}
}