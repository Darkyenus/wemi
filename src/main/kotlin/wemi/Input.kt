package wemi

import org.jline.reader.EndOfFileException
import org.jline.reader.UserInterruptException
import wemi.boot.CLI
import wemi.util.Failable

/**
 * Base for input. Does not store any prepared values, but reads from the user, if interactive.
 * If not interactive, simply fails.
 */
internal class InputBase (private val interactive:Boolean) : Input() {

    override fun <Value> getPrepared(key: String, validator: Validator<Value>): Value? = null

    override fun <Value> read(key: String, description: String, validator: Validator<Value>): Value? {
        if (!interactive) {
            return null
        }
        try {
            while (true) {
                val line = CLI.LineReader.readLine(description)
                val value = validator(line)
                value.use ({
                    return it
                }, {
                    print("Invalid input: ")
                    println(it)
                })
            }
        } catch (e:UserInterruptException) {
            return null
        } catch (e:EndOfFileException) {
            return null
        }
    }

}

/**
 * Holds input values, for the [InputExtensionBinding]. Also holds which free input values were already consumed.
 *
 * @param freeInput array of input strings that can be used when no input pairs fit
 * @param inputPairs map of input strings to use preferably, key corresponds to the key parameter of [Input.read]
 */
private class InputExtension(val freeInput:Array<out String>?, val inputPairs:Map<String, String>?) {
    /**
     * Next free input to consume. If [freeInput] is null or [nextFreeInput] >= [freeInput].size,
     * there is no more free input.
     */
    var nextFreeInput = 0
}

/**
 * Binds mutable [InputExtension] into the [Input] stack.
 */
private class InputExtensionBinding(val previous:Input, val extension: InputExtension) : Input() {

    override fun <Value> getPrepared(key: String, validator: Validator<Value>): Value? {
        if (extension.inputPairs != null) {
            val preparedValue = extension.inputPairs[key]

            if (preparedValue != null) {
                validator(preparedValue).success {
                    return it
                }
            }
        }
        return previous.getPrepared(key, validator)
    }

    override fun <Value> read(key: String, description: String, validator: Validator<Value>): Value? {
        val prepared = getPrepared(key, validator)
        if (prepared != null) {
            return prepared
        }

        extension.apply {
            if (freeInput != null) {
                if (nextFreeInput < freeInput.size) {
                    validator(freeInput[nextFreeInput]).success {
                        // We will use this free input
                        nextFreeInput++
                        return it
                    }
                }
            }
        }

        return previous.read(key, description, validator)
    }
}

/**
 * A function that validates given string input and converts it to the desirable type.
 * Should be pure. Returns [Failable], with the validated/converted value or with an error string,
 * that will be printed for the user verbatim.
 */
typealias Validator<Value> = (String) -> Failable<Value, String>

/**
 * Default validator, always succeeds and returns the entered string, no matter what it is.
 */
val StringValidator:Validator<String> = { Failable.success(it) }
/**
 * Integer validator, accepts decimal numbers
 */
val IntValidator:Validator<Int> = { Failable.failNull(it.trim().toIntOrNull(), "Integer expected") }
/**
 * Boolean validator, treats true, yes, 1 and y as true, false, no, 0 and n as false.
 */
val BooleanValidator:Validator<Boolean> = {
    when (it.toLowerCase()) {
        "true", "yes", "1", "y" ->
            Failable.success(true)
        "false", "no", "0", "n" ->
            Failable.success(false)
        else ->
            Failable.failure("Boolean expected")
    }
}

/**
 * Input provider. Retrieved values may come from the user interactively, be entered as part of the key string,
 * or be specified programmatically, see [withInput].
 */
abstract class Input {
    /**
     * Get the key value from the stack if it has been specified explicitly and fits the validator.
     * Items on the top of the stack are considered before those on the bottom.
     */
    abstract internal fun <Value> getPrepared(key: String, validator: Validator<Value>):Value?

    /**
     * Convenience method, calls [read] with [StringValidator].
     */
    fun read(key:String, description:String):String? = read(key, description, StringValidator)

    /**
     * Read a [Value] from the input.
     * The value is first searched for using the [key] from explicit input pairs.
     * Then, free input strings (without explicit [key]s) are considered. Both are considered from top
     * (added last) to bottom, and only if they are accepted by the [validator].
     * As a last resort, user is asked, if in interactive mode. Otherwise, the query fails.
     *
     * @param key simple, preferably lowercase non-user-readable key.
     * @param description displayed to the user, if asked interactively
     * @param validator to use for validation and conversion of found string
     * @return found value or null if validator fails on all possible values
     */
    abstract fun <Value> read(key:String, description:String, validator: Validator<Value>):Value?
}

/**
 * Add given inputs pairs to the scope through an anonymous configuration and run the [action] with that
 * configuration in [Scope] stack.
 * @see [using]
 */
fun <Result> Scope.withInput(vararg inputPairs:Pair<String, String>, action:Scope.()->Result):Result {
    val inputExtension = InputExtension(null, inputPairs.toMap())
    return using(anonInitializer = {
            Keys.input.modify { old ->
                InputExtensionBinding(old, inputExtension)
            }
        }, parent = null, action = action)
}

/**
 * Add given free inputs to the scope through an anonymous configuration and run the [action] with that
 * configuration in [Scope] stack.
 *
 * Note that free inputs added last (through [withInput]) are considered first.
 * Inputs on n-th position in the [freeInput] array will be considered only after those on (n-1)-th position were used.
 *
 * @see [using]
 */
fun <Result> Scope.withInput(vararg freeInput:String, action:Scope.()->Result):Result {
    val inputExtension = InputExtension(freeInput, null)
    return using(anonInitializer = {
            Keys.input.modify { old ->
                InputExtensionBinding(old, inputExtension)
            }
        }, parent = null, action = action)
}

