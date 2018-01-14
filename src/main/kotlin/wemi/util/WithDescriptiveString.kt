package wemi.util

/**
 * Declares that this object has a different [Object.toString] implementation,
 * that is more suited for user and may contain ANSI terminal colors.
 */
interface WithDescriptiveString {
    /**
     * Return the human readable [String] representation of this object.
     * May contain ANSI colors.
     *
     * @see wemi.boot.CLI.format
     */
    fun toDescriptiveAnsiString(): String
}