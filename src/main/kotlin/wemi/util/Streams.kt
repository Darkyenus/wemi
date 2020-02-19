package wemi.util

import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * Read every available byte from [stream] and write it to [into].
 * Use [buffer] for it.
 *
 * Works only on streams that support [InputStream.available].
 */
fun readFully(into: OutputStream, stream: InputStream, buffer: ByteArray = ByteArray(1024)): Int {
    var read = 0
    while (true) {
        val available = stream.available()
        if (available <= 0) {
            break
        }
        val size = stream.read(buffer, 0, minOf(buffer.size, available))
        if (size == -1) {
            break
        }
        read += size
        into.write(buffer, 0, size)
    }
    return read
}

/**
 * OutputStream that buffers bytes until they can be converted to text using given charset and until the
 * text forms a valid line, ended with '\n'. Then it takes the line and calls [onLineRead] with it, without the line end.
 *
 * [close] to obtain the last line without ending '\n'.
 */
abstract class LineReadingOutputStream(charset: Charset = Charsets.UTF_8) : OutputStream() {

    private val decoder: CharsetDecoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private val inputBuffer: ByteBuffer = ByteBuffer.allocate(1024)
    private val outputBuffer: CharBuffer = CharBuffer.allocate(1024)
    private val outputSB = StringBuilder()

    init {
        inputBuffer.clear()
        outputBuffer.clear()
    }

    protected abstract fun onLineRead(line:CharSequence)

    /** Called when [flush] is called and the buffer contains some characters.
     * @return true when [characters] were consumed, they will not appear in the line in next [onLineRead]. False to ignore the flush. */
    protected open fun onCharactersFlushed(characters:CharSequence):Boolean {
        return false
    }

    private fun flushLine() {
        onLineRead(outputSB)
        outputSB.setLength(0)
    }

    private fun decode(endOfInput: Boolean) {
        inputBuffer.flip()
        while (true) {
            val result = decoder.decode(inputBuffer, outputBuffer, endOfInput)
            outputBuffer.flip()

            outputSB.ensureCapacity(outputBuffer.limit())
            for (i in 0 until outputBuffer.limit()) {
                val c = outputBuffer[i]
                outputSB.append(c)

                if (c == '\n') {
                    // Flush outputSB!
                    flushLine()
                }
            }
            outputBuffer.position(0)
            outputBuffer.limit(outputBuffer.capacity())

            if (result.isUnderflow) {
                break
            }
        }

        inputBuffer.compact()
    }

    override fun write(b: Int) {
        inputBuffer.put(b.toByte())
        decode(false)
    }

    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var offset = off
        var remaining = len

        while (remaining > 0) {
            val toConsume = minOf(remaining, inputBuffer.remaining())
            inputBuffer.put(b, offset, toConsume)
            offset += toConsume
            remaining -= toConsume
            decode(false)
        }
    }

    override fun flush() {
        super.flush()
        if (outputSB.isNotEmpty() && onCharactersFlushed(outputSB)) {
            outputSB.setLength(0)
        }
    }

    /**
     * Flushes the pending, unfinished line.
     * Writing further bytes into the stream after closing it leads to an undefined behavior.
     */
    override fun close() {
        decode(true)
        flushLine()
    }
}

/**
 * [Writer] that buffers characters until the text forms a valid line, ended with '\n'.
 * Then it takes the line and calls [onLineRead] with it, without the line end.
 *
 * [close] to obtain the last line without ending '\n'.
 */
open class LineReadingWriter(private val onLineRead: (CharSequence) -> Unit) : Writer() {

    private val outputSB = StringBuilder()

    private fun flushLine() {
        onLineRead(outputSB)
        outputSB.setLength(0)
    }

    override fun append(csq: CharSequence?): Writer {
        return append(csq, 0, csq?.length ?: 0)
    }

    override fun append(csq: CharSequence?, start: Int, end: Int): Writer {
        if (csq == null) {
            outputSB.append(null as String?)
        } else {
            for (i in start until end) {
                append(csq[i])
            }
        }
        return this
    }

    override fun append(c: Char): Writer {
        if (c == '\n') {
            onLineRead(outputSB)
            outputSB.setLength(0)
        } else {
            outputSB.append(c)
        }
        return this
    }

    override fun write(c: Int) {
        append(c.toChar())
    }

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        for (i in off until (off + len)) {
            append(cbuf[i])
        }
    }

    override fun write(str: String, off: Int, len: Int) {
        append(str, off, off+len)
    }

    /** No-op. */
    override fun flush() {}

    /**
     * Flushes the pending, unfinished line.
     * Object may be reused after that.
     */
    override fun close() {
        flushLine()
    }
}

internal open class GaugedInputStream(private val next:InputStream) : InputStream() {

    protected open var totalRead:Long = 0L

    override fun read(): Int {
        val read = next.read()
        if (read != -1) {
            totalRead++
        }
        return read
    }

    override fun read(b: ByteArray): Int {
        val read = next.read(b)
        if (read > 0) {
            totalRead += read
        }
        return read
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = next.read(b, off, len)
        if (read > 0) {
            totalRead += read
        }
        return read
    }

    override fun skip(n: Long): Long {
        val skipped = next.skip(n)
        if (skipped > 0) {
            totalRead += skipped
        }
        return skipped
    }

    override fun available(): Int {
        return next.available()
    }

    override fun close() {
        next.close()
    }
}