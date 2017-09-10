package wemi.assembly

/**
 *
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
    /** If more than one copy, discard all of them */
    Discard,
    /** Only one value is expected, take the copy only if all other copies hold equal data */
    Deduplicate,
    /** Move the file to different path, specified by [wemi.Keys.assemblyRenameFunction] */
    Rename
}