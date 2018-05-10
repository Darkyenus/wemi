import dummypackage.MyClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import wemiplugin.jvmhotswap.agent.BytecodeUtil

/**
 *
 */
class BytecodeUtilTests {

    private fun testSingleClass(c:Class<*>) {
        val name = c.name
        val classNameStart = name.lastIndexOf('.')
        val resourceName = "${if (classNameStart == -1) name else name.substring(classNameStart + 1)}.class"
        val bytes = c.getResourceAsStream(resourceName).readBytes()

        val className = BytecodeUtil.javaClassName(bytes)
        assertEquals(name, BytecodeUtil.bytecodeToNormalClassName(className))
    }

    @Test
    fun javaClassNameTest() {
        testSingleClass(MyClass::class.java)
        testSingleClass(MyClass.MyInnerClass::class.java)
        testSingleClass(BytecodeUtilTests::class.java)
    }

}