package hello

/**
 * [java.util.Random] instance. Used from [hello.Greeter]!
 */
val Random = java.util.Random()

/**
 * Main. Greets the user of this computer with random personalized greeting.
 */
fun main(args: Array<String>) {
    println("Hello from compiled Wemi file!")

    println("Kotlin version is ${kotlin.KotlinVersion.CURRENT}")

    Greeter("Hello {}!", "Hi {}", "{}, welcome!", "Ahoy, {}!")
            .greet(System.getProperty("user.name"))
}