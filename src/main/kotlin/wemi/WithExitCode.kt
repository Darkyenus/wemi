package wemi

/**
 * [Key]s may return objects that implement this interface.
 * Under certain circumstances, Wemi can take its process' exit code from that returned object.
 *
 * For example, [wemi.keys.test] uses this to exit with non-zero exit code when tests fail.
 *
 * Conditions for the [processExitCode] to be used:
 * 1. Task must be run non-interactively, supplied as process argument
 * 2. Task (that is, its key evaluation) must be the last task in the passed task list
 * 3. Wemi must be running in standard, i.e. not machine readable mode, or in shell-format machine readable mode
 */
interface WithExitCode {
    /**
     * Return the exit code that the Wemi process should end with, if it would depend on this instance.
     *
     * As per convention, 0 exit code is reserved for success and non-zero for failure.
     * See [wemi.boot.EXIT_CODE_SUCCESS], [wemi.boot.EXIT_CODE_TASK_FAILURE] and other predefined exit codes.
     */
    fun processExitCode(): Int
}