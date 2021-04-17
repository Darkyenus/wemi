package wemi.dependency.internal

/**
 * Range of two versions as used in Maven.
 */
class MavenVersionRange(private val min:MavenVersion?, private val minInclusive:Boolean, private val max:MavenVersion?, private val maxInclusive:Boolean) {

	operator fun contains(version:MavenVersion):Boolean {
		if (min != null) {
			if (minInclusive) {
				if (version < min) {
					return false
				}
			} else {
				if (version <= min) {
					return false
				}
			}
		}
		if (max != null) {
			if (maxInclusive) {
				if (version > max) {
					return false
				}
			} else {
				if (version >= max) {
					return false
				}
			}
		}
		return true
	}
}

operator fun List<MavenVersionRange>.contains(version:String):Boolean {
	val v = parseMavenVersion(version)
	return any { v in it }
}

class MavenVersion(val numeric:IntArray, val qualifier:String):Comparable<MavenVersion> {
	override fun compareTo(other: MavenVersion): Int {
		// https://cwiki.apache.org/confluence/display/MAVENOLD/Dependency+Mediation+and+Conflict+Resolution#DependencyMediationandConflictResolution-DependencyVersionRanges
		for (i in 0 until maxOf(numeric.size, other.numeric.size)) {
			val thisN = if (i < numeric.size) numeric[i] else 0
			val otherN = if (i < other.numeric.size) other.numeric[i] else 0
			val cmp = thisN.compareTo(otherN)
			if (cmp != 0) {
				return cmp
			}
		}

		val thisQualifierAsBuildNumber = qualifier.toIntOrNull()
		val otherQualifierAsBuildNumber = other.qualifier.toIntOrNull()

		val thisHasQualifier = qualifier.isNotEmpty() && thisQualifierAsBuildNumber == null
		val otherHasQualifier = other.qualifier.isNotEmpty() && otherQualifierAsBuildNumber == null
		if (!thisHasQualifier && otherHasQualifier) {
			return 1
		} else if (thisHasQualifier && !otherHasQualifier) {
			return -1
		} else if (thisHasQualifier && otherHasQualifier) {
			return qualifier.compareTo(other.qualifier)
		}

		return (thisQualifierAsBuildNumber ?: 0).compareTo(otherQualifierAsBuildNumber ?: 0)
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is MavenVersion) return false

		return compareTo(other) == 0
	}

	override fun hashCode(): Int {
		var result = numeric.contentHashCode()
		result = 31 * result + qualifier.hashCode()
		return result
	}
}

fun parseMavenVersion(version:String):MavenVersion {
	var numeric = IntArray(3)

	// Read in numbers
	var start = 0
	for (n in 0 until Int.MAX_VALUE) {
		var numberEnd = start
		while (numberEnd < version.length && version[numberEnd] in '0'..'9') {
			numberEnd++
		}
		if (start == numberEnd) {
			break
		}
		if (n >= numeric.size) {
			numeric = numeric.copyOf(n + 1)
		}
		numeric[n] = version.substring(start, numberEnd).toIntOrNull() ?: Int.MAX_VALUE // Prevent overflow
		if (numberEnd < version.length && version[numberEnd] == '.') {
			// Got more numbers
			start = numberEnd + 1
		} else {
			// A different part now
			start = numberEnd
			break
		}
	}

	if (start < version.length && version[start] == '-') {
		start++
	}

	val qualifier = version.substring(start)
	return MavenVersion(numeric, qualifier)
}

fun parseMavenVersionRange(range:String):List<MavenVersionRange> {
	val result = ArrayList<MavenVersionRange>()

	var versionRangeStart = 0
	// Skip whitespace
	while (versionRangeStart < range.length && range[versionRangeStart].isWhitespace()) {
		versionRangeStart++
	}

	while (versionRangeStart < range.length) {
		val opener = range[versionRangeStart]
		val implicitRange = opener != '[' && opener != '('
		if (implicitRange) {
			var end = range.indexOf(',', startIndex = versionRangeStart)
			if (end == -1) {
				end = range.length
			}
			if (end == versionRangeStart) {
				// Empty range, skip
				continue
			}
			val implicitRangeMin = parseMavenVersion(range.substring(versionRangeStart, end).trim())
			result.add(MavenVersionRange(implicitRangeMin, true, null, false))
			versionRangeStart = end + 1
			continue
		}

		versionRangeStart++
		if (versionRangeStart >= range.length) {
			// Dangling opener
			break
		}

		// Looking for a comma or a closer
		var end = versionRangeStart
		var firstRangeVersion:MavenVersion? = null
		var hasFirstRangeVersion = false
		var lastRangeVersion:MavenVersion? = null

		var closer = ']'

		versionRange@while (end < range.length) {
			val charI = end
			val char = range[charI]
			end++
			when (char) {
				',' -> {
					val firstRangeVersionStr = range.substring(versionRangeStart, charI).trim()
					if (firstRangeVersionStr.isNotEmpty()) {
						firstRangeVersion = parseMavenVersion(firstRangeVersionStr)
					}
					hasFirstRangeVersion = true
					versionRangeStart = end
				}
				']', ')' -> {
					val lastRangeVersionStr = range.substring(versionRangeStart, charI).trim()
					if (lastRangeVersionStr.isNotEmpty()) {
						lastRangeVersion = parseMavenVersion(lastRangeVersionStr)
					}
					closer = char
					break@versionRange
				}
			}
		}

		versionRangeStart = end

		if (firstRangeVersion != null || lastRangeVersion != null) {
			// Complete range
			val minInclusive = opener == '['
			val maxInclusive = closer == ']'
			val min: MavenVersion?
			val max: MavenVersion?

			if (hasFirstRangeVersion) {
				min = firstRangeVersion
				max = lastRangeVersion
			} else {
				min = lastRangeVersion
				max = lastRangeVersion
			}

			// This range does not make any sense as it will not contain anything, skip it
			@Suppress("RedundantIf")
			val valid = if (min == max && (!minInclusive || !maxInclusive)) {
				false
			} else if (min != null && max != null && min > max) {
				false
			} else true

			if (valid) {
				result.add(MavenVersionRange(min, minInclusive, max, maxInclusive))
			}
		}

		// Skip whitespace
		while (versionRangeStart < range.length && range[versionRangeStart].isWhitespace()) {
			versionRangeStart++
		}
		// Skip comma
		if (versionRangeStart < range.length && range[versionRangeStart] == ',') {
			versionRangeStart++
		}
		// Skip whitespace
		while (versionRangeStart < range.length && range[versionRangeStart].isWhitespace()) {
			versionRangeStart++
		}
	}

	return result
}


/** Take a Maven-style version range and extract a single explicit version from it.
 * This parsing is very strict to the exact form of the version range, while very lenient to the actual versions. */
internal fun extractSingleVersionFromVersionRange(version:String):String? {
	// https://docs.oracle.com/middleware/1212/core/MAVEN/maven_version.htm#MAVEN402
	if (!version.startsWith('[') && !version.startsWith('(')) {
		// Simple version with no range weirdness, fast path
		// Also catches the case when the version is empty
		return null
	}

	val length = version.length
	var i = 0
	val rangeGroups = ArrayList<String>()

	while (true) {
		// Asserts that there is at least one character
		val groupStart = i
		if (version[groupStart] != '[' && version[groupStart] != '(') {
			// Malformed group start
			return null
		}
		i++

		run {
			while (i < length) {
				val c = version[i++]
				if (c == ']' || c == ')') {
					return@run
				}
			}
			// Missing group end
			return null
		}
		rangeGroups.add(version.substring(groupStart, i))
		// This is either the last group, or there is a comma and another group starts
		if (i < length) {
			if (version[i] != ',' || length - i - 1 < 3) {
				// No comma or comma and not enough space for more
				return null
			}
			i++ // Skip comma
		} else break
	}

	// Now that we have a collection of version ranges, we have to find the best concrete one
	for (rangeI in rangeGroups.indices.reversed()) {
		val range = rangeGroups[rangeI]
		assert(range.startsWith('[') || range.startsWith('(')) { "$range in $version" }
		assert(range.endsWith(']') || range.endsWith(')')) { "$range in $version" }
		val rangeLength = range.length
		if (rangeLength < 3) {
			// Invalid range, it does not contain a valid version
			return null
		}
		val closedLeft = range.startsWith('[')
		val closedRight = range.endsWith(']')
		val commaI = range.indexOf(',')
		if (commaI == -1) {
			// This is a single number
			if (closedLeft && closedRight) {
				// This is a good exact version!
				return range.substring(1, rangeLength - 1)
			}
			// Single version range can be only all closed, this is malformed
			return null
		}
		// We have a comma
		if (closedRight) {
			// Right version can be used (it is the highest allowed)
			val right = range.substring(commaI + 1, rangeLength - 1)
			if (right.isEmpty()) {
				// This is malformed and cruel
				return null
			}
			return right
		} else if (closedLeft) {
			// Left version can be used
			val left = range.substring(1, commaI)
			if (left.isEmpty()) {
				return null
			}
			return left
		}
	}

	// Could not find a good version
	return null
}