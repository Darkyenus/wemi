package wemi.util

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * Stream utilities
 */

fun readFully(into: OutputStream, stream: InputStream, buffer:ByteArray = ByteArray(1024)):Int {
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

open class LineReadingOutputStream(charset: Charset = Charsets.UTF_8, private val onLineRead:(CharSequence) -> Unit) : OutputStream() {

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

    private fun decode() {
        inputBuffer.flip()
        while (true) {
            val result = decoder.decode(inputBuffer, outputBuffer, false)
            outputBuffer.flip()
            for (i in 0 until outputBuffer.limit()) {
                val c = outputBuffer[i]
                outputSB.append(c)

                if (c == '\n') {
                    // Flush outputSB!
                    onLineRead(outputSB)
                    outputSB.setLength(0)
                }
            }

            if (!result.isOverflow) {
                break
            }
        }

        inputBuffer.compact()
    }

    override fun write(b: Int) {
        inputBuffer.put(b.toByte())
        decode()
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
            decode()
        }
    }
}