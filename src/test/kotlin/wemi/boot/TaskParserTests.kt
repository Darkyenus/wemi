package wemi.boot

import org.junit.Assert
import org.junit.Test

/**
 *
 */
class TaskParserTests {

    private fun assertEquals(line:String, vararg tasks:Task) {
        val lines = TaskParser.parseTokens(line, 0)
        val tokens = TaskParser.createTokens(lines.tokens)
        val parsedTasks = TaskParser.parseTasks(tokens).toTypedArray()

        Assert.assertArrayEquals(tasks, parsedTasks)
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
        assertEqualsMulti("key", Task(null, emptyList(),"key", emptyList()))
        assertEqualsMulti("project/key", Task("project", emptyList(),"key", emptyList()))
        assertEqualsMulti("conf:key", Task(null, listOf("conf"),"key", emptyList()))
        assertEqualsMulti("conf1:conf2:key", Task(null, listOf("conf1", "conf2"),"key", emptyList()))
        assertEqualsMulti("project/conf1:conf2:key", Task("project", listOf("conf1", "conf2"),"key", emptyList()))
        assertEqualsMulti("project /  conf1 :   conf2:key  ", Task("project", listOf("conf1", "conf2"),"key", emptyList()))
        assertEqualsMulti("project/conf1:conf2:key", Task("project", listOf("conf1", "conf2"),"key", emptyList()))
        assertEqualsMulti("key free", Task(null, emptyList(),"key", listOf(null to "free")))
        assertEqualsMulti("key free free", Task(null, emptyList(),"key", listOf(null to "free", null to "free")))
        assertEqualsMulti("key free1 free2", Task(null, emptyList(),"key", listOf(null to "free1", null to "free2")))
        assertEqualsMulti("key k=f", Task(null, emptyList(),"key", listOf("k" to "f")))
        assertEqualsMulti("key k  =  f   ", Task(null, emptyList(),"key", listOf("k" to "f")))
        assertEqualsMulti("key k=f k=f", Task(null, emptyList(),"key", listOf("k" to "f", "k" to "f")))
        assertEqualsMulti("key free k=f", Task(null, emptyList(),"key", listOf(null to "free", "k" to "f")))
        assertEqualsMulti("key k=f free", Task(null, emptyList(),"key", listOf("k" to "f", null to "free")))
        assertEqualsMulti("project /  conf1 :   conf2:key   k=f free free2 kk=ff",
                Task("project", listOf("conf1", "conf2"),"key", listOf("k" to "f", null to "free", null to "free2", "kk" to "ff")))
    }

}