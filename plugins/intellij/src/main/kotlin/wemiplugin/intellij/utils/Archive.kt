package wemiplugin.intellij.utils

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.z.ZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.slf4j.LoggerFactory
import wemi.util.div
import wemi.util.ensureEmptyDirectory
import wemi.util.isDirectory
import wemi.util.lastModifiedMillis
import wemi.util.name
import wemi.util.pathHasExtension
import java.io.BufferedInputStream
import java.io.IOException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.util.*
import java.util.zip.GZIPInputStream

private val LOG = LoggerFactory.getLogger("Archive")

/**
 * Un-tar a .tar or .tar.gz
 */
fun unTar(tarFile: Path, outputDirectory: Path, allowOutsideLinks:Boolean = false, allowHardLinks:Boolean = false) {
	val tempDir = outputDirectory.resolveSibling(outputDirectory.name+"-temp")
	LOG.debug("Unpacking {} to {}", tarFile, tempDir)
	tempDir.ensureEmptyDirectory()

	Files.newInputStream(tarFile).use { fileIn ->
		// Various compressions from: https://en.wikipedia.org/wiki/Tar_(computing)#Suffixes_for_compressed_files
		val baseIn = if (tarFile.name.pathHasExtension(listOf("gz", "tgz", "taz"))) {
			GZIPInputStream(fileIn, 2048)
		} else if (tarFile.name.pathHasExtension(listOf("bz2", "tb2", "tbz", "tbz2", "tz2"))) {
			BZip2CompressorInputStream(BufferedInputStream(fileIn, 2048))
		} else if (tarFile.name.pathHasExtension(listOf("Z", "tZ", "taZ"))) {
			ZCompressorInputStream(BufferedInputStream(fileIn, 2048))
		} else if (tarFile.name.pathHasExtension(listOf("lzma", "tlz"))) {
			LZMACompressorInputStream(BufferedInputStream(fileIn, 2048))
		} else if (tarFile.name.pathHasExtension(listOf("zst", "tzst"))) {
			ZstdCompressorInputStream(BufferedInputStream(fileIn, 2048))
		} else if (tarFile.name.pathHasExtension(listOf("xz"))) {
			XZCompressorInputStream(BufferedInputStream(fileIn, 2048))
		} else {
			BufferedInputStream(fileIn, 2048)
		}
		val tarIn = TarArchiveInputStream(baseIn, Charsets.UTF_8.name())

		while (true) {
			val entry = tarIn.nextTarEntry ?: break
			val entryPath = (tempDir / entry.name).normalize()
			if (!entry.isCheckSumOK) {
				LOG.debug("UnTar: {} of {} has a wrong checksum, ignoring and hoping for the best", entry.name, tarFile)
			}
			if (!entryPath.startsWith(tempDir)) {
				LOG.warn("UnTar: Skipping entry {} of {} - it goes outside of the result folder!", entry.name, tarFile)
				continue
			}
			if (entry.isFile) {
				LOG.debug("UnTar: {} of {} to {} (a normal file)", entry.name, tarFile, entryPath)
				Files.createDirectories(entryPath.parent)
				Files.newOutputStream(entryPath).use {
					tarIn.copyTo(it)
				}
				copyTarAttributes(entry, entryPath)
				continue
			}
			if (entry.isDirectory) {
				LOG.debug("UnTar: {} of {} to {} (a directory)", entry.name, tarFile, entryPath)
				Files.createDirectories(entryPath)
				copyTarAttributes(entry, entryPath)
				continue
			}
			if (entry.isSymbolicLink) {
				LOG.debug("UnTar: {} of {} to {} (a symbolic link)", entry.name, tarFile, entryPath)

				val linkPath = (tempDir / entry.linkName).normalize()
				if (linkPath.startsWith(tempDir) && !allowOutsideLinks) {
					LOG.warn("UnTar: Skipping entry {} of {} - symbolically linked file is outside of the result folder", entry.name, tarFile)
					continue
				}
				try {
					Files.createDirectories(entryPath.parent)
					Files.createSymbolicLink(entryPath, linkPath)
					copyTarAttributes(entry, entryPath)
				} catch (e:Exception) {
					LOG.warn("UnTar: Failure on symbolic link entry {} of {}", entry.name, tarFile, e)
				}
				continue
			}
			if (entry.isLink) {
				if (!allowHardLinks) {
					LOG.warn("UnTar: Skipping entry {} of {} - hard links are not allowed", entry.name, tarFile)
					continue
				}

				val linkPath = (tempDir / entry.linkName).normalize()
				if (linkPath.startsWith(tempDir) && !allowOutsideLinks) {
					LOG.warn("UnTar: Skipping entry {} of {} - hard linked file is outside of the result folder", entry.name, tarFile)
					continue
				}
				try {
					Files.createDirectories(entryPath.parent)
					Files.createLink(entryPath, linkPath)
					copyTarAttributes(entry, entryPath)
				} catch (e:Exception) {
					LOG.warn("UnTar: Failure on symbolic link entry {} of {}", entry.name, tarFile, e)
				}
				continue
			}

			LOG.warn("UnTar: Skipping {} of {} - unsupported entry type - {}", entry.name, tarFile, when {
				entry.isBlockDevice -> "block device"
				entry.isCharacterDevice -> "character device"
				entry.isFIFO -> "FIFO"
				else -> "unknown"
			})
		}
	}

	outputDirectory.ensureEmptyDirectory()
	Files.move(tempDir, outputDirectory, StandardCopyOption.REPLACE_EXISTING)
}

private fun copyTarAttributes(entry: TarArchiveEntry, file: Path) {
	val modTime = entry.modTime?.time ?: 0L
	if (modTime > 0L) {
		try {
			Files.setLastModifiedTime(file, FileTime.fromMillis(modTime))
		} catch (e: IOException) {
			LOG.debug("Failed to set mod time on {}", file)
		}
	}

	val mode = entry.mode
	if (mode != 0) {
		// While 0 is a valid mode, it is more probable that the mode was not saved properly
		try {
			val permissions = EnumSet.noneOf(PosixFilePermission::class.java)
			// Bits from https://www.gnu.org/software/tar/manual/html_node/Standard.html
			//#define TUREAD   00400          /* read by owner */
			//#define TUWRITE  00200          /* write by owner */
			//#define TUEXEC   00100          /* execute/search by owner */
			//#define TGREAD   00040          /* read by group */
			//#define TGWRITE  00020          /* write by group */
			//#define TGEXEC   00010          /* execute/search by group */
			//#define TOREAD   00004          /* read by other */
			//#define TOWRITE  00002          /* write by other */
			//#define TOEXEC   00001          /* execute/search by other */
			if (mode and 0x1 != 0) {
				permissions.add(PosixFilePermission.OTHERS_EXECUTE)
			}
			if (mode and 0x2 != 0) {
				permissions.add(PosixFilePermission.OTHERS_WRITE)
			}
			if (mode and 0x4 != 0) {
				permissions.add(PosixFilePermission.OTHERS_READ)
			}
			if (mode and 0x10 != 0) {
				permissions.add(PosixFilePermission.GROUP_EXECUTE)
			}
			if (mode and 0x20 != 0) {
				permissions.add(PosixFilePermission.GROUP_WRITE)
			}
			if (mode and 0x40 != 0) {
				permissions.add(PosixFilePermission.GROUP_READ)
			}
			if (mode and 0x100 != 0) {
				permissions.add(PosixFilePermission.OWNER_EXECUTE)
			}
			if (mode and 0x200 != 0) {
				permissions.add(PosixFilePermission.OWNER_WRITE)
			}
			if (mode and 0x400 != 0) {
				permissions.add(PosixFilePermission.OWNER_READ)
			}
			Files.setPosixFilePermissions(file, permissions)
		} catch (e: IOException) {
			LOG.debug("Failed to set permissions on {}", file)
		}
	}
	// Ignoring owner, group and other stuff, it is not important for our use-case
}

/**
 * @return true if just unzipped, false if the cache was still fresh
 */
fun unZipIfNew(zipFile: Path, outputDirectory: Path, allowOutsideLinks: Boolean = false, existenceIsEnough:Boolean = false):Boolean {
	if (existenceIsEnough) {
		if (!outputDirectory.isDirectory()) {
			unZip(zipFile, outputDirectory, allowOutsideLinks)
			return true
		}
	} else {
		val markerFile = outputDirectory / ".wemi_unzip_marker"
		val markerMod = markerFile.lastModifiedMillis()
		val zipMod = zipFile.lastModifiedMillis()
		if (markerMod < 0L || markerMod < zipMod) {
			unZip(zipFile, outputDirectory, allowOutsideLinks)
			Files.deleteIfExists(markerFile)
			Files.createFile(markerFile)
			return true
		}
	}

	return false
}

fun unZip(zipFile: Path, outputDirectory: Path, allowOutsideLinks: Boolean = false) {
	val tempDir = outputDirectory.resolveSibling(outputDirectory.name+"-temp")
	LOG.debug("Unpacking {} to {}", zipFile, tempDir)
	tempDir.ensureEmptyDirectory()

	Files.newByteChannel(zipFile, StandardOpenOption.READ).use { channel ->
		val zip = ZipFile(channel, zipFile.name, Charsets.UTF_8.name(), true, false)

		for (entry in zip.entries) {
			val entryPath = (tempDir / entry.name).normalize()
			if (!entryPath.startsWith(tempDir)) {
				LOG.warn("UnZip: Skipping entry {} of {} - it goes outside of the result folder!", entry.name, zipFile)
				continue
			}
			if (entry.isDirectory) {
				LOG.debug("UnZip: {} of {} to {} (a directory)", entry.name, zipFile, entryPath)
				Files.createDirectories(entryPath)
				copyZipAttributes(entry, entryPath)
				continue
			}
			if (!zip.canReadEntryData(entry)) {
				LOG.warn("UnZip: Skipping entry {} of {} - can't read", entry.name, zipFile)
				continue
			}

			if (entry.isUnixSymlink) {
				LOG.debug("UnZip: {} of {} to {} (a symbolic link)", entry.name, zipFile, entryPath)

				val linkPath = (tempDir / zip.getUnixSymlink(entry)).normalize()
				if (linkPath.startsWith(tempDir) && !allowOutsideLinks) {
					LOG.warn("UnZip: Skipping entry {} of {} - symbolically linked file is outside of the result folder", entry.name, zipFile)
					continue
				}
				try {
					Files.createDirectories(entryPath.parent)
					Files.createSymbolicLink(entryPath, linkPath)
					copyZipAttributes(entry, entryPath)
				} catch (e:Exception) {
					LOG.warn("UnZip: Failure on symbolic link entry {} of {}", entry.name, zipFile, e)
				}
				continue
			}

			LOG.debug("UnZip: {} of {} to {} (a normal file)", entry.name, zipFile, entryPath)
			Files.createDirectories(entryPath.parent)
			Files.newOutputStream(entryPath).use {
				zip.getInputStream(entry).copyTo(it)
			}
			copyZipAttributes(entry, entryPath)
		}
	}

	outputDirectory.ensureEmptyDirectory()
	Files.move(tempDir, outputDirectory, StandardCopyOption.REPLACE_EXISTING)
}

private fun copyZipAttributes(entry: ZipArchiveEntry, file: Path) {
	val modTime = entry.time
	if (modTime > 0L) {
		try {
			Files.setLastModifiedTime(file, FileTime.fromMillis(modTime))
		} catch (e: IOException) {
			LOG.debug("Failed to set mod time on {}", file)
		}
	}

	val mode = entry.unixMode
	if (mode != 0) {
		// While 0 is a valid mode, it is more probable that the mode was not saved properly
		try {
			val permissions = EnumSet.noneOf(PosixFilePermission::class.java)
			// Apparently same as in Tar: https://unix.stackexchange.com/questions/14705/the-zip-formats-external-file-attribute
			//#define TUREAD   00400          /* read by owner */
			//#define TUWRITE  00200          /* write by owner */
			//#define TUEXEC   00100          /* execute/search by owner */
			//#define TGREAD   00040          /* read by group */
			//#define TGWRITE  00020          /* write by group */
			//#define TGEXEC   00010          /* execute/search by group */
			//#define TOREAD   00004          /* read by other */
			//#define TOWRITE  00002          /* write by other */
			//#define TOEXEC   00001          /* execute/search by other */
			if (mode and 0x1 != 0) {
				permissions.add(PosixFilePermission.OTHERS_EXECUTE)
			}
			if (mode and 0x2 != 0) {
				permissions.add(PosixFilePermission.OTHERS_WRITE)
			}
			if (mode and 0x4 != 0) {
				permissions.add(PosixFilePermission.OTHERS_READ)
			}
			if (mode and 0x10 != 0) {
				permissions.add(PosixFilePermission.GROUP_EXECUTE)
			}
			if (mode and 0x20 != 0) {
				permissions.add(PosixFilePermission.GROUP_WRITE)
			}
			if (mode and 0x40 != 0) {
				permissions.add(PosixFilePermission.GROUP_READ)
			}
			if (mode and 0x100 != 0) {
				permissions.add(PosixFilePermission.OWNER_EXECUTE)
			}
			if (mode and 0x200 != 0) {
				permissions.add(PosixFilePermission.OWNER_WRITE)
			}
			if (mode and 0x400 != 0) {
				permissions.add(PosixFilePermission.OWNER_READ)
			}
			Files.setPosixFilePermissions(file, permissions)
		} catch (e: IOException) {
			LOG.debug("Failed to set permissions on {}", file)
		}
	}
	// Ignoring owner, group and other stuff, it is not important for our use-case
}
