package wemi.boot

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
         * When first String (key) is null, represents a free input argument.
         */
        val input: List<Pair<String?, String>>,
        /**
         * Internal flags.
         *  @see FLAG_MACHINE_READABLE_COMMAND
         *  @see FLAG_MACHINE_READABLE_OPTIONAL
         */
        internal val flags:Int = 0) {

    val isCommand:Boolean
        get() = project == null
                && configurations.isEmpty()
                && (flags and FLAG_MACHINE_READABLE_COMMAND) == FLAG_MACHINE_READABLE_COMMAND

    override fun toString(): String {
        val sb = StringBuilder()
        if (project != null) {
            sb.append(project).append(PROJECT_SEPARATOR)
        }
        for (configuration in configurations) {
            sb.append(configuration).append(CONFIGURATION_SEPARATOR)
        }
        sb.append(key)

        for ((k, v) in input) {
            sb.append(' ')
            if (k != null) {
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
        if (input != other.input) return false

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
        val FLAG_MACHINE_READABLE_COMMAND = 1 shl 0
        /**
         * Task is optional (ends with ?)
         */
        val FLAG_MACHINE_READABLE_OPTIONAL = 1 shl 1
    }
}