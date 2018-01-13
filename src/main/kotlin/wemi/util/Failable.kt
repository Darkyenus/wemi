package wemi.util

@Suppress("unused")
/**
 * Represents a value that may have failed to be computed.
 * Similar to Either in other languages.
 */
class Failable<out Success, out Failure>
private constructor(val successful: Boolean, val value: Success?, val failure: Failure?) {

    inline fun success(action: (Success) -> Unit) {
        if (successful) {
            @Suppress("UNCHECKED_CAST")
            action(value as Success)
        }
    }

    inline fun failure(action: (Failure) -> Unit) {
        if (!successful) {
            @Suppress("UNCHECKED_CAST")
            action(failure as Failure)
        }
    }

    inline fun <Result> use(successAction: (Success) -> Result, failureAction: (Failure) -> Result): Result {
        return if (successful) {
            @Suppress("UNCHECKED_CAST")
            successAction(value as Success)
        } else {
            @Suppress("UNCHECKED_CAST")
            failureAction(failure as Failure)
        }
    }

    companion object {
        fun <Success, Failure> success(success: Success): Failable<Success, Failure> {
            return Failable(true, success, null)
        }

        fun <Success, Failure> failure(failure: Failure): Failable<Success, Failure> {
            return Failable(false, null, failure)
        }

        fun <Success, Failure> failNull(success: Success?, failure: Failure): Failable<Success, Failure> {
            return if (success == null) {
                failure(failure)
            } else {
                success(success)
            }
        }
    }
}