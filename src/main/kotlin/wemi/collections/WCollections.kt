@file:Suppress("UNCHECKED_CAST", "unused")

package wemi.collections

/**
 * Base interface for Wemi collections.
 *
 * Those collections handle mutability deterministically, see [toMutable].
 */
interface WCollection<T>:Collection<T> {

    /**
     * Return new mutable collection if this one is immutable, but always return self if mutable.
     */
    fun toMutable(): WMutableCollection<T>
}

/**
 * Ordered set specialization of [WCollection].
 */
interface WSet<T>: WCollection<T>, Set<T> {
    override fun toMutable(): WMutableSet<T>
}

/**
 * List specialization of [WCollection]
 */
interface WList<T>: WCollection<T>, List<T> {
    override fun toMutable(): WMutableList<T>
}

/**
 * Mutable counterpart to [WCollection]
 */
interface WMutableCollection<T>: WCollection<T>, MutableCollection<T>

/**
 * Ordered set that can be freely modified
 */
class WMutableSet<T>:LinkedHashSet<T>, WMutableCollection<T>, WSet<T> {

    constructor(initialCapacity: Int, loadFactor: Float) : super(initialCapacity, loadFactor)
    constructor(initialCapacity: Int) : super(initialCapacity)
    constructor() : super()
    constructor(c: Collection<T>?) : super(c)

    private var mutable:Boolean = true

    internal constructor(initialCapacity: Int, mutable: Boolean) : super(initialCapacity) {
        this.mutable = mutable
    }

    override fun toMutable(): WMutableSet<T> {
        if (mutable) {
            return this
        } else {
            return WMutableSet(this)
        }
    }
}

/**
 * List that can be freely modified
 */
class WMutableList<T>:ArrayList<T>, WMutableCollection<T>, WList<T> {

    constructor(initialCapacity: Int) : super(initialCapacity)
    constructor() : super()
    constructor(c: Collection<T>?) : super(c)

    private var mutable:Boolean = true

    internal constructor(initialCapacity: Int, mutable:Boolean) : super(initialCapacity) {
        this.mutable = mutable
    }

    override fun toMutable(): WMutableList<T> {
        if (mutable) {
            return this
        } else {
            return WMutableList(this)
        }
    }
}

private object WEmptySet:Set<Nothing> by emptySet(), WSet<Nothing> {
    override fun toMutable(): WMutableSet<Nothing> = WMutableSet()
}

private object WEmptyList:List<Nothing> by emptyList(), WList<Nothing> {
    override fun toMutable(): WMutableList<Nothing> = WMutableList()
}

/**
 * Create an empty [WSet]. Guaranteed to be immutable!
 */
fun <T> wEmptySet(): WSet<T> = WEmptySet as WSet<T>

/**
 * Create an empty [WList]. Guaranteed to be immutable!
 */
fun <T> wEmptyList(): WList<T> = WEmptyList as WList<T>

/**
 * Create an [WSet] with [items].
 * Guaranteed to be immutable in a way that [WCollection.toMutable] will return a new collection.
 */
fun <T> wSetOf(vararg items:T): WSet<T> {
    return if (items.isEmpty()) {
        WEmptySet as WSet<T>
    } else {
        WMutableSet<T>((items.size / 0.7f).toInt(), false).apply {
            for (item in items) {
                add(item)
            }
        }
    }
}

/**
 * Create an [WSet] with [items].
 * Guaranteed to be immutable in a way that [WCollection.toMutable] will return a new collection.
 */
fun <T> wMutableSetOf(vararg items:T): WSet<T> {
    return if (items.isEmpty()) {
        WEmptySet as WSet<T>
    } else {
        WMutableSet<T>((items.size / 0.7f).toInt()).apply {
            for (item in items) {
                add(item)
            }
        }
    }
}

/**
 * Create an [WList] with [items].
 * Guaranteed to be immutable in a way that [WCollection.toMutable] will return a new collection.
 */
fun <T> wListOf(vararg items:T): WList<T> {
    return if (items.isEmpty()) {
        WEmptyList as WList<T>
    } else {
        WMutableList<T>(items.size, false).apply {
            for (item in items) {
                add(item)
            }
        }
    }
}

/**
 * Create an [WList] with [items].
 */
fun <T> wMutableListOf(vararg items:T): WList<T> {
    return if (items.isEmpty()) {
        WEmptyList as WList<T>
    } else {
        WMutableList<T>(items.size).apply {
            for (item in items) {
                add(item)
            }
        }
    }
}

/**
 * Convert this collection to [WSet].
 * Return this if already [WSet].
 */
fun <T> Collection<T>.toWSet(): WSet<T> {
    if (this is WSet) {
        return this
    } else {
        return WMutableSet(this)
    }
}

/**
 * Convert this collection to [WList].
 * Return this if already [WList].
 */
fun <T> Collection<T>.toWList(): WList<T> {
    if (this is WList) {
        return this
    } else {
        return WMutableList(this)
    }
}
