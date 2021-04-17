package wemi.boot

import wemi.Command
import wemi.Key
import wemi.WemiException
import wemi.boot.Task.Companion.FLAG_MACHINE_READABLE_COMMAND
import wemi.boot.Task.Companion.FLAG_MACHINE_READABLE_OPTIONAL
import wemi.boot.TaskParser.CONFIGURATION_SEPARATOR
import wemi.boot.TaskParser.INPUT_SEPARATOR
import wemi.boot.TaskParser.PROJECT_SEPARATOR

/**
 * Single key or command evaluation settings, unresolved.
 *
 * May be a command, but only if [project] is null and [configurations] are empty.
 */
class Task(
        /**
         * [wemi.Project] name to use for key evaluation.
         * Null when command or default project.
         */
        val project: String?,
        /**
         * [wemi.Configuration] names to use, in given order, when evaluating.
         * Must be empty when command.
         */
        val configurations: List<String>,
        /**
         * [wemi.Key] or command name
         */
        val key: String,
        /**
         * Input pairs to use, represents key-value relationship.
         * When first String (key) is empty, represents a free input argument.
         */
        val input: Array<Pair<String, String>>,
        /**
         * Internal flags.
         *  @see FLAG_MACHINE_READABLE_COMMAND
         *  @see FLAG_MACHINE_READABLE_OPTIONAL
         */
        internal val flags: Int = 0) {

    internal val isMachineReadableCommand: Boolean
        get() = couldBeInternalCommand
                && (flags and FLAG_MACHINE_READABLE_COMMAND) != 0

    internal val isMachineReadableOptional: Boolean
        get() = (flags and FLAG_MACHINE_READABLE_OPTIONAL) != 0

    internal val couldBeInternalCommand: Boolean
        get() = project == null
                && configurations.isEmpty()

    /**
     * Returns first [input] that has given [name] or is free if [orFree].
     */
    internal fun firstInput(name:String, orFree:Boolean):String? {
        return input.find { (it.first.isEmpty() && orFree) || it.first == name }?.second
    }

    /**
     * Returns all [input]s that have given [name] or are free
     */
    internal fun inputs(name:String):List<String> {
        return input.mapNotNull { (n, i) ->
            if (n.isEmpty() || n == name) {
                i
            } else null
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        if (project != null) {
            sb.append(project).append(PROJECT_SEPARATOR)
        }
        for (configuration in configurations) {
            sb.append(configuration).append(CONFIGURATION_SEPARATOR)
        }
        if (isMachineReadableCommand) {
            sb.append('#')
        }
        sb.append(key)
        if (isMachineReadableOptional) {
            sb.append('?')
        }

        for ((k, v) in input) {
            sb.append(' ')
            if (k.isNotEmpty()) {
                sb.append(k).append(INPUT_SEPARATOR)
            }
            sb.append(v)
        }

        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Task

        if (project != other.project) return false
        if (configurations != other.configurations) return false
        if (key != other.key) return false
        if (!input.contentEquals(other.input)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = project?.hashCode() ?: 0
        result = 31 * result + configurations.hashCode()
        result = 31 * result + key.hashCode()
        result = 31 * result + input.hashCode()
        return result
    }

    companion object {
        /**
         * Task is a command (starts with #)
         */
        const val FLAG_MACHINE_READABLE_COMMAND = 1 shl 0
        /**
         * Task is optional (ends with ?)
         */
        const val FLAG_MACHINE_READABLE_OPTIONAL = 1 shl 1
    }
}

/**
 * Status of attempt to evaluate task.
 *
 * @see TaskEvaluationResult
 */
enum class TaskEvaluationStatus {
    /** Data contains the evaluation result */
    Success,
    /** Data contains invalid name of project or null if tried to use default project but that is undefined */
    NoProject,
    /** Data contains invalid name of configuration */
    NoConfiguration,
    /** Data contains invalid name of key */
    NoKey,
    /** Data contains [WemiException.KeyNotAssignedException] */
    NotAssigned,
    /** Data contains [WemiException] */
    Exception,
    /** Task was command, data is null */
    Command
}

/**
 * Result of task evaluation.
 *
 * @param key that was evaluated, if known (will be null of [command] is not null)
 * @param command that was evaluated, if known (will be null of [key] is not null)
 * @param data [status]-dependent data, see documentation of [TaskEvaluationStatus]
 * @param status of the evaluation
 */
data class TaskEvaluationResult constructor(val key: Key<*>?, val command: Command<*>?, val data: Any?, val status: TaskEvaluationStatus) {
    constructor(data: Any?, status: TaskEvaluationStatus): this(null, null, data, status)
}
