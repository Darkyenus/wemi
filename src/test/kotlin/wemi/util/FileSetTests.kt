package wemi.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureNanoTime

/**
 * Tests for [FileSet] and related methods
 */
class FileSetTests {

    @Test
    fun patternSanitization() {
        assertEquals("foo", sanitizePattern("foo"))
        assertEquals("foo", sanitizePattern("./foo"))
        assertEquals("foo", sanitizePattern("foo/."))
        assertEquals("foo/**", sanitizePattern("foo/./"))
        assertEquals("foo/**", sanitizePattern("foo/.///"))
        assertEquals("foo/bar", sanitizePattern("foo/./bar"))
        assertEquals("foo/bar", sanitizePattern("foo/./././bar"))
        assertEquals("foo/bar", sanitizePattern("foo//bar"))
        assertEquals("foo/bar", sanitizePattern("foo////bar"))
        assertEquals("foo**bar", sanitizePattern("foo**bar"))
        assertEquals("foo**bar", sanitizePattern("foo***bar"))
        assertEquals("foo**bar", sanitizePattern("foo****bar"))
    }

    @Test
    fun patternMatchesTest() {
        // Literals
        assertTrue(patternMatches("foo", "foo", false))
        assertTrue(patternMatches("foo", "foo", true))
        assertFalse(patternMatches("foo", "foO", true))
        assertTrue(patternMatches("foo", "foO", false))

        // Single character
        assertTrue(patternMatches("?oo", "foo", true))
        assertFalse(patternMatches("?oo", "oo", true))
        assertTrue(patternMatches("??o", "foo", true))
        assertFalse(patternMatches("??o", "fooo", true))
        assertTrue(patternMatches("???", "foo", true))
        assertFalse(patternMatches("???", "fo", true))

        // Many charactery
        assertTrue(patternMatches("*", "foo", true))
        assertTrue(patternMatches("*", "", true))
        assertTrue(patternMatches("*", "foo?bar", true))
        assertFalse(patternMatches("*", "foo/bar", true))

        // Any characters
        assertTrue(patternMatches("**", "foo", true))
        assertTrue(patternMatches("**", "", true))
        assertTrue(patternMatches("**", "foo?bar", true))
        assertTrue(patternMatches("**", "foo/bar", true))

        // Trailing slash
        assertTrue(patternMatches(sanitizePattern("*/"), "foo", true))
        assertTrue(patternMatches(sanitizePattern("*/"), "foo/bar", true))
        assertTrue(patternMatches(sanitizePattern("*/"), "foo/bar/baz", true))

        // Leading **
        assertTrue(patternMatches("**/foo", "foo", true))
        assertTrue(patternMatches("**/foo", "bar/foo", true))
        assertTrue(patternMatches("**/foo", "bar/baz/foo", true))
        assertFalse(patternMatches("**/foo", "bar/baz/foo/boo", true))

        // Ant Examples
        assertTrue(patternMatches("**/FOO/*", "FOO/Repository", true))
        assertTrue(patternMatches("**/FOO/*", "org/company/FOO/Entries", true))
        assertTrue(patternMatches("**/FOO/*", "org/company/product/tools/animal/FOO/Entries", true))
        assertFalse(patternMatches("**/FOO/*", "org/company/FOO/random/words/Entries", true))

        assertTrue(patternMatches("org/company/product/**", "org/company/product", true))
        assertTrue(patternMatches("org/company/product/**", "org/company/product/tools/animal/docs/index.html", true))
        assertTrue(patternMatches("org/company/product/**", "org/company/product/text.xml", true))
        assertFalse(patternMatches("org/company/product/**", "org/company/xyz.java", true))

        assertTrue(patternMatches("org/company/**/FOO/*", "org/company/FOO/Entries", true))
        assertTrue(patternMatches("org/company/**/FOO/*", "org/company/product/tools/animal/FOO/Entries", true))
        assertFalse(patternMatches("org/company/**/FOO/*", "org/company/FOO/random/words/Entries", true))

        assertTrue(patternMatches("**/test/**", "test", true))
        assertTrue(patternMatches("**/test/**", "foo/test", true))
        assertTrue(patternMatches("**/test/**", "foo/bar/test", true))
        assertTrue(patternMatches("**/test/**", "test/foo", true))
        assertTrue(patternMatches("**/test/**", "test/foo/bar", true))
        assertTrue(patternMatches("**/test/**", "foo/bar/test/foo/bar", true))
        assertFalse(patternMatches("**/test/**", "testament", true))
        assertFalse(patternMatches("**/test/**", "foo/testament", true))
        assertFalse(patternMatches("**/test/**", "testament/bar", true))
        assertFalse(patternMatches("**/test/**", "foo/testament/bar", true))

        // Complex combinations
        assertTrue(patternMatches("b?*r", "bar", true))
        assertTrue(patternMatches("b?*r", "bazaar", true))
        assertFalse(patternMatches("b?*r", "br", true))
        assertFalse(patternMatches("b?*r", "baz/ar", true))

        assertTrue(patternMatches("b?*?r", "bazar", true))
        assertTrue(patternMatches("b?*?r", "baar", true))
        assertFalse(patternMatches("b?*?r", "bar", true))
        assertFalse(patternMatches("b?*?r", "ba/ar", true))

        assertTrue(patternMatches("b?**?r", "ba/ar", true))
        assertTrue(patternMatches("b?**?r", "ba/not/ar", true))
        assertTrue(patternMatches("b?**?r", "ba/not/ar", true))

        // Recursion busters
        assertTrue(patternMatches("*?*a*?*b*?*c*?*d*?*e*", "_a_b_c_d_e_", true))
        assertTrue(patternMatches("*?*a*?*b*?*c*?*d*?*e*", "aabbccddeee", true))
        assertTrue(patternMatches("*?*a*?*b*?*c*?*d*?*e*", "_a__a___b___b_c_b_dd_d____e_ee_e_", true))
        assertTrue(patternMatches("*test*hula*foo", "testhulafoo", true))
        assertTrue(patternMatches("**t*h*f", "ththththththththththththththththththththththththththth/thththththth/hula test hula f", true))
    }

    @Disabled("Development only") // Takes a long time, currently
    @Test
    fun patternMatchesBenchmark() {
        println("-- patternMatchesBenchmark --")

        fun bench() {
            patternMatchesTest()
            patternMatchesTest()
            patternMatchesTest()
            patternMatchesTest()
            patternMatchesTest()
        }

        val iterations = 10000
        // Warmup
        for (i in 1..iterations) {
            bench()
        }

        for (i in 1..10) {
            // Measure
            val rounds = LongArray(10) {
                measureNanoTime {
                    for (j in 1..iterations) {
                        bench()
                    }
                }
            }
            val nsPerTest = rounds.average() / (5.0 * iterations)
            println("patternMatchesTest(): $nsPerTest ns")
        }
    }

    @Test
    fun matchingFilesTest() {
        val temp = Files.createTempDirectory("matchingFilesTest")
        val kotlinDir = Files.createDirectories(temp / "src/main/kotlin")
        val javaDir = Files.createDirectories(temp / "src/main/java")
        val src1 = Files.createFile(temp / "src/main/kotlin/SourceFile.kt")
        Files.createFile(temp / "src/main/kotlin/._trash_SourceFile.kt")
        Files.createFile(temp / "src/main/kotlin/.kt")
        val src2 = Files.createFile(temp / "src/main/kotlin/AnotherSourceFile.kt")
        val src3 = Files.createFile(temp / "src/main/java/JavaSource.java")
        Files.createFile(temp / "src/main/java/Image.png")
        Files.createFile(temp / "src/main/java/.Thumbs.db")
        Files.createFile(temp / "src/main/StrayFile.java")
        val expectedResult = setOf(src1, src2, src3)

        val includeSet = FileSet(temp, include("src/main/kotlin/**.kt"), include("src/main/java/**.java"))
        assertEquals(expectedResult, includeSet.matchingFiles().toSet())

        val unionSet = FileSet(kotlinDir, include("**.kt")) + FileSet(javaDir, include("**.java"))
        assertEquals(expectedResult, unionSet.matchingFiles().toSet())
    }

    @Test
    fun matchingSingleFileTest() {
        val temp = Files.createTempDirectory("matchingSingleFileTest")
        val singleFile = Files.createFile(temp / "Archive.jar")

        val expectedResult = setOf(singleFile)

        val normalSet = FileSet(singleFile)
        assertEquals(expectedResult, normalSet.matchingFiles().toSet())

        val includeSet = FileSet(singleFile, include("*.jar"))
        assertEquals(expectedResult, includeSet.matchingFiles().toSet())

        val excludeSet = FileSet(singleFile, exclude("*.java"))
        assertEquals(expectedResult, excludeSet.matchingFiles().toSet())

        val emptyIncludeSet = FileSet(singleFile, include("a*.jar"))
        assertEquals(emptySet<Path>(), emptyIncludeSet.matchingFiles().toSet())

        val emptyExcludeSet = FileSet(singleFile, exclude("*.ja?"))
        assertEquals(emptySet<Path>(), emptyExcludeSet.matchingFiles().toSet())
    }

}