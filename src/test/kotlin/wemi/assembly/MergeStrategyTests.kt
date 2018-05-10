package wemi.assembly

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError

/**
 *
 */
class MergeStrategyTests {

    private val VALUES = arrayOf(
            "first".toByteArray(),
            "second".toByteArray(),
            "third".toByteArray()
    )

    private fun assertMapEquals(expected:Map<String, AssemblySource>?, actual:Map<String, AssemblySource>?) {
        if (expected == null && actual == null) {
            return
        } else if (expected == null || actual == null) {
            throw AssertionFailedError("Expected: $expected, got: $actual", expected, actual)
        }

        val actualRemaining = HashMap(actual)
        for ((key, expectedValue) in expected) {
            assertTrue(actualRemaining.containsKey(key)) { "Actual should contain $key -> $expectedValue, but doesn't" }
            val removed = actualRemaining.remove(key)
            assertArrayEquals(expectedValue.data, removed?.data) {"Actual data should contain something else"}
        }
        assertTrue(actualRemaining.isEmpty()) {"Actual has unexpected values: $actualRemaining"}
    }

    private fun fooOperation(own:Int):AssemblyOperation {
        val operation = AssemblyOperation()
        for ((index, value) in VALUES.withIndex()) {
            operation.addSource("foo", value, index == own)
        }
        return operation
    }

    @Test
    fun firstTest() {
        val resolved =
                fooOperation(-1).resolve({MergeStrategy.First}, DefaultRenameFunction)

        assertMapEquals(mapOf("foo" to source(VALUES[0])), resolved)
    }

    @Test
    fun lastTest() {
        val resolved =
                fooOperation(-1).resolve({MergeStrategy.Last}, DefaultRenameFunction)

        assertMapEquals(mapOf("foo" to source(VALUES.last())), resolved)
    }

    @Test
    fun singleOwn() {
        for (index in VALUES.indices) {
            val resolved =
                    fooOperation(index).resolve({MergeStrategy.SingleOwn}, DefaultRenameFunction)

            assertMapEquals(mapOf("foo" to source(VALUES[index])), resolved)
        }
    }

    @Test
    fun singleOrError() {
        assertMapEquals(null, fooOperation(-1).resolve({MergeStrategy.SingleOrError}, DefaultRenameFunction))

        val operation = AssemblyOperation()
        operation.addSource("foo", VALUES[0], false)
        assertMapEquals(mapOf("foo" to source(VALUES[0])), operation.resolve({MergeStrategy.SingleOrError}, DefaultRenameFunction))
    }

    @Test
    fun concatenate() {
        assertMapEquals(mapOf("foo" to source(VALUES.fold(ByteArray(0)) {l, r -> l + r})),
                fooOperation(-1).resolve({MergeStrategy.Concatenate}, DefaultRenameFunction))
    }

    @Test
    fun lines() {
        assertMapEquals(mapOf("foo" to source("first\nsecond\nthird\n".toByteArray())),
                fooOperation(-1).resolve({MergeStrategy.Lines}, DefaultRenameFunction))

        AssemblyOperation().let { operation ->
            for (value in arrayOf(
                    "third".toByteArray(),
                    "second\r\n".toByteArray(),
                    "third\r\nfourth".toByteArray())) {
                operation.addSource("foo", value, false)
            }
            assertMapEquals(mapOf("foo" to source("third\r\nsecond\r\nthird\r\nfourth\r\n".toByteArray())),
                    operation.resolve({MergeStrategy.Lines}, DefaultRenameFunction))
        }
    }

    @Test
    fun uniqueLines() {
        assertMapEquals(mapOf("foo" to source("first\nsecond\nthird\n".toByteArray())),
                fooOperation(-1).resolve({MergeStrategy.UniqueLines}, DefaultRenameFunction))

        AssemblyOperation().let { operation ->
            for (value in arrayOf(
                    "third".toByteArray(),
                    "second\r\n".toByteArray(),
                    "third\r\nfourth".toByteArray())) {
                operation.addSource("foo", value, false)
            }
            assertMapEquals(mapOf("foo" to source("third\r\nsecond\r\nfourth\r\n".toByteArray())),
                    operation.resolve({MergeStrategy.UniqueLines}, DefaultRenameFunction))
        }
    }

    @Test
    fun discard() {
        assertMapEquals(mapOf(), fooOperation(-1).resolve({MergeStrategy.Discard}, DefaultRenameFunction))

        AssemblyOperation().let { operation ->
            for ((i, value) in VALUES.withIndex()) {
                operation.addSource("foo$i", value, false)
            }
            assertMapEquals(mapOf(
                    "foo0" to source(VALUES[0]),
                    "foo1" to source(VALUES[1]),
                    "foo2" to source(VALUES[2])
            ), operation.resolve({MergeStrategy.Discard}, DefaultRenameFunction))
        }
    }

    @Test
    fun deduplicate() {
        assertMapEquals(null, fooOperation(-1).resolve({MergeStrategy.Deduplicate}, DefaultRenameFunction))

        AssemblyOperation().let { operation ->
            operation.addSource("foo", VALUES[0], false)
            operation.addSource("foo", VALUES[0], false)
            operation.addSource("foo1", VALUES[1], false)
            assertMapEquals(mapOf(
                    "foo" to source(VALUES[0]),
                    "foo1" to source(VALUES[1])
            ), operation.resolve({MergeStrategy.Deduplicate}, DefaultRenameFunction))
        }
    }

    @Test
    fun rename() {
        var order = 0
        assertMapEquals(mapOf(
                "foo0" to source(VALUES[0]),
                "foo" to source(VALUES[1]),
                "foo1" to source(VALUES[2])
        ), fooOperation(1).resolve({MergeStrategy.Rename}, {
            source, name ->
            if (source.own) {
                name
            } else {
                "$name${order++}"
            }
        }))
    }

    private fun source(bytes:ByteArray):AssemblySource {
        return AssemblySource("test-source", null, -1, false, bytes)
    }
}