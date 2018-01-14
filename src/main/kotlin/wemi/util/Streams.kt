package wemi.util

import java.io.InputStream
import java.io.OutputStream
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
open class LineReadingOutputStream(charset: Charset = Charsets.UTF_8, private val onLineRead: (CharSequence) -> Unit) : OutputStream() {

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

    private fun flushLine() {
        onLineRead(outputSB)
        outputSB.setLength(0)
    }

    private fun decode(endOfInput: Boolean) {
        inputBuffer.flip()
        while (true) {
            val result = decoder.decode(inputBuffer, outputBuffer, endOfInput)
            outputBuffer.flip()
            for (i in 0 until outputBuffer.limit()) {
                val c = outputBuffer[i]
                outputSB.append(c)

                if (c == '\n') {
                    // Flush outputSB!
                    flushLine()
                }
            }

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

    /**
     * Flushes the pending, unfinished line.
     * Writing further bytes into the stream after closing it leads to an undefined behavior.
     */
    override fun close() {
        decode(true)
        flushLine()
    }
}