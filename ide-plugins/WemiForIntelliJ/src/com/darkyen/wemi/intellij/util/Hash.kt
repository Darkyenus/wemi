package com.darkyen.wemi.intellij.util

import java.io.File
import java.security.MessageDigest

/**
 *
 */
private const val HexDigits = "0123456789abcdef"

fun toHexString(data: ByteArray): String {
    val chars = CharArray(data.size * 2)
    for (i in data.indices) {
        chars[2 * i] = HexDigits[(data[i].toInt() ushr 4) and 0xF]
        chars[2 * i + 1] = HexDigits[data[i].toInt() and 0xF]
    }
    return String(chars)
}

fun MessageDigest.update(i:Int) {
    update((i and 0xFF).toByte())
    update(((i ushr 8) and 0xFF).toByte())
    update(((i ushr 16) and 0xFF).toByte())
    update(((i ushr 24) and 0xFF).toByte())
}

fun MessageDigest.update(string: String) {
    update(string.length)
    for (c in string) {
        update(c.toByte())
    }
}

fun MessageDigest.update(file: File) {
    update(file.canonicalPath)
}

inline fun <T> MessageDigest.update(list:List<T>, updateElement: MessageDigest.(T)->Unit) {
    update(list.size)
    for (t in list) {
        updateElement(t)
    }
}

inline fun digestToHexString(algorithm: String = "MD5", digester: MessageDigest.() -> Unit):String {
    val md = MessageDigest.getInstance(algorithm)
    md.digester()
    return toHexString(md.digest())
}