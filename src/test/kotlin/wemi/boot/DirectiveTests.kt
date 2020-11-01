package wemi.boot

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.util.*

/**
 *
 */
class DirectiveTests {

    @Test
    fun directiveParsingOk() {
        val expected = listOf(
                BuildClasspathDependency::class.java to arrayOf("BCD"),
                BuildDependency::class.java to arrayOf("GOF", "", ""),
                BuildDependency::class.java to arrayOf("G", "N", "V"),
                BuildDependency::class.java to arrayOf("G", "N", "V"),
                BuildDependency::class.java to arrayOf("G", "N", "V"),
                BuildDependency::class.java to arrayOf("G", "N", "V"),
                BuildDependencyRepository::class.java to arrayOf("NAME", "\tCRAZY:U\\R\"L\t"),
                BuildDependencyPlugin::class.java to arrayOf("wemi-plugin-foo", "com.darkyen.wemi"),
                BuildDependencyPlugin::class.java to arrayOf("wemi-plugin-bar", "com.example.group")
        )
        val expectedIterator = expected.iterator()

        var directiveIndex = 0
        val result = InputStreamReader(DirectiveFields::class.java.getResourceAsStream("directivesOk.txt"), Charsets.UTF_8).use {
            parseFileDirectives(it.buffered(), SupportedDirectives) {annotation, arguments ->
                directiveIndex++
                assertTrue(expectedIterator.hasNext())
                val (eClass, eArguments) = expectedIterator.next()

                assertEquals(eClass, annotation) {"Directive $directiveIndex $eClass -> ${eArguments.contentToString()}"}
                assertArrayEquals(eArguments, arguments) {"Directive $directiveIndex $eClass -> ${eArguments.contentToString()}"}
            }
        }

        assertFalse(expectedIterator.hasNext())
        assertTrue(result)
    }

}