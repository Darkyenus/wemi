package wemi.util

import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.*

/**
 *
 */
private val HexDigits = "0123456789abcdef"

fun toHexString(data: ByteArray): String {
    val chars = CharArray(data.size * 2)
    for (i in data.indices) {
        chars[2 * i] = HexDigits[(data[i].toInt() ushr 4) and 0xF]
        chars[2 * i + 1] = HexDigits[data[i].toInt() and 0xF]
    }
    return String(chars)
}

fun fromHexString(data: CharSequence): ByteArray? {
    val bytes = ByteArray(data.length / 2)
    var byteI = 0

    var lowByte = -1

    for (c in data) {
        if (c.isWhitespace()) continue
        val digit = HexDigits.indexOf(c, ignoreCase = true)
        if (digit == -1) {
            return null
        }

        if (lowByte != -1) {
            bytes[byteI++] = (lowByte or digit).toByte()
            lowByte = -1
        } else {
            lowByte = digit shl 4
        }
    }

    if (lowByte != -1) {
        return null
    }

    if (byteI == bytes.size) {
        return bytes
    } else {
        return Arrays.copyOf(bytes, byteI)
    }
}

fun Path.hash(algorithm:String = "MD5"):ByteArray {
    val md = MessageDigest.getInstance(algorithm)
    DigestInputStream(Files.newInputStream(this), md).use {
        val buf = ByteArray(1024)
        while (it.read(buf) != -1) {}
        it.close()
    }
    return md.digest()
}

