package com.darkyen.wemi.intellij.util

/**
 * Wraps version string and provides comparison capability over it.
 * Newer versions are larger.
 *
 * Version string is divided into parts (separated by dots).
 * Each part has its numeric value taken (from any leading digits), otherwise it is treated as 0.
 * When comparing two versions, missing parts are treated as 0.
 */
class Version private constructor(private val parts:List<Int>) : Comparable<Version> {

    constructor(text:String):this(text.split('.').map { cell ->
        cell.takeWhile { it in '0'..'9' }.toIntOrNull() ?: 0
    })

    override fun compareTo(other: Version): Int {
        for (cell in 0 until maxOf(parts.size, other.parts.size)) {
            val my = if (cell in parts.indices) parts[cell] else 0
            val others = if (cell in other.parts.indices) other.parts[cell] else 0
            val comparison = my.compareTo(others)
            if (comparison != 0) {
                return comparison
            }
        }
        return 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Version

        if (parts != other.parts) return false

        return true
    }

    override fun hashCode(): Int {
        return parts.hashCode()
    }

    override fun toString(): String {
        return parts.toString()
    }

    companion object {
        val NONE = Version(emptyList())

        val WEMI_0_11 = Version("0.11")
    }
}