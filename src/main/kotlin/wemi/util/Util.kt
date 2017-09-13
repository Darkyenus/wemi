package wemi.util

import java.io.File

/**
 *
 */
fun <V> Map<String, V>.findCaseInsensitive(key:String):V? {
    synchronized(this) {
        return getOrElse(key) {
            for ((k, v) in this) {
                if (key.equals(k, ignoreCase = true)) {
                    return v
                }
            }
            return null
        }
    }
}

fun formatTimeDuration(ms:Long):CharSequence {
    val Second = 1000
    val Minute = Second * 60
    val Hour = Minute * 60
    val Day = Hour * 24

    val result = StringBuilder()
    var remaining = ms

    val days = remaining / Day
    remaining %= Day
    val hours = remaining / Hour
    remaining %= Hour
    val minutes = remaining / Minute
    remaining %= Minute
    val seconds = remaining / Second
    remaining %= Second

    if (days == 1L) {
        result.append("1 day ")
    } else if (days > 1L) {
        result.append(days).append(" days ")
    }

    if (hours == 1L) {
        result.append("1 hour ")
    } else if (hours > 1L) {
        result.append(hours).append(" hours ")
    }

    if (minutes == 1L) {
        result.append("1 minute ")
    } else if (minutes > 1L) {
        result.append(minutes).append(" minutes ")
    }

    if (seconds == 1L) {
        result.append("1 second ")
    } else if (seconds > 1L) {
        result.append(seconds).append(" seconds ")
    }

    result.append(remaining).append(" ms")
    return result
}

fun File.hasExtension(extensions:Collection<String>):Boolean {
    val name = this.name
    val length = name.length
    for (extension in extensions) {
        if (length >= extension.length + 1
                && name.endsWith(extension, ignoreCase = true)
                && name[length - extension.length - 1] == '.') {
            return true
        }
    }
    return false
}
