package wemi.util

import org.jline.reader.History
import org.jline.reader.LineReader
import org.slf4j.LoggerFactory
import wemi.boot.WemiCacheFolder
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Simple [History] implementation that does sane saving and loading.
 */
internal class SimpleHistory(private val path: Path?) : History {

    private val DEFAULT_HISTORY_SIZE = 50

    val items = ArrayList<String>()
    private var index = 0
    private var changed = false

    init {
        load()
    }

    override fun attach(reader: LineReader) {
        // Do nothing, we don't care about what LineReader thinks
    }

    override fun load() {
        read(path, false)
    }

    override fun read(file: Path?, incremental: Boolean) {
        if (file == null || !Files.exists(file)) {
            return
        }
        val lines = try {
            Files.readAllLines(file, Charsets.UTF_8)
        } catch (e: Exception) {
            LOG.warn("Failed to load from path {}", path, e)
            return
        }
        if (incremental) {
            for (line in lines) {
                add(line)
            }
        } else {
            clear()
            items.addAll(lines)
            changed = false
            index = size()
        }
    }


    override fun save() {
        if (save(path, force = false, incremental = false, append = false)) {
            changed = false
        }
    }

    override fun write(file: Path?, incremental: Boolean) {
        save(file, true, incremental, false)
    }

    override fun append(file: Path?, incremental: Boolean) {
        save(file, true, incremental, true)
    }

    private fun save(file:Path?, force:Boolean, incremental:Boolean, append:Boolean):Boolean {
        if ((!changed && !force) || (!changed && incremental) || file == null) {
            return false
        }

        try {
            if (items.isEmpty() && !force) {
                Files.deleteIfExists(file)
            } else {
                val openOptions:Array<OpenOption> =
                        if (append)
                            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
                        else
                            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                Files.newBufferedWriter(file, Charsets.UTF_8, *openOptions).use { writer ->
                    for (line in items) {
                        writer.write(line)
                        writer.append('\n')
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to write to path {}", file, e)
            return false
        }

        return true
    }

    private fun clear() {
        items.clear()
        index = 0
        changed = true
    }

    override fun purge() {
        clear()
        if (path != null) {
            LOG.trace("Purging history from {}", path)
            Files.deleteIfExists(path)
        }
        changed = false
    }

    override fun size(): Int {
        return items.size
    }

    override fun isEmpty(): Boolean {
        return items.isEmpty()
    }

    override fun index(): Int = index

    override fun first(): Int = 0

    override fun last(): Int = items.size - 1

    override fun get(index: Int): String {
        return items[index]
    }

    override fun add(line: String) {
        items.remove(line)
        items.add(line)
        changed = true
        while (size() > DEFAULT_HISTORY_SIZE) {
            items.removeAt(0)
        }
        index = size()
    }

    override fun add(time: Instant, line: String) {
        add(line)
    }

    // This is used when navigating the history
    override fun iterator(index: Int): ListIterator<History.Entry> {
        val iterator = items.listIterator(index)
        return object : ListIterator<History.Entry> {
            override fun hasNext(): Boolean = iterator.hasNext()

            override fun hasPrevious(): Boolean = iterator.hasPrevious()

            override fun nextIndex(): Int = iterator.nextIndex()

            override fun previousIndex(): Int = iterator.previousIndex()

            private fun map(index: Int, line: String): History.Entry {
                return object : History.Entry {
                    override fun index(): Int = index

                    override fun time(): Instant = Instant.EPOCH

                    override fun line(): String = line
                }
            }

            override fun next(): History.Entry = map(iterator.nextIndex(), iterator.next())

            override fun previous(): History.Entry = map(iterator.previousIndex(), iterator.previous())
        }
    }

    //
    // Navigation
    //

    /**
     * This moves the history to the last entry. This entry is one position
     * before the moveToEnd() position.
     *
     * @return Returns false if there were no history iterator or the history
     * index was already at the last entry.
     */
    override fun moveToLast(): Boolean {
        val lastEntry = size() - 1
        if (lastEntry >= 0 && lastEntry != index) {
            index = size() - 1
            return true
        }

        return false
    }

    /**
     * Move to the specified index in the history
     */
    override fun moveTo(index: Int): Boolean {
        if (index >= 0 && index < size()) {
            this.index = index
            return true
        }
        return false
    }

    /**
     * Moves the history index to the first entry.
     *
     * @return Return false if there are no iterator in the history or if the
     * history is already at the beginning.
     */
    override fun moveToFirst(): Boolean {
        if (size() > 0 && index != 0) {
            index = 0
            return true
        }
        return false
    }

    /**
     * Move to the end of the history buffer. This will be a blank entry, after
     * all of the other iterator.
     */
    override fun moveToEnd() {
        index = size()
    }

    /**
     * Return the content of the current buffer.
     */
    override fun current(): String {
        return if (index < 0 || index >= size()) {
            ""
        } else {
            items[index]
        }
    }

    /**
     * Move the pointer to the previous element in the buffer.
     *
     * @return true if we successfully went to the previous element
     */
    override fun previous(): Boolean {
        if (index <= 0) {
            return false
        }
        index--
        return true
    }

    /**
     * Move the pointer to the next element in the buffer.
     *
     * @return true if we successfully went to the next element
     */
    override fun next(): Boolean {
        if (index >= size()) {
            return false
        }
        index++
        return true
    }

    override fun resetIndex() {
        index = if (index > items.size) items.size else index
    }

    override fun toString(): String {
        val sb = StringBuilder()
        for (e in this) {
            sb.append(e.toString()).append("\n")
        }
        return sb.toString()
    }

    companion object {

        private val LOG = LoggerFactory.getLogger(SimpleHistory::class.java)

        /**
         * All loaded histories.
         *
         * Currently used names:
         * - "repl" for REPL
         * - "input.<key>" for input <key>
         *
         * @see [getHistory]
         */
        private val histories = HashMap<String, SimpleHistory>()

        /**
         * Construct history file for given names.
         */
        private fun getHistoryFile(name: String): Path? {
            val historiesFolder = WemiCacheFolder.resolve("history")
            Files.createDirectories(historiesFolder)
            val fileName = StringBuilder()
            for (c in name) {
                if (c.isLetterOrDigit() || c.isWhitespace() || c == '.' || c == '_' || c == '-') {
                    fileName.append(c)
                } else {
                    fileName.append(c.toInt())
                }
            }

            return historiesFolder.resolve(fileName.toString())
        }

        /**
         * Get the CLI input history for given name.
         *
         * @see histories
         */
        internal fun getHistory(name: String): SimpleHistory {
            return histories.getOrPut(name) {
                SimpleHistory(getHistoryFile(name))
            }
        }

        /**
         * Retrieves the history, but only if it exists, either in cache or in filesystem.
         */
        internal fun getExistingHistory(name: String): SimpleHistory? {
            val existing = histories[name]
            if (existing != null) {
                return existing
            }

            val historyFile = getHistoryFile(name)
            if (historyFile == null || !Files.exists(historyFile)) {
                return null
            }

            val history = SimpleHistory(historyFile)
            histories[name] = history
            return history
        }

        /**
         * Return history name for [getHistory] or [getExistingHistory],
         * for storing history of given key.
         *
         * @param inputKey or null if free input
         */
        internal fun inputHistoryName(inputKey: String?):String {
            return if (inputKey == null) {
                "input"
            } else {
                "input.${inputKey.toLowerCase()}"
            }
        }

        /**
         * History when no history is needed
         */
        val NoHistory = SimpleHistory(null)

        init {
            // Prepare shutdown hook to save CLI histories
            Runtime.getRuntime().addShutdownHook(Thread({
                for (value in histories.values) {
                    value.save()
                }
                LOG.debug("Histories saved")
            }, "HistorySaver"))
        }
    }
}