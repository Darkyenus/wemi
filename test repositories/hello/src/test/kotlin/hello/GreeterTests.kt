package hello

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName

class GreeterTests {

    @Test
    @DisplayName("Greeter should greet")
    fun testGreeting() {
        assertEquals("hello", Greeter("{}").createGreeting("hello"));
    }

}