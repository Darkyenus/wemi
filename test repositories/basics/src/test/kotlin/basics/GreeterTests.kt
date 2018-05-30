package basics

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assumptions

class GreeterTests {

    @Test
    @DisplayName("Greeter should greet")
    fun testGreeting() {
        assertEquals("hello", Greeter("{}").createGreeting("hello"));
    }

    @Test
    fun pickyTest() {
        val time = System.currentTimeMillis()
        Assumptions.assumeTrue(time == 1522665955743)

        assertEquals(2, time % 3)
    }

    @Test
    @Disabled("Too stupid")
    fun stupidTest() {
        assertEquals(true, true)
    }
}