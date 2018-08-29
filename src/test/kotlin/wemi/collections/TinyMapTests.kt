package wemi.collections

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.*

/**
 *
 */
class TinyMapTests {

    @Test
    fun basics() {
        val map = CheckedTinyMap<String, Int>()

        map.put("1", 1, 0, 1, null)
        map.put("2", 2, 1, 2, null)
        map.put("1", 3, 2, 2, 1)

        map.remove("3", null, false, 2)
        map.remove("2", 2, true, 2)
        map.remove("1", 3, true, 1)
    }

    @Test
    fun stress() {
        val RNG = Random()

        val tinyMap = CheckedTinyMap<Int, Double>()
        val javaMap = HashMap<Int, Double>()

        for (i in 0..1000) {
            val key = RNG.nextInt(1000)
            if (RNG.nextInt(3) == 0) {
                val value = RNG.nextDouble()

                val tinyContainsKey = tinyMap.pContainsKey(key)
                val javaContainsKey = javaMap.containsKey(key)
                assertEquals(tinyContainsKey, javaContainsKey)

                val tinyContainsValue = tinyMap.pContainsValue(value)
                val javaContainsValue = javaMap.containsValue(value)
                assertEquals(tinyContainsValue, javaContainsValue)

                val oldTiny = tinyMap.putSimple(key, value)
                val oldJava = javaMap.put(key, value)
                assertEquals(oldTiny, oldJava)
            } else {
                val tinyContainsKey = tinyMap.pContainsKey(key)
                val javaContainsKey = javaMap.containsKey(key)
                assertEquals(tinyContainsKey, javaContainsKey)

                val oldTiny = tinyMap.removeSimple(key)
                val oldJava = javaMap.remove(key)
                assertEquals(oldTiny, oldJava)
            }

            assertEquals(tinyMap.size, javaMap.size)
        }
    }

    private fun measureMemory(instances:Int = 100_000, generator:()->Any):Double {
        val runtime = Runtime.getRuntime()
        val usedMemoryStart = runtime.totalMemory() - runtime.freeMemory()

        var usedMemoryEnd:Long = 0
        kotlin.run {
            val items = Array(instances) {
                generator()
            }
            usedMemoryEnd = runtime.totalMemory() - runtime.freeMemory()
            items[0].toString()
        }

        while (runtime.totalMemory() - runtime.freeMemory() >= usedMemoryEnd) {
            System.gc()
            Thread.sleep(50)
        }

        for (i in 0..10) {
            System.gc()
            Thread.sleep(50)
        }

        return (usedMemoryEnd - usedMemoryStart).toDouble() / instances
    }

    @Disabled("Development only")
    @Test
    fun memory() {
        val baseline = measureMemory { Object() }

        val emptyTiny = measureMemory { ArrayMap<String, String>() }
        val emptyJava = measureMemory { HashMap<String, String>() }

        val oneTiny = measureMemory { CheckedTinyMap<String, String>().apply {
            putSimple("", "")
        } }
        val oneJava = measureMemory { HashMap<String, String>().apply {
            put("", "")
        } }

        val tenTiny = measureMemory { CheckedTinyMap<String, String>().apply {
            putSimple("1", "")
            putSimple("2", "")
            putSimple("3", "")
            putSimple("4", "")
            putSimple("5", "")
            putSimple("6", "")
            putSimple("7", "")
            putSimple("8", "")
            putSimple("9", "")
            putSimple("10", "")
        } }
        val tenJava = measureMemory { HashMap<String, String>().apply {
            put("1", "")
            put("2", "")
            put("3", "")
            put("4", "")
            put("5", "")
            put("6", "")
            put("7", "")
            put("8", "")
            put("9", "")
            put("10", "")
        } }

        println("\tBaseline: $baseline b")

        println("Empty:")
        println("\tTiny: $emptyTiny b")
        println("\tJava: $emptyJava b")

        println("One:")
        println("\tTiny: $oneTiny b")
        println("\tJava: $oneJava b")

        println("Ten:")
        println("\tTiny: $tenTiny b")
        println("\tJava: $tenJava b")
    }

    private class CheckedTinyMap<K, V> : ArrayMap<K, V>() {

        init {
            assertEquals(size, 0)
            assertTrue(isEmpty())
        }

        fun put(k:K, v:V, sizeBefore:Int, sizeAfter:Int, old:V?) {
            assertEquals(sizeBefore, size)
            val contains = super.containsKey(k)
            val before = super.get(k)
            assertEquals(before, old)

            val putOld = super.put(k, v)
            assertEquals(putOld, old)
            assertEquals(sizeAfter, size)

            if (contains) {
                assertEquals(sizeBefore, sizeAfter)
            } else {
                assertEquals(sizeBefore + 1, sizeAfter)
            }

            assertTrue(super.containsValue(v))
            assertEquals(super.get(k), v)
        }

        fun putSimple(k:K, v:V):V? {
            val sizeBefore = size

            val contains = super.containsKey(k)
            val before = super.get(k)

            val putOld = super.put(k, v)
            assertEquals(putOld, before)

            val sizeAfter = size
            assertEquals(sizeAfter, size)

            if (contains) {
                assertEquals(sizeBefore, sizeAfter)
            } else {
                assertEquals(sizeBefore + 1, sizeAfter)
            }

            assertTrue(super.containsValue(v))
            assertEquals(super.get(k), v)

            return putOld
        }

        fun remove(k:K, old:V?, contained:Boolean, sizeBefore: Int) {
            assertEquals(sizeBefore, size)
            assertEquals(contained, super.containsKey(k))
            if (contained) {
                assertTrue(super.containsValue(old!!))
            }

            val before = super.get(k)
            assertEquals(before, old)
            val removed = super.remove(k)
            assertEquals(removed, old)

            if (contained) {
                assertEquals(sizeBefore - 1, size)
            } else {
                assertEquals(sizeBefore, size)
                assertEquals(removed, null)
            }

            assertFalse(super.containsKey(k))
        }

        fun removeSimple(k:K):V? {
            val sizeBefore = size
            val contained = super.containsKey(k)

            val before = super.get(k)
            if (contained) {
                assertTrue(super.containsValue(before!!))
            }
            val removed = super.remove(k)
            assertEquals(removed, before)

            if (contained) {
                assertEquals(sizeBefore - 1, size)
            } else {
                assertEquals(sizeBefore, size)
                assertEquals(removed, null)
            }

            assertFalse(super.containsKey(k))
            return removed
        }

        fun pContainsKey(k:K):Boolean {
            return super.containsKey(k)
        }

        fun pContainsValue(v:V):Boolean {
            return super.containsValue(v)
        }
    }
}
