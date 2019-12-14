package com.darkyen.wemi.intellij.util

@Suppress("unused")
/**
 * Represents a value that may have failed to be computed.
 * Similar to Either in other languages.
 *
 * @param successful if [value] contains the successful result, otherwise [failure] contains reason why not
 */
class Failable<out Success, Failure>
private constructor(val successful: Boolean, val value: Success?, val failure: Failure?) {

    /** Run [action] if this [Failable] has been successful. */
    inline fun success(action: (Success) -> Unit) {
        if (successful) {
            @Suppress("UNCHECKED_CAST")
            action(value as Success)
        }
    }

    /** Run [action] if this [Failable] has not been successful. */
    inline fun failure(action: (Failure) -> Unit) {
        if (!successful) {
            @Suppress("UNCHECKED_CAST")
            action(failure as Failure)
        }
    }

    /** Run [successAction] if this [Failable] has been successful and [failureAction] if not. */
    inline fun <Result> use(successAction: (Success) -> Result, failureAction: (Failure) -> Result): Result {
        return if (successful) {
            @Suppress("UNCHECKED_CAST")
            successAction(value as Success)
        } else {
            @Suppress("UNCHECKED_CAST")
            failureAction(failure as Failure)
        }
    }

    inline fun <Result> map(operation: (Success) -> Result):Failable<Result, Failure> {
        if (successful) {
            @Suppress("UNCHECKED_CAST")
            return Failable.success(operation(value as Success))
        } else {
            return this.reFail()
        }
    }

    inline fun <Result> fold(operation: (Success) -> Failable<Result, Failure>):Failable<Result, Failure> {
        if (successful) {
            @Suppress("UNCHECKED_CAST")
            return operation(value as Success)
        } else {
            return this.reFail()
        }
    }

    /** Re-cast this as a [Failable] with different [Success] type. */
    fun <NewSuccess> reFail():Failable<NewSuccess, Failure> {
        @Suppress("UNCHECKED_CAST")
        return this as Failable<NewSuccess, Failure>
    }

    companion object {
        /** Create successful [Failable] with [success] as its result. */
        fun <Success, Failure> success(success: Success): Failable<Success, Failure> {
            return Failable(true, success, null)
        }

        /** Create failed [Failable] with [failure] as its result. */
        fun <Success, Failure> failure(failure: Failure): Failable<Success, Failure> {
            return Failable(false, null, failure)
        }

        /**
         * Create [Failable] that is successful if [success] is not null.
         * Otherwise use [failure] to create failed failable.
         */
        fun <Success, Failure> failNull(success: Success?, failure: Failure): Failable<Success, Failure> {
            return if (success == null) {
                Failable(false, null, failure)
            } else {
                Failable(true, success, null)
            }
        }
    }
}