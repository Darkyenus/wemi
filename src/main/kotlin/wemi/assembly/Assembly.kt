@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package wemi.assembly

import org.slf4j.LoggerFactory
import wemi.WemiException
import wemi.util.*
import java.io.BufferedOutputStream
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * A script to be used in [wemi.Keys.assemblyPrependData] which launches self jar with `exec java -jar` command.
 */
val PREPEND_SCRIPT_EXEC_JAR: ByteArray = "#!/usr/bin/env sh\nexec java -jar \"$0\" \"$@\"\n"
        .toByteArray(Charsets.UTF_8)

private val LOG = LoggerFactory.getLogger("Assembly")

/**
 * Chooses a merge strategy for given path. Called only when there is multiple files with the same path in [AssemblyOperation].
 */
typealias MergeStrategyChooser = (String) -> MergeStrategy

/**
 * [MergeStrategyChooser] that handles most duplicates that arise when merging jars together.
 */
val JarMergeStrategyChooser: MergeStrategyChooser = { name ->
    // Based on https://github.com/sbt/sbt-assembly logic
    if (FileRecognition.isReadme(name) || FileRecognition.isLicenseFile(name)) {
        MergeStrategy.Rename
    } else if (FileRecognition.isSystemJunkFile(name)) {
        MergeStrategy.Discard
    } else if (name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) {
        MergeStrategy.Rename
    } else if (name.startsWith("META-INF/services/", ignoreCase = true)) {
        MergeStrategy.UniqueLines
    } else if (name.startsWith("META-INF/", ignoreCase = true)) {
        MergeStrategy.SingleOwn
    } else {
        MergeStrategy.Deduplicate
    }
}

/**
 * [MergeStrategyChooser] that assumes that no file should have multiple variants.
 */
val NoConflictStrategyChooser: MergeStrategyChooser = { MergeStrategy.Deduplicate }

/**
 * First argument is the source of the data, second is the path inside the root. Returns new path or null to discard.
 */
typealias RenameFunction = (AssemblySource, String) -> String?

/**
 * Rename function that keeps names of own files and injects name of source file to others.
 */
val DefaultRenameFunction: RenameFunction = { root, name ->
    if (root.own) {
        name
    } else {
        val injectedName = root.sourceFile?.name?.pathWithoutExtension() ?: "(unknown)"
        val extensionSeparator = name.lastIndexOf('.')
        if (extensionSeparator == -1) {
            name + '_' + injectedName
        } else {
            name.substring(0, extensionSeparator) + '_' + injectedName + name.substring(extensionSeparator)
        }
    }
}

/**
 * Function gets the in-archive path and source of the data.
 * It may then choose to perform any transformation or filtering, by returning null.
 */
typealias AssemblyMapFilter = (path:String, source: AssemblySource) -> ByteArray?

val DefaultAssemblyMapFilter:AssemblyMapFilter = { _, source -> source.data }

/**
 * Prepend data to use, when no data should be prepended.
 *
 * @see AssemblyOperation.assembly prependData parameter
 */
val NoPrependData = ByteArray(0)

/**
 * Represents a packing operation. Contains internal list of sources, to which elements can be added via
 * [addSource]. Actual assembling is invoked via [assembly] and is safe to do multiple times (with same or added sources)
 * if needed. [close] should be called when [AssemblyOperation] is no longer needed.
 */
class AssemblyOperation : Closeable {

    private fun normalizeZipPath(path: String): String {
        return path.replace('\\', '/').removePrefix("/")
    }

    private val loadedSources = LinkedHashMap<String, ArrayList<AssemblySource>>()
    private val filesToClose = ArrayList<Closeable>()

    fun addSource(locatedFile: LocatedFile, own: Boolean, extractJarEntries: Boolean = true) {
        val file = locatedFile.file
        if (extractJarEntries && file.name.pathHasExtension("jar")) {
            // Add jar entries
            val zip = ZipFile(file.toFile(), ZipFile.OPEN_READ, StandardCharsets.UTF_8)
            filesToClose.add(zip)

            for (entry in zip.entries()) {
                if (entry.isDirectory) continue

                val path = normalizeZipPath(entry.name)
                loadedSources.getOrPut(path) { ArrayList() }.add(
                        AssemblySource(file.absolutePath + '?' + path, file, file.lastModified.toMillis(), own) {
                            zip.getInputStream(entry).use { it.readBytes(entry.size.toInt()) }
                        })
            }
        } else {
            // Add file entry
            loadedSources.getOrPut(normalizeZipPath(locatedFile.path)) { ArrayList() }.add(AssemblySource(locatedFile.toString(), file, file.lastModified.toMillis(), own) {

                Files.readAllBytes(file)
            })
        }
    }

    fun addSource(path: String, data: ByteArray, own: Boolean) {
        loadedSources.getOrPut(normalizeZipPath(path)) { ArrayList() }.add(AssemblySource("(custom $path)", null, -1L, own, data))
    }

    /**
     * Resolve duplicates and return sources that should be assembled into the resulting archive.
     * Used by [assembly].
     * [mergeStrategy] and [renameFunction] are used iff [addSource] has added duplicate entries,
     * to resolve how they should be handled.
     */
    fun resolve(mergeStrategy: MergeStrategyChooser, renameFunction: RenameFunction): MutableMap<String, AssemblySource>? {
        // Trim duplicates
        val assemblySources = LinkedHashMap<String, AssemblySource>()

        var hasError = false

        // Renaming has to be done later, because it is not yet known which paths are clean for renaming
        val sourcesToBeRenamed = LinkedHashMap<String, ArrayList<AssemblySource>>()

        for ((path, dataList) in loadedSources) {
            if (dataList.size == 1) {
                val single = dataList[0]
                if (LOG.isTraceEnabled) {
                    LOG.trace("Including single item {}", single)
                }
                assemblySources[path] = single
                continue
            }

            // Resolve duplicate
            val strategy = mergeStrategy(path)
            when (strategy) {
                MergeStrategy.First -> {
                    val first = dataList.first()
                    LOG.debug("Including first item {}", first)
                    assemblySources[path] = first
                }
                MergeStrategy.Last -> {
                    val last = dataList.last()
                    LOG.debug("Including last item {}", last)
                    assemblySources[path] = last
                }
                MergeStrategy.SingleOwn -> {
                    var own: AssemblySource? = null
                    for (source in dataList) {
                        if (source.own) {
                            if (own == null) {
                                own = source
                            } else {
                                LOG.error("Own file at {} is also duplicated, one is at {} and other at {}", own, source)
                                hasError = true
                            }
                        }
                    }

                    if (own == null) {
                        if (LOG.isDebugEnabled) {
                            LOG.debug("Discarding {} because none of the {} variants is own", path, dataList.size)
                            var i = 1
                            for (source in dataList) {
                                LOG.debug("\t{}) {}", i, source)
                                i += 1
                            }
                        }
                    } else {
                        if (LOG.isDebugEnabled) {
                            LOG.debug("Using own version of {} out of {} candidates", path, dataList.size)
                        }
                        assemblySources[path] = own
                    }
                }
                MergeStrategy.SingleOrError -> {
                    LOG.error("File at {} has {} candidates, which is illegal under SingleOrError merge strategy", path, dataList.size)
                    var i = 1
                    for (source in dataList) {
                        LOG.error("\t{}) {}", i, source)
                        i += 1
                    }
                    hasError = true
                }
                MergeStrategy.Concatenate -> {
                    var totalLength = 0
                    val data = Array(dataList.size) { i ->
                        val loaded = dataList[i].data
                        totalLength += loaded.size
                        loaded
                    }

                    val concatenated = ByteArray(totalLength)
                    var pointer = 0
                    for (d in data) {
                        System.arraycopy(d, 0, concatenated, pointer, d.size)
                        pointer += d.size
                    }

                    if (LOG.isDebugEnabled) {
                        LOG.debug("Including {} concatenated items ({} bytes total)", dataList.size, totalLength)
                        var i = 1
                        for (source in dataList) {
                            LOG.debug("\t{}) {}", i, source)
                            i += 1
                        }
                    }

                    assemblySources[path] = AssemblySource(strategy.name, null, System.currentTimeMillis(), false, concatenated)
                }
                MergeStrategy.Lines, MergeStrategy.UniqueLines -> {
                    val lineEndings = arrayOf("\r\n", "\n", "\r")
                    val lines: MutableCollection<String> = if (strategy != MergeStrategy.UniqueLines) ArrayList() else LinkedHashSet()
                    var lineEnding: String? = null
                    var totalLength = 0
                    var lineEndingsInconsistent = false

                    for (assemblySource in dataList) {
                        val sourceString = String(assemblySource.data, Charsets.UTF_8)
                        val sourceLines = sourceString.split(*lineEndings)
                        if (!lineEndingsInconsistent && sourceLines.size > 1) {
                            // If line endings are inconsistent, or there are none, don't bother searching
                            for (le in lineEndings) {
                                if (sourceString.contains(le)) {
                                    if (lineEnding == null) {
                                        lineEnding = le
                                    } else if (lineEnding != le) {
                                        lineEndingsInconsistent = true
                                    }
                                    break
                                }
                            }
                        }
                        // Add all lines (except empty trailing newline)
                        for (i in 0 until sourceLines.size - if (sourceLines.last().isEmpty()) 1 else 0) {
                            lines.add(sourceLines[i].apply { totalLength += length })
                        }
                    }

                    if (lineEnding == null) {
                        lineEnding = "\n"
                    }

                    val result = StringBuilder(totalLength + lines.size * lineEnding.length)
                    for (line in lines) {
                        result.append(line).append(lineEnding)
                    }

                    assemblySources[path] = AssemblySource(strategy.name, null, System.currentTimeMillis(), false, result.toString().toByteArray(Charsets.UTF_8))
                }
                MergeStrategy.Discard -> {
                    if (LOG.isDebugEnabled) {
                        LOG.debug("Discarding {} items", dataList.size)
                        var i = 1
                        for (source in dataList) {
                            LOG.debug("\t{}) {}", i, source)
                            i += 1
                        }
                    }
                }
                MergeStrategy.Deduplicate -> {
                    val data = Array(dataList.size) { i -> dataList[i].data }

                    for (i in 1..data.lastIndex) {
                        if (!data[0].contentEquals(data[i])) {
                            LOG.error("Content for path {} given by {} is not the same as the content provided by {}", path, dataList[0], dataList[i])
                            hasError = true
                        }
                    }

                    assemblySources[path] = dataList[0]
                }
                MergeStrategy.Rename -> {
                    sourcesToBeRenamed[path] = dataList
                }
            }
        }

        // Resolve those that should be renamed
        if (sourcesToBeRenamed.isNotEmpty()) {
            for ((path, dataList) in sourcesToBeRenamed) {
                if (LOG.isDebugEnabled) {
                    LOG.debug("Renaming {} items at {}", dataList.size, path)
                }
                var debugIndex = 1

                for (source in dataList) {
                    val renamedPath = normalizeZipPath(renameFunction(source, path) ?: "")
                    if (renamedPath.isEmpty()) {
                        if (LOG.isDebugEnabled) {
                            LOG.debug("\t{}) discarding {}", debugIndex, source)
                        }
                    } else {
                        val alreadyPresent = assemblySources[renamedPath]

                        if (alreadyPresent != null) {
                            LOG.error("Can't rename {} at {} to {}, this path is already occupied by {}", source, path, renamedPath, alreadyPresent)
                            hasError = true
                        } else {
                            if (LOG.isDebugEnabled) {
                                LOG.debug("\t({}) moving {} to {}", debugIndex, source, renamedPath)
                            }

                            assemblySources[renamedPath] = source
                        }
                    }
                    debugIndex += 1
                }
            }
        }

        if (hasError) {
            return null
        } else {
            return assemblySources
        }
    }

    /**
     * Assembly added sources and produce jar with [outputFile] path.
     * [prependData] will be prepended to the jar, but not in accordance with zip file format. `java` can still
     * read those files, but other tools do not.
     *
     * @see resolve
     * @throws WemiException on failure
     */
    fun assembly(mergeStrategy: MergeStrategyChooser, renameFunction: RenameFunction, mapFilter:AssemblyMapFilter, outputFile: Path, prependData: ByteArray, compress: Boolean) {
        val assemblySources = resolve(mergeStrategy, renameFunction)
                ?: throw WemiException("assembly task failed", showStacktrace = false)

        BufferedOutputStream(Files.newOutputStream(outputFile)).use { out ->
            if (prependData.isNotEmpty()) {
                out.write(prependData)
            }

            val jarOut = JarOutputStream(out)

            val crc: CRC32? = if (compress) null else CRC32()

            for ((path, source) in assemblySources) {
                val data = mapFilter(path, source)
                if (data == null) {
                    if (LOG.isDebugEnabled) {
                        LOG.debug("Filtered out entry {} from {}", path, source)
                    }
                    continue
                }

                val entry = ZipEntry(path)
                if (compress) {
                    entry.method = ZipEntry.DEFLATED
                    entry.size = data.size.toLong()
                    // compressed size and crc unknown
                } else {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = entry.size
                    crc!!.update(data)
                    entry.crc = crc.value
                    crc.reset()
                }

                if (source.lastModifiedMs != -1L) {
                    entry.time = source.lastModifiedMs
                }

                jarOut.putNextEntry(entry)
                jarOut.write(data)
                jarOut.closeEntry()

                if (LOG.isDebugEnabled) {
                    LOG.debug("Writing out entry {} ({} bytes) from {}", path, data.size, source)
                }
            }

            jarOut.finish()
            jarOut.flush()
            jarOut.close()

            LOG.debug("{} entries written", assemblySources.size)
        }
    }

    /**
     * Close internal open resources.
     * After this call, [AssemblyOperation] is in undefined state and should not be used any more.
     */
    override fun close() {
        for (closeable in filesToClose) {
            try {
                closeable.close()
            } catch (ignored: Exception) {
            }
        }
        filesToClose.clear()
    }
}