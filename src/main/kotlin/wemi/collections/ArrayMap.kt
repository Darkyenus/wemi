package wemi.collections

import javax.naming.OperationNotSupportedException

/**
 * Extra-memory-lightweight implementation of [MutableMap] interface.
 * Has almost zero overhead, single allocation when first value is entered.
 *
 * All operations are O(n), NOT SUITABLE IF EXPECTED AMOUNT OF VALUES > 10!
 */
@Suppress("unused")
open class ArrayMap<K, V> : MutableMap<K, V> {

    private var size2: Int = 0

    /**
     * Amount of keys in the map
     */
    override val size: Int
        get() = size2 ushr 1

    /**
     * Keys and values, sequentially
     */
    private var keysValues: Array<Any?> = EMPTY_ARRAY

    @PublishedApi
    internal fun indexOf(key: K): Int {
        var i = 0
        val size2 = size2
        val keysValues = this.keysValues
        while (i < size2) {
            if (keysValues[i] == key) {
                return i
            }
            i += 2
        }
        return -1
    }

    private fun ensureCapacity(extra: Int): Array<Any?> {
        val size2 = size2
        val needed = size2 + (extra shl 1)
        val oldKeysValues = keysValues
        val oldSize = oldKeysValues.size
        if (oldSize < needed) {
            var newSize = Math.max(oldSize, 4)
            while (newSize < needed) {
                newSize *= 2
                if (newSize < 0) {
                    newSize = needed
                    break
                }
            }

            val newKeysValues = arrayOfNulls<Any?>(newSize)
            System.arraycopy(oldKeysValues, 0, newKeysValues, 0, size2)

            keysValues = newKeysValues
            return newKeysValues
        }
        return keysValues
    }

    override fun containsKey(key: K): Boolean {
        return indexOf(key) >= 0
    }

    override fun containsValue(value: V): Boolean {
        var i = 0
        val size2 = size2
        val keysValues = this.keysValues
        while (i < size2) {
            if (keysValues[i + 1] == value) {
                return true
            }
            i += 2
        }
        return false
    }

    override operator fun get(key: K): V? {
        val index = indexOf(key)
        if (index >= 0) {
            return getExisting(index)
        } else {
            return null
        }
    }

    @PublishedApi
    internal fun getExisting(validIndex:Int):V {
        @Suppress("UNCHECKED_CAST")
        return keysValues[validIndex + 1] as V
    }

    inline fun getOrPut(key:K, put:()->V):V {
        val index = indexOf(key)
        if (index >= 0) {
            return getExisting(index)
        } else {
            val newValue = put()
            putNew(key, newValue)
            return newValue
        }
    }

    override fun isEmpty(): Boolean {
        return size == 0
    }

    override fun clear() {
        var i = size2 - 1
        size2 = 0
        val keysValues = this.keysValues
        while (i >= 0) {
            keysValues[i] = null
            i--
        }
    }

    override fun put(key: K, value: V): V? {
        val index = indexOf(key)
        if (index >= 0) {
            val old = keysValues[index + 1]
            keysValues[index + 1] = value
            @Suppress("UNCHECKED_CAST")
            return old as V?
        }

        putNew(key, value)
        return null
    }

    @PublishedApi
    internal fun putNew(key:K, value:V) {
        val keysValues = ensureCapacity(1)
        keysValues[size2] = key
        keysValues[size2 + 1] = value
        size2 += 2
    }

    override fun putAll(from: Map<out K, V>) {
        ensureCapacity(from.size)

        for ((key, value) in from.entries) {
            val index = indexOf(key)
            if (index >= 0) {
                keysValues[index + 1] = value
            } else {
                ensureCapacity(1)
                keysValues[size2] = key
                keysValues[size2 + 1] = value
                size2 += 2
            }
        }
    }

    override fun remove(key: K): V? {
        val index = indexOf(key)
        if (index < 0) {
            return null
        }

        val oldValue = keysValues[index + 1]

        val keysValues = keysValues
        val size2 = size2
        keysValues[index] = keysValues[size2 - 2]
        keysValues[index + 1] = keysValues[size2 - 1]
        keysValues[size2 - 2] = null
        keysValues[size2 - 1] = null
        this.size2 -= 2

        @Suppress("UNCHECKED_CAST")
        return oldValue as V?
    }

    fun forEachEntry(action: ((K, V) -> Unit)) {
        var i = 0
        val size2 = size2
        val keysValues = this.keysValues
        while (i < size2) {
            val key = keysValues[i]
            val value = keysValues[i + 1]

            @Suppress("UNCHECKED_CAST")
            action(key as K, value as V)

            i += 2
        }
    }

    @Deprecated("not supported")
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = throw OperationNotSupportedException()

    @Deprecated("not supported")
    override val keys: MutableSet<K>
        get() = throw OperationNotSupportedException()

    @Deprecated("not supported")
    override val values: MutableCollection<V>
        get() = throw OperationNotSupportedException()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        @Suppress("UNCHECKED_CAST")
        other as ArrayMap<Any?, Any?>

        val size2 = size2
        if (size2 != other.size2) return false

        var i = 0
        val keysValues = this.keysValues
        while (i < size2) {
            val myKey = keysValues[i]
            val myValue = keysValues[i + 1]

            if (other[myKey] != myValue) {
                return false
            }

            i += 2
        }
        return true
    }

    override fun hashCode(): Int {
        val size2 = size2
        var result = 31 * size2

        var i = 0
        val keysValues = this.keysValues
        while (i < size2) {
            val myKeyHash = keysValues[i]?.hashCode() ?: 0
            val myValueHash = keysValues[i + 1]?.hashCode() ?: 0
            result += myKeyHash * 31 + myValueHash
            i += 2
        }

        return result
    }

    override fun toString(): String {
        val sb = StringBuilder(8 + size2 * 12)
        sb.append('{')
        var i = 0
        val size2 = size2
        val keysValues = this.keysValues
        while (i < size2) {
            val myKey = keysValues[i]
            val myValue = keysValues[i + 1]

            if (i != 0) {
                sb.append(", ")
            }
            sb.append(myKey).append("=").append(myValue)

            i += 2
        }
        sb.append("}")
        return sb.toString()
    }

    fun filteredToString(predicate: (K, V) -> Boolean): String {
        val sb = StringBuilder(8 + size2 * 12)
        sb.append('{')
        var i = 0
        val size2 = size2
        val keysValues = this.keysValues
        var first = true
        while (i < size2) {
            val myKey = keysValues[i]
            val myValue = keysValues[i + 1]

            @Suppress("UNCHECKED_CAST")
            if (predicate(myKey as K, myValue as V)) {
                if (!first) {
                    sb.append(", ")
                }
                sb.append(myKey).append("=").append(myValue)
                first = false
            }

            i += 2
        }
        sb.append("}")
        return sb.toString()
    }

    private companion object {
        private val EMPTY_ARRAY = emptyArray<Any?>()
    }
}