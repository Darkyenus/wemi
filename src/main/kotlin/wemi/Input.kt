package wemi

import org.jline.reader.EndOfFileException
import org.jline.reader.UserInterruptException
import org.slf4j.LoggerFactory
import wemi.boot.CLI
import wemi.boot.WemiBuildFolder
import wemi.boot.WemiRunningInInteractiveMode
import wemi.util.*
import wemi.util.CliStatusDisplay.Companion.withStatus
import java.nio.file.NoSuchFileException
import java.util.*
import java.util.regex.Pattern

private val LOG = LoggerFactory.getLogger("Input")

@PublishedApi
internal val NO_INPUT = emptyArray<Pair<String,String>>()

/**
 * Read a [V] from the input.
 * The value is first searched for using the [key] from explicit input pairs.
 * Then, free input strings (without explicit [key]s) are considered. Both are considered from top
 * (added last) to bottom, and only if they are accepted by the [validator].
 * As a last resort, if interactive, user is asked. Otherwise, the query fails.
 *
 * @param key simple, non-user-readable key (case-insensitive, converted to lowercase)
 * @param description displayed to the user, if asked interactively
 * @param validator to use for validation and conversion of found string
 * @param ask if value is not already specified, ask the user
 * @return found value or null if validator fails on all possible values
 */
fun <V> CommandBindingHolder.read(key: String, description: String, validator: Validator<V>, ask:Boolean = true): V? {
    val input = this.input

    // Search in prepared by key
    for ((preparedKey, preparedValue) in input) {
        if (!preparedKey.equals(key, ignoreCase = true)) {
            continue
        }

        validator(preparedValue).use<Unit>({
            SimpleHistory.getHistory(SimpleHistory.inputHistoryName(key)).add(preparedValue)
            return it
        }, {
            LOG.info("Can't use '{}' for input key '{}': {}", preparedValue, key, it)
        })
    }

    // Search in prepared for free
    // Move nextFreeInput to a valid index of free input
    while (nextFreeInput < input.size && input[nextFreeInput].first.isNotEmpty()) {
        nextFreeInput++
    }
    // Try to use it
    if (nextFreeInput < input.size) {
        val freeInput = input[nextFreeInput].second
        validator(freeInput).use({
            // We will use this free input
            SimpleHistory.getHistory(SimpleHistory.inputHistoryName(key)).add(freeInput)
            SimpleHistory.getHistory(SimpleHistory.inputHistoryName(null)).add(freeInput)
            nextFreeInput++
            return it
        }, {
            LOG.info("Can't use free '{}' for input key '{}': {}", freeInput, key, it)
        })
    }

    if (!ask) {
        return null
    }

    // Still no hit, read interactively
    if (!WemiRunningInInteractiveMode) {
        LOG.info("Not asking for {} - '{}', not interactive", key, description)
        return null
    }

    try {
        while (true) {
            val line = CLI.InputLineReader.run {
                val previousHistory = history
                try {
                    history = SimpleHistory.getHistory(SimpleHistory.inputHistoryName(key))
                    CLI.MessageDisplay.withStatus(false) {
                        readLine("${format(description, format = Format.Bold)} (${format(key, Color.White)}): ")
                    }
                } finally {
                    history = previousHistory
                }
            }
            val value = validator(line)
            value.use({
                return it
            }, {
                print(format("Invalid input: ", format = Format.Bold))
                println(format(it, foreground = Color.Red))
            })
        }
    } catch (e: UserInterruptException) {
        return null
    } catch (e: EndOfFileException) {
        return null
    }
}

private val SECRET_KEY_VALUE_REGEX = Pattern.compile("^([a-zA-Z0-9-_.]+)[\\s]*:[\\s]*(.*?)[\\s]*\$")

/**
 * Read a secret key. This is different from normal [read] in several ways:
 * 1. Read token is not saved to autocomplete history and there is no autocomplete
 * 2. Before asking, secret is first searched for in a "build/wemi-secrets.txt" file, which is an UTF-8 encoded file,
 *      where each line that matches regex ^([a-zA-Z0-9-_.]+)[\s]*:[\s]*(.*?)[\s]*$ is considered a key-value pair.
 *      For example, one such line may look like this:
 *      my-secret: 1234567890
 *      If you do have such file, remember to not push it to version control system!
 *      Keys taken from file are not case sensitive.
 */
fun <V> CommandBindingHolder.readSecret(key:String, description:String, validator:Validator<V>):V? {
    val input = this.input

    // Search in prepared by key
    for ((preparedKey, preparedValue) in input) {
        if (!preparedKey.equals(key, ignoreCase = true)) {
            continue
        }

        validator(preparedValue).use<Unit>({
            return it
        }, {
            LOG.info("Can't use specified value for input key '{}': {}", key, it.replace(preparedValue, "<secret>"))
        })
    }

    // Search in prepared for free
    // Move nextFreeInput to a valid index of free input
    while (nextFreeInput < input.size && input[nextFreeInput].first.isNotEmpty()) {
        nextFreeInput++
    }
    // Try to use it
    if (nextFreeInput < input.size) {
        val freeInput = input[nextFreeInput].second
        validator(freeInput).use({
            // We will use this free input
            nextFreeInput++
            return it
        }, {
            LOG.info("Can't use specified free input value for input key '{}': {}", key, it.replace(freeInput, "<secret>"))
        })
    }

    // Read file
    val secretsFile = WemiBuildFolder / "wemi-secrets.txt"
    val secretFromFile = try {
        Files.lines(secretsFile).map { line ->
            val matcher = SECRET_KEY_VALUE_REGEX.matcher(line)
            if (matcher.matches() && matcher.group(1).equals(key, ignoreCase = true)) {
                matcher.group(2)
            } else null
        }.filter { it != null }.findFirst().orElse(null)
    } catch (e:NoSuchFileException) {
        LOG.info("Not reading secret {} from {} - file does not exist")
        null
    } catch (e:Exception) {
        LOG.warn("Failed to read {} to obtain {}", secretsFile, key, e)
        null
    }
    if (secretFromFile != null) {
        validator(secretFromFile).use({ success ->
            return success
        }, {
            LOG.warn("Can't use value of secret key {} from {}, because the value does not pass validation: {}", key, secretsFile, it.replace(secretFromFile, "<secret>"))
        })
    }

    // Still no hit, read interactively
    if (!WemiRunningInInteractiveMode) {
        LOG.info("Not asking for secret {} - '{}', not interactive", key, description)
        return null
    }

    try {
        while (true) {
            val line = CLI.InputLineReader.run {
                val previousHistory = history
                try {
                    history = SimpleHistory(null)
                    CLI.MessageDisplay.withStatus(false) {
                        readLine("${format(description, format = Format.Bold)} (${format(key, Color.White)}): ")
                    }
                } finally {
                    history = previousHistory
                }
            }
            val value = validator(line)
            value.use({
                return it
            }, {
                print(format("Invalid input: ", format = Format.Bold))
                println(format(it, foreground = Color.Red))
            })
        }
    } catch (e: UserInterruptException) {
        return null
    } catch (e: EndOfFileException) {
        return null
    }
}


/**
 * A function that validates given string input and converts it to the desirable type.
 * Should be pure. Returns [Failable], with the validated/converted value or with an error string,
 * that will be printed for the user verbatim.
 */
typealias Validator<V> = (String) -> Failable<V, String>

/**
 * Default validator, always succeeds and returns the entered string, no matter what it is.
 */
val StringValidator: Validator<String> = { Failable.success(it) }

/** Integer validator, accepts integer numbers in decimal system and MIN/MAX for Int limits  */
@Suppress("unused")
val IntValidator: Validator<Int> = {
    val trimmed = it.trim()
    if (trimmed.equals("min", ignoreCase=true)) {
        Failable.success(Int.MIN_VALUE)
    } else if (trimmed.equals("max", ignoreCase=true)) {
        Failable.success(Int.MAX_VALUE)
    } else {
        Failable.failNull(trimmed.toIntOrNull(), "Integer expected")
    }
}

/**
 * Boolean validator, treats true, yes, 1 and y as true, false, no, 0 and n as false.
 */
@Suppress("unused")
val BooleanValidator: Validator<Boolean> = {
    when (it.toLowerCase(Locale.ROOT).trim()) {
        "true", "yes", "1", "y", "on" ->
            Failable.success(true)
        "false", "no", "0", "n", "off" ->
            Failable.success(false)
        else ->
            Failable.failure("Boolean expected")
    }
}

/**
 * String validator, succeeds only if string is a valid class name with package.
 * (For example "wemi.Input")
 */
val ClassNameValidator: Validator<String> = validator@ {
    val className = it.trim()

    var firstLetter = true
    for (c in className) {
        if (firstLetter) {
            if (!c.isJavaIdentifierStart()) {
                return@validator Failable.failure("Invalid character '$c' - class name expected")
            }
            firstLetter = false
        } else {
            if (!c.isJavaIdentifierPart()) {
                if (c == '.') {
                    firstLetter = true
                } else {
                    return@validator Failable.failure("Invalid character '$c' - class name expected")
                }
            }
        }
    }
    if (firstLetter) {
        return@validator Failable.failure("Class name is incomplete")
    }

    Failable.success(className)
}
