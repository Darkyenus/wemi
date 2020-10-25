package wemi.util

import java.lang.NumberFormatException

/**
 * Wraps version string and provides comparison capability over it.
 * Newer versions are larger.
 *
 * Version string is divided into parts (separated by dots, hyphens or underscores).
 * Each part has its numeric value taken (from any leading digits), otherwise it is sorted lexicographically.
 * Textual parts are always treated as older than numerical.
 * When comparing two versions, missing parts are treated as numerical 0.
 */
class Version(val version:String) : Comparable<Version> {

	private val text:Array<String?>
	private val numbers:IntArray

	init {
		val parts = version.split('.', '_', '-')
		text = arrayOfNulls(parts.size)
		numbers = IntArray(parts.size)
		for ((i, part) in parts.withIndex()) {
			try {
				numbers[i] = part.toInt()
			} catch (e:NumberFormatException) {
				text[i] = part
			}
		}
	}

	override fun compareTo(other: Version): Int {
		for (cell in 0 until maxOf(numbers.size, other.numbers.size)) {
			val myText = text.getOrNull(cell)
			val otherText = other.text.getOrNull(cell)
			if (myText != null && otherText != null) {
				val cmp = myText.compareTo(otherText)
				if (cmp != 0) {
					return cmp
				}
				continue
			}
			if (myText != null) {
				// My version has some text, therefore it is older / smaller
				return -1
			}
			if (otherText != null) {
				// Other version has some text, therefore it is older / smaller
				return 1
			}

			val my = if (cell in numbers.indices) numbers[cell] else 0
			val others = if (cell in other.numbers.indices) other.numbers[cell] else 0

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
		return numbers.contentEquals(other.numbers) && text.contentEquals(other.text)
	}

	override fun hashCode(): Int {
		return numbers.hashCode() * 31 + text.hashCode()
	}

	override fun toString(): String = version
}