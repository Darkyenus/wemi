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
    if (this is WMutableSet) {
        return this
    } else if (this is WMutableList) {
        return this
    } else if (this is Set) {
        return WMutableSet(this)
    } else {
        return WMutableList(this)
    }
}

/**
 * @see toMutable
 */
fun <Element> Set<Element>.toMutable(): WMutableSet<Element> {
    if (this is WMutableSet) {
        return this
    } else {
        return WMutableSet(this)
    }
}

/**
 * @see toMutable
 */
fun <Element> List<Element>.toMutable(): WMutableList<Element> {
    if (this is WMutableList) {
        return this
    } else {
        return WMutableList(this)
    }
}