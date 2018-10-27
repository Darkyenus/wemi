package wemi.collections

/**
 * Ordered set that can be freely modified
 */
class WMutableSet<T> : LinkedHashSet<T> {
    constructor(initialCapacity: Int, loadFactor: Float) : super(initialCapacity, loadFactor)
    constructor(initialCapacity: Int) : super(initialCapacity)
    constructor() : super()
    constructor(c: Collection<T>?) : super(c)
}

/**
 * List that can be freely modified
 */
class WMutableList<T> : ArrayList<T> {
    constructor(initialCapacity: Int) : super(initialCapacity)
    constructor() : super()
    constructor(c: Collection<T>?) : super(c)
}

/**
 * Convert collection to one of WMutable* collections, unless it already is, then it returns itself.
 */
fun <Element> Collection<Element>.toMutable(): MutableCollection<Element> {
    return when (this) {
        is WMutableSet -> this
        is WMutableList -> this
        is Set -> WMutableSet(this)
        else -> WMutableList(this)
    }
}

/**
 * @see toMutable
 */
fun <Element> Set<Element>.toMutable(): WMutableSet<Element> {
    return this as? WMutableSet ?: WMutableSet(this)
}

/**
 * @see toMutable
 */
fun <Element> List<Element>.toMutable(): WMutableList<Element> {
    return this as? WMutableList ?: WMutableList(this)
}