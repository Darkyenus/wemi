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
val JarMergeStrategyChooser:MergeStrategyChooser = { name ->
    // Based on https://github.com/sbt/sbt-assembly logic
    if (FileRecognition.isReadme(name) || FileRecognition.isLicenseFile(name)) {
        MergeStrategy.Rename
    } else if (FileRecognition.isSystemJunkFile(name)) {
        MergeStrategy.Discard
    } else if (name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) {
        MergeStrategy.Rename
    } else if (name.startsWith("META-INF/services/", ignoreCase = true)) {
        MergeStrategy.Concatenate
    } else if (name.startsWith("META-INF/", ignoreCase = true)) {
        MergeStrategy.SingleOwn
    } else {
        MergeStrategy.Deduplicate
    }
}

/**
 * [MergeStrategyChooser] that assumes that no file should have multiple variants.
 */
val NoConflictStrategyChooser:MergeStrategyChooser = { MergeStrategy.Deduplicate }

/**
 * First argument is the source of the data, second is the path inside the root. Returns new path or null to discard.
 */
typealias RenameFunction = (AssemblySource, String) -> String?

/**
 * Rename function that keeps names of own files and injects name of source file to others.
 */
val DefaultRenameFunction:RenameFunction = { root, name ->
    if (root.own) {
        name
    } else {
        val injectedName = root.file?.name?.pathWithoutExtension() ?: "(unknown)"
        val extensionSeparator = name.lastIndexOf('.')
        if (extensionSeparator == -1) {
            name + '_' + injectedName
        } else {
            name.substring(0, extensionSeparator) + '_' + injectedName + name.substring(extensionSeparator)
        }
    }
}

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

    private fun normalizeZipPath(path:String):String {
        return path.replace('\\', '/').removePrefix("/")
    }

    private val loadedSources = LinkedHashMap<String, ArrayList<AssemblySource>>()
    private val filesToClose = ArrayList<Closeable>()

    fun addSource(locatedFile: LocatedFile, own: Boolean, extractJarEntries:Boolean = true) {
        val file = locatedFile.file
        if (extractJarEntries && file.name.pathHasExtension("jar")) {
            // Add jar entries
            val zip = ZipFile(file.toFile(), ZipFile.OPEN_READ, StandardCharsets.UTF_8)
            filesToClose.add(zip)

            for (entry in zip.entries()) {
                if (entry.isDirectory) continue

                val path = normalizeZipPath(entry.name)
                loadedSources.getOrPut(path) { ArrayList() }.add(object : AssemblySource(file, file, entry, own) {

                    override fun load(): ByteArray = zip.getInputStream(entry).use { it.readBytes(entry.size.toInt()) }

                    override val debugName: String = file.absolutePath + '?' + path
                })
            }
        } else {
            // Add file entry
            loadedSources.getOrPut(normalizeZipPath(locatedFile.path)) { ArrayList() }.add(object : AssemblySource(locatedFile.root, file, null, own) {

                override fun load(): ByteArray = Files.readAllBytes(file)

                override val debugName: String = locatedFile.toString()
            })
        }
    }

    fun addSource(path:String, data:ByteArray, own: Boolean) {
        loadedSources.getOrPut(normalizeZipPath(path)) { ArrayList() }.add(object : AssemblySource(null, null, null, own) {
            override fun load(): ByteArray = data

            override val debugName: String
                get() = "(custom $path)"
        })
    }

    /**
     * Assembly added sources and produce jar with [outputFile] path.
     * [prependData] will be prepended to the jar, but not in accordance with zip file format. `java` can still
     * read those files, but other tools do not.
     * [mergeStrategy] and [renameFunction] are used iff [addSource] has added duplicate entries,
     * to resolve how they should be handled.
     *
     * @throws WemiException on failure
     */
    fun assembly(mergeStrategy: MergeStrategyChooser, renameFunction: RenameFunction, outputFile: Path, prependData:ByteArray, compress:Boolean) {
        // Trim duplicates
        val assemblySources = LinkedHashMap<String, Pair<AssemblySource?, ByteArray>>()

        var hasError = false

        // Renaming has to be done later, because it is not yet known which paths are clean for renaming
        val sourcesToBeRenamed = LinkedHashMap<String, MutableList<AssemblySource>>()

        for ((path, dataList) in loadedSources) {
            if (dataList.size == 1) {
                val single = dataList[0]
                if (LOG.isTraceEnabled) {
                    LOG.trace("Including single item {}", single.debugName)
                }
                assemblySources[path] = Pair(single, single.data)
                continue
            }

            // Resolve duplicate
            val strategy = mergeStrategy(path)
            when (strategy) {
                MergeStrategy.First -> {
                    val first = dataList.first()
                    if (LOG.isDebugEnabled) {
                        LOG.debug("Including first item {}", first.debugName)
                    }
                    assemblySources[path] = Pair(first, first.data)
                }
                MergeStrategy.Last -> {
                    val last = dataList.last()
                    if (LOG.isDebugEnabled) {
                        LOG.debug("Including last item {}", last.debugName)
                    }
                    assemblySources[path] = Pair(last, last.data)
                }
                MergeStrategy.SingleOwn -> {
                    var own: AssemblySource? = null
                    for (source in dataList) {
                        if (source.own) {
                            if (own == null) {
                                own = source
                            } else {
                                LOG.error("Own file at {} is also duplicated, one is at {} and other at {}", own.debugName, source.debugName)
                                hasError = true
                            }
                        }
                    }

                    if (own == null) {
                        if (LOG.isDebugEnabled) {
                            LOG.debug("Discarding {} because none of the {} variants is own", path, dataList.size)
                            var i = 1
                            for (source in dataList) {
                                LOG.debug("\t{}) {}", i, source.debugName)
                                i += 1
                            }
                        }
                    } else {
                        if (LOG.isDebugEnabled) {
                            LOG.debug("Using own version of {} out of {} candidates", path, dataList.size)
                        }
                        assemblySources[path] = Pair(own, own.data)
                    }
                }
                MergeStrategy.SingleOrError -> {
                    LOG.error("File at {} has {} candidates, which is illegal under SingleOrError merge strategy", path, dataList.size)
                    var i = 1
                    for (source in dataList) {
                        LOG.error("\t{}) {}", i, source.debugName)
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
                            LOG.debug("\t{}) {}", i, source.debugName)
                            i += 1
                        }
                    }

                    assemblySources[path] = Pair(null, concatenated)
                }
                MergeStrategy.Discard -> {
                    if (LOG.isDebugEnabled) {
                        LOG.debug("Discarding {} items", dataList.size)
                        var i = 1
                        for (source in dataList) {
                            LOG.debug("\t{}) {}", i, source.debugName)
                            i += 1
                        }
                    }
                }
                MergeStrategy.Deduplicate -> {
                    val data = Array(dataList.size) { i -> dataList[i].data }

                    for (i in 1..data.lastIndex) {
                        if (!data[0].contentEquals(data[i])) {
                            LOG.error("Content for path {} given by {} is not the same as the content provided by {}", path, dataList[0].debugName, dataList[i].debugName)
                            hasError = true
                        }
                    }

                    assemblySources[path] = Pair(dataList[0], data[0])
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
                            LOG.debug("\t{}) discarding {}", debugIndex, source.debugName)
                        }
                    } else {
                        val alreadyPresent = assemblySources.containsKey(renamedPath)

                        if (alreadyPresent) {
                            LOG.error("Can't rename {} from {} to {}, this path is already occupied", path, source.root, renamedPath)
                            hasError = true
                        } else {
                            if (LOG.isDebugEnabled) {
                                LOG.debug("\t({}) moving {} to {}", debugIndex, source.debugName, renamedPath)
                            }

                            assemblySources[renamedPath] = Pair(source, source.data)
                        }
                    }
                    debugIndex += 1
                }
            }
        }

        if (hasError) {
            throw WemiException("assembly task failed", showStacktrace = false)
        }

        BufferedOutputStream(Files.newOutputStream(outputFile)).use { out ->
            if (prependData.isNotEmpty()) {
                out.write(prependData)
            }

            val jarOut = JarOutputStream(out)

            val crc: CRC32? = if (compress) null else CRC32()

            for ((path, value) in assemblySources) {
                val (source, data) = value

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
                if (source != null) {
                    if (source.zipEntry != null && source.zipEntry.time != -1L) {
                        entry.time = source.zipEntry.time
                    } else if (source.file != null) {
                        entry.time = source.file.lastModified.toMillis()
                    }
                }

                jarOut.putNextEntry(entry)
                jarOut.write(data)
                jarOut.closeEntry()

                if (LOG.isDebugEnabled) {
                    if (source != null) {
                        LOG.debug("Writing out entry {} ({} bytes) from {}", path, data.size, source.debugName)
                    } else {
                        LOG.debug("Writing out entry {} ({} bytes)", path, data.size)
                    }
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
            } catch (ignored:Exception) {}
        }
        filesToClose.clear()
    }
}