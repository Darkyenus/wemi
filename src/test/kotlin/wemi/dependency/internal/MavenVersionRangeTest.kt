package wemi.dependency.internal

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 *
 */
class MavenVersionRangeTest {
	@Test
	fun mavenVersionParsing() {
		assertArrayEquals(intArrayOf(1, 0, 0), parseMavenVersion("1.0").numeric)
		assertArrayEquals(intArrayOf(1, 15, 0), parseMavenVersion("1.15").numeric)
		assertArrayEquals(intArrayOf(42, 15, 0), parseMavenVersion("42.15").numeric)
		assertArrayEquals(intArrayOf(42, 15, 145), parseMavenVersion("42.15.145").numeric)
		assertArrayEquals(intArrayOf(42, 15, 145, 99), parseMavenVersion("42.15.145.99").numeric)

		assertEquals("", parseMavenVersion("1.0").qualifier)
		assertEquals("", parseMavenVersion("1.15").qualifier)
		assertEquals("", parseMavenVersion("42.15").qualifier)
		assertEquals("", parseMavenVersion("42.15.145").qualifier)
		assertEquals("", parseMavenVersion("42.15.145.99").qualifier)

		assertEquals("SNAPSHOT", parseMavenVersion("1.0-SNAPSHOT").qualifier)
		assertEquals("SNAPSHOT", parseMavenVersion("1.15-SNAPSHOT").qualifier)
		assertEquals("SNAPSHOT", parseMavenVersion("42.15-SNAPSHOT").qualifier)
		assertEquals("SNAPSHOT", parseMavenVersion("42.15.145-SNAPSHOT").qualifier)
		assertEquals("SNAPSHOT", parseMavenVersion("42.15.145.99-SNAPSHOT").qualifier)
		assertEquals("", parseMavenVersion("42.15.145.99-").qualifier)

		assertEquals("alpha", parseMavenVersion("1-alpha").qualifier)
	}

	@Test
	fun mavenVersionComparison() {
		assertTrue(parseMavenVersion("1.0").compareTo(parseMavenVersion("1.0")) == 0)
		assertTrue(parseMavenVersion("1.0") < parseMavenVersion("1.1"))
		assertTrue(parseMavenVersion("1.0") < parseMavenVersion("1.0.1"))
		assertTrue(parseMavenVersion("1") < parseMavenVersion("1.0.1"))
		assertTrue(parseMavenVersion("1") < parseMavenVersion("1.0.1-alpha"))
		assertTrue(parseMavenVersion("1") > parseMavenVersion("1-alpha"))
		assertTrue(parseMavenVersion("1-alpha") < parseMavenVersion("1-beta"))
		assertTrue(parseMavenVersion("1").compareTo(parseMavenVersion("1-0")) == 0)
	}

	private fun range(range:String, contains:Array<String>, doesNotContain:Array<String>) {
		val r = parseMavenVersionRange(range)
		for (contain in contains) {
			assertTrue(contain in r, contain)
		}
		for (notContain in doesNotContain) {
			assertFalse(notContain in r, notContain)
		}
	}

	@Test
	fun mavenRangeComparison() {
		range("1.0", arrayOf("1.0", "2.0"), arrayOf("0.9.9", "1.0-alpha"))
		range("(,1.0]", arrayOf("1.0", "0.9.9", "1.0-alpha"), arrayOf("2.0"))
		range("(,1.0)", arrayOf("0.9.9", "1.0-alpha"), arrayOf("1.0", "2.0"))
		range("[1.0]", arrayOf("1.0"), arrayOf("0.9.9", "1.0-alpha", "2.0"))
		range("[1.0,)", arrayOf("1.0", "2.0"), arrayOf("0.9.9", "1.0-alpha"))
		range("(1.0,)", arrayOf("2.0"), arrayOf("1.0", "0.9.9", "1.0-alpha"))
		range("(1.0,2.0)", arrayOf("1.1"), arrayOf("1.0", "0.9.9", "1.0-alpha", "2.0"))
		range("[1.0,2.0]", arrayOf("1.0", "1.1", "2.0"), arrayOf("0.9.9", "1.0-alpha"))
		range("(,1.0],[1.2,)", arrayOf("1.0", "0.9.9", "1.0-alpha", "2.0"), arrayOf("1.1", "1.0.1"))
		range("(,1.1),(1.1,)", arrayOf("1.0", "0.9.9", "1.0-alpha", "2.0", "1.0.1"), arrayOf("1.1"))
	}
}