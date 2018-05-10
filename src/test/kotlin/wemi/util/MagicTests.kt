package wemi.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests Magic
 */
class MagicTests {

    @Test
    fun classpathFileOfTest() {
        val myPath = Magic.classpathFileOf(this.javaClass) ?: fail("Path to $this should not be null")
        val innerClassPath = Magic.classpathFileOf(InnerClass::class.java) ?: fail("Path to ${InnerClass::class} should not be null")

        val randomLambda = {println("Do not invoke me!")}
        val lambdaPath = Magic.classpathFileOf(randomLambda.javaClass) ?: fail("Path to $randomLambda should not be null")

        assertEquals(myPath, innerClassPath)
        assertEquals(myPath, lambdaPath)

        if (myPath.isRegularFile()) {
            assertTrue(myPath.name.pathHasExtension("jar")) {"Path is not a jar file: $myPath"}
        } else if (myPath.isDirectory()) {
            assertTrue(myPath.resolve("wemi")?.isDirectory() == true) {"Path is not a classpath root file: $myPath"}
        } else {
            fail("My path is neither jar nor valid directory: $myPath")
        }
    }

    class InnerClass

}