@file:Suppress("MemberVisibilityCanBePrivate")

package wemi.util

import org.slf4j.LoggerFactory
import java.util.*

/** System and OS detection utilities. */
object SystemInfo {

	/** Is this any Microsoft Windows operating system? */
	val IS_WINDOWS:Boolean
	/** Is this any operating system with Linux kernel? */
	val IS_LINUX:Boolean
	/** Is this Mac OS X, macOS, or whatever else they decide to call it?
	 * (Does not apply to classic Macintosh, that is not supported, nor detected.) */
	val IS_MAC_OS:Boolean
	/** FreeBSD, OpenBSD, NetBSD or possibly others, but not Darwin (that falls under macOS). */
	val IS_BSD:Boolean
	/** Not to be confused with SunOS, which is BSD based. But I am not sure what it would be detected as.
	 * Most of these are untested anyway - how would I get access to this stuff? */
	val IS_SOLARIS:Boolean
	/** Another UNIX, this time by IBM. */
	val IS_AIX:Boolean

	/** Is this operating system POSIX compliant? (At least roughly and in terms of available command line utilities?) */
	val IS_POSIX:Boolean
	/** Is this operating system an UNIX descendant? (Including Linux.) */
	val IS_UNIX:Boolean

	/** Is the processor using the x86 architecture? (either 32 or 64 bit) */
	val IS_X86:Boolean
	/** Is the processor using the ARM architecture? */
	val IS_ARM:Boolean
	// Architecture museum
	/** 64-bit, bi-endianness RISC, designed by DEC, killed by Intel in 2001. */
	val IS_ALPHA:Boolean
	/** Another 32/64-bit, bi-endianness RISC, older than Alpha. */
	val IS_MIPS:Boolean
	/** Itanium IA-64, not to be confused with IA-32, which is actually x86.
	 * Intel's arguably failed attempt at 64-bit architecture, before surrendering to AMD's x86-64. */
	val IS_IA_64:Boolean
	/** 32/64-bit RISC, designed by Apple-IBM-Motorola alliance, notable for being used in Apple's computers
	 * from 1994 to 2006, in Xbox 360, GameCube, Wii and on the Curiosity Mars rover. */
	val IS_POWER_PC:Boolean
	/** A fairly new (2010) 32/64/128-bit RISC notable for obtaining
	 * The Linley Group's Analyst's Choice Award for Best Technology in 2017 */
	val IS_RISC_V:Boolean
	/** A proper business enterprise processor by IBM. Covers both s390 (32-bit) and s390x (64-bit). */
	val IS_S390:Boolean
	/** 32/64-bit RISC by Sun. */
	val IS_SPARC:Boolean

	/** Is the processor architecture 32 bit? */
	val IS_32_BIT:Boolean
	/** Is the processor architecture 64 bit? */
	val IS_64_BIT:Boolean

	init {
		val LOG = LoggerFactory.getLogger(SystemInfo.javaClass)

		var isWindows = false
		var isLinux = false
		var isMacOs = false
		var isBSD = false
		var isSolaris = false
		var isAIX = false

		var isPosix = false

		var isX86 = false
		var isARM = false
		var isAlpha = false
		var isMips = false
		var isIA64 = false
		var isPowerPC = false
		var isRiscV = false
		var isS390 = false
		var isSparc = false

		var is32bit = false
		var is64bit = false

		// Values based on what is reported by OpenJDK
		// Windows:
		//  https://github.com/openjdk/jdk/blob/master/src/java.base/windows/native/libjava/java_props_md.c

		// macOS:
		//  https://github.com/openjdk/jdk/blob/master/src/java.base/macosx/native/libjava/java_props_macosx.c
		// Other (Unix + Linux):
		//  https://github.com/openjdk/jdk/blob/master/src/java.base/unix/native/libjava/java_props_md.c
		//  os.name returns directly "uname().sysname"
		// os.arch is the value of ARCHPROPNAME, which is set through OPENJDK_TARGET_CPU_OSARCH which is in turn set in
		// https://github.com/openjdk/jdk/blob/master/make/autoconf/platform.m4 (search for _CPU_OSARCH)

		val osName = System.getProperty("os.name").toLowerCase(Locale.ROOT)
		if ("windows" in osName) {
			isWindows = true
		} else if ("mac os x" in osName || "darwin" in osName || "macosx" in osName || "osx" in osName) {
			isMacOs = true
		} else if ("linux" in osName) {
			isLinux = true
		} else if ("sunos" in osName || "solaris" in osName) {
			isSolaris = true
		} else if ("bsd" in osName) {
			isBSD = true
		} else if ("cygwin" in osName || "mingw" in osName || "msys" in osName) {
			// I don't even know if this branch gets executed there, but it would be cool if it did.
			// Get in touch if you find out that it works/does not work or if you know how to make it work.
			isWindows = true
			isPosix = true
		} else if ("aix" in osName) {
			isAIX = true
		} else {
			LOG.warn("Unrecognized operating system: {}", osName)
		}

		val osArch = System.getProperty("os.arch").toLowerCase(Locale.ROOT)
		if ("amd64" in osArch || "x64" in osArch || "x86_64" in osArch || "x86-64" in osArch) {
			isX86 = true
			is64bit = true
		} else if ("x86" in osArch || "x32" in osArch || "i386" in osArch || "i486" in osArch || "i586" in osArch || "i686" in osArch) {
			isX86 = true
			is32bit = true
		} else if ("aarch64" in osArch || "armv8" in osArch) {
			isARM = true
			is64bit = true
		} else if ("arm" in osArch) {
			isARM = true
			is32bit = true
		} else if ("alpha" in osArch) {
			isAlpha = true
			is64bit = true
		} else if ("ia64" in osArch) {
			isIA64 = true
			is64bit = true
		} else if ("mips64" in osArch) {
			isMips = true
			is64bit = true
		} else if ("mips" in osArch) {
			isMips = true
			is32bit = true
		} else if ("powerpc64" in osArch || "ppc64" in osArch) {
			isPowerPC = true
			is64bit = true
		} else if ("powerpc" in osArch || "ppc" in osArch) {
			isPowerPC = true
			is32bit = true
		} else if ("riscv64" in osArch) {
			isRiscV = true
			is64bit = true
		} else if ("riscv" in osArch) {
			isRiscV = true
			is32bit = true
		} else if ("s390x" in osArch) {
			isS390 = true
			is64bit = true
		} else if ("s390" in osArch) {
			isS390 = true
			is32bit = true
		} else if ("sparcv9" in osArch || "sparc64" in osArch) {
			isSparc = true
			is64bit = true
		} else if ("sparc" in osArch) {
			isSparc = true
			is32bit = true
		} else {
			LOG.warn("Unrecognized processor architecture: {}", osArch)
		}

		IS_WINDOWS = isWindows
		IS_LINUX = isLinux
		IS_MAC_OS = isMacOs
		IS_BSD = isBSD
		IS_SOLARIS = isSolaris
		IS_AIX = isAIX
		IS_UNIX = isLinux || isMacOs || isBSD || isSolaris || isAIX
		IS_POSIX = IS_UNIX || isPosix
		IS_X86 = isX86
		IS_ARM = isARM
		IS_ALPHA = isAlpha
		IS_MIPS = isMips
		IS_IA_64 = isIA64
		IS_POWER_PC = isPowerPC
		IS_RISC_V = isRiscV
		IS_S390 = isS390
		IS_SPARC = isSparc
		IS_32_BIT = is32bit
		IS_64_BIT = is64bit
	}

}