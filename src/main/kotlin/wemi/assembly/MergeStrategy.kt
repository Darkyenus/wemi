package wemi.assembly

/**
 * Strategies to use when merging files during assembly.
 */
enum class MergeStrategy {
    /** Only the first copy is kept */
    First,
    /** Only the last copy is kept */
    Last,
    /** Only own copy (the from [wemi.Keys.internalClasspath]) is used, others are discarded, error if multiple own */
    SingleOwn,
    /** Only one copy is expected, error if multiple */
    SingleOrError,
    /** Concatenate all copies byte by byte */
    Concatenate,
    /** Line-aware [Concatenate]. Adds trailing newline, attempts to use present line endings, reverts to \n if ambiguous.
     * Data is assumed to be in UTF-8. */
    Lines,
    /** Like [Lines], but duplicate lines are dropped */
    UniqueLines,
    /** If more than one copy, discard all of them */
    Discard,
    /** Only one value is expected, take the copy only if all other copies hold equal data */
    Deduplicate,
    /** Move the file to different path, specified by [wemi.Keys.assemblyRenameFunction] */
    Rename
}