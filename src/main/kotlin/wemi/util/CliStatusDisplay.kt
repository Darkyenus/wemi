package wemi.util

import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.utils.*
import kotlin.math.max
import kotlin.math.min

/**
 * Implements a status bar with an arbitrary message for CLI output.
 * All output must be streamed through this class and will be written into the [terminal].
 */
internal class CliStatusDisplay(private val terminal: Terminal) : LineReadingOutputStream() {

    /*
    Useful links:
    https://www.mkssoftware.com/docs/man5/terminfo.5.asp (terminal capabilities briefly explained)
     */

    private val terminalWriter = terminal.writer()
    private val display = Display(terminal, false)

    @set:Synchronized
    var enabled = false
        set(value) {
            if (field != value) {
                if (value) {
                    doEnable()
                    synchronized(enabledStatusDisplays) {
                        enabledStatusDisplays.add(this)
                    }
                } else {
                    doDisable()
                    synchronized(enabledStatusDisplays) {
                        enabledStatusDisplays.remove(this)
                    }
                }
                field = value
            }
        }

    private var width = 0
    private var originalAttributes: Attributes? = null
    private var prevHandler: Terminal.SignalHandler? = null

    private var message:AttributedString = AttributedString.EMPTY
    private var messageImportantPrefix:Int = 0

    /**
     * @param message to be shown, when enabled (if enabled, the change will be presented immediately)
     * @param importantPrefix how many characters from the start should not be ellipsized
     */
    @Synchronized
    fun setMessage(message:AttributedString, importantPrefix:Int) {
        this.message = message
        this.messageImportantPrefix = importantPrefix
        if (enabled) {
            show()
        }
    }

    private val _emptyLine = listOf(AttributedString.EMPTY)
    private fun hide() {
        display.update(_emptyLine, 0)
        terminalWriter.flush()
    }

    private fun show() {
        val builder = AttributedStringBuilder(message.length + 10)
        val messageLength = message.columnLength()

        if (messageLength <= width) {
            builder.append(message)
        } else {
            val ellipsis = '…'
            val ellipsisWidth = WCWidth.wcwidth(ellipsis.toInt())

            val importantPrefix = message.substring(0, max(min(message.length, messageImportantPrefix), 0))
            val importantPrefixWidth = importantPrefix.columnLength()

            if (importantPrefixWidth >= width) {
                // Truncate from back, not even important part fits
                val howMuchOfImportantPartFits = importantPrefix.lengthForColumnWidth(0, width - ellipsisWidth)
                builder.append(message.columnSubSequence(0, howMuchOfImportantPartFits))
                builder.append(ellipsis)
            } else {
                // Important prefix fits fine, truncate right after it
                builder.append(message, 0, messageImportantPrefix)
                builder.append(ellipsis)
                val remaining = width - importantPrefixWidth - ellipsisWidth
                val last = message.lengthForColumnWidthBackwards(message.length, remaining)
                builder.append(message, message.length - last, message.length)
            }
        }

        display.update(listOf(builder.toAttributedString()), 0)
        terminalWriter.flush()
    }

    private var incompleteLineCharacters = 0

    @Synchronized
    override fun onLineRead(line: CharSequence) {
        if (enabled) {
            hide()
            if (incompleteLineCharacters > 0) {
                terminal.puts(InfoCmp.Capability.cursor_up)
                terminal.puts(InfoCmp.Capability.parm_right_cursor, incompleteLineCharacters)
            }
            terminalWriter.append(line)
            show()
        } else {
            terminalWriter.append(line)
        }

        incompleteLineCharacters = 0
        terminalWriter.flush()
    }

    @Synchronized
    override fun onCharactersFlushed(characters: CharSequence): Boolean {
        if (enabled) {
            hide()
            if (incompleteLineCharacters > 0) {
                terminal.puts(InfoCmp.Capability.cursor_up)
                terminal.puts(InfoCmp.Capability.parm_right_cursor, incompleteLineCharacters)
            }
            terminalWriter.append(characters)
            terminalWriter.append('\n')
            show()

            incompleteLineCharacters += characters.length
            return true
        } else {
            terminalWriter.append(characters)
            incompleteLineCharacters += characters.length
            return true
        }
    }

    private fun updateSize() {
        val width = terminal.width
        this.width = width
        display.resize(1, width)
    }

    private fun doEnable() {
        updateSize()
        prevHandler = terminal.handle(Terminal.Signal.WINCH) {
            synchronized(this@CliStatusDisplay) {
                if (enabled) {
                    hide()
                    updateSize()
                    show()
                    terminalWriter.flush()
                } else {
                    updateSize()
                }
            }
        }

        originalAttributes = terminal.enterRawMode()
        terminal.puts(InfoCmp.Capability.cursor_invisible)
        if (incompleteLineCharacters > 0) {
            terminalWriter.append('\n')
        }
        show()
        terminalWriter.flush()
    }

    private fun doDisable() {
        hide()
        if (incompleteLineCharacters > 0) {
            terminal.puts(InfoCmp.Capability.cursor_up)
            terminal.puts(InfoCmp.Capability.parm_right_cursor, incompleteLineCharacters)
        }
        terminal.puts(InfoCmp.Capability.cursor_normal)

        terminal.attributes = originalAttributes
        if (prevHandler != null) {
            terminal.handle(Terminal.Signal.WINCH, prevHandler)
        }
        terminalWriter.flush()
    }

    @Synchronized
    override fun flush() {
        super.flush()
        terminalWriter.flush()
    }

    private fun AttributedCharSequence.lengthForColumnWidth(begin:Int, columnWidth:Int):Int {
        var index = begin
        var width = 0
        while (index < this.length) {
            val cp = codePointAt(index)
            val w = if (isHidden(index)) 0 else WCWidth.wcwidth(cp)
            if (width + w > columnWidth) {
                break
            }
            index += Character.charCount(cp)
            width += w
        }
        return index - begin
    }

    private fun AttributedCharSequence.lengthForColumnWidthBackwards(end:Int, columnWidth:Int):Int {
        if (end <= 0) {
            return 0
        }
        var index = end - 1
        var width = 0
        while (index >= 0) {
            val cp = codePointAt(index)
            val w = if (isHidden(index)) 0 else WCWidth.wcwidth(cp)
            if (width + w > columnWidth) {
                break
            }
            index -= Character.charCount(cp)
            width += w
        }
        return end - (index + 1)
    }

    companion object {

        /** Do the [action] with status [enabled]. */
        inline fun <T> CliStatusDisplay?.withStatus(enabled:Boolean, action:()->T):T {
            if (this == null) {
                return action()
            }

            val enabledBefore = synchronized(this) {
                val enabledBefore = this.enabled
                this.enabled = enabled
                enabledBefore
            }
            try {
                return action()
            } finally {
                synchronized(this) {
                    this.enabled = enabledBefore
                }
            }
        }


        private val enabledStatusDisplays = HashSet<CliStatusDisplay>()

        init {
            Runtime.getRuntime().addShutdownHook(object : Thread("ShutdownEnabledCliStatusDisplays") {
                override fun run() {
                    // Less efficient kind of enumeration, but prevents any issues
                    // with enabledStatusDisplays changing while disabling them
                    synchronized(enabledStatusDisplays) {
                        while (enabledStatusDisplays.isNotEmpty()) {
                            enabledStatusDisplays.first().enabled = false
                        }
                    }
                }
            })
        }
    }
}