package wemi.boot

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 *
 */
class TaskParserTests {

    private val Whitespace = Regex("\\s+")

    private fun assertEquals(line:String, vararg tasks:Task) {
        val parsed = TaskParser.PartitionedLine(listOf(line), true, false)
        assertArrayEquals(tasks, parsed.tasks.toTypedArray())

        if (line.indexOf('"') == -1) {
            // Test it as if it came from command line
            // (simple test does not handle quotes yet)
            val parsedFromCommandLine = TaskParser.PartitionedLine(line.split(Whitespace), false, false)
            assertArrayEquals(tasks, parsedFromCommandLine.tasks.toTypedArray())
        }
    }

    private fun assertEqualsMulti(line: String, vararg tasks: Task) {
        assertEquals(line, *tasks)
        val twoTasks = (tasks.toList() + tasks.toList()).toTypedArray()
        assertEquals("$line;$line", *twoTasks)
        assertEquals("$line  ;$line", *twoTasks)
        assertEquals("$line;  $line", *twoTasks)
        assertEquals("$line;$line;$line", *(tasks.toList() + tasks.toList() + tasks.toList()).toTypedArray())
    }

    @Test
    fun testTaskParsing() {
        assertEqualsMulti("key", Task(null, emptyList(),"key", emptyArray()))
        assertEqualsMulti("project/key", Task("project", emptyList(),"key", emptyArray()))
        assertEqualsMulti("conf:key", Task(null, listOf("conf"),"key", emptyArray()))
        assertEqualsMulti("conf1:conf2:key", Task(null, listOf("conf1", "conf2"),"key", emptyArray()))
        assertEqualsMulti("project/conf1:conf2:key", Task("project", listOf("conf1", "conf2"),"key", emptyArray()))
        assertEqualsMulti("project /  conf1 :   conf2:key  ", Task("project", listOf("conf1", "conf2"),"key", emptyArray()))
        assertEqualsMulti("project/conf1:conf2:key", Task("project", listOf("conf1", "conf2"),"key", emptyArray()))
        assertEqualsMulti("key free", Task(null, emptyList(),"key", arrayOf("" to "free")))
        assertEqualsMulti("key free free", Task(null, emptyList(),"key", arrayOf("" to "free", "" to "free")))
        assertEqualsMulti("key free1 free2", Task(null, emptyList(),"key", arrayOf("" to "free1", "" to "free2")))
        assertEqualsMulti("key k=f", Task(null, emptyList(),"key", arrayOf("k" to "f")))
        assertEqualsMulti("key k  =  f   ", Task(null, emptyList(),"key", arrayOf("k" to "f")))
        assertEqualsMulti("key k=f k=f", Task(null, emptyList(),"key", arrayOf("k" to "f", "k" to "f")))
        assertEqualsMulti("key free k=f", Task(null, emptyList(),"key", arrayOf("" to "free", "k" to "f")))
        assertEqualsMulti("key k=f free", Task(null, emptyList(),"key", arrayOf("k" to "f", "" to "free")))
        assertEqualsMulti("project /  conf1 :   conf2:key   k=f free free2 kk=ff",
                Task("project", listOf("conf1", "conf2"),"key", arrayOf("k" to "f", "" to "free", "" to "free2", "kk" to "ff")))
    }

}