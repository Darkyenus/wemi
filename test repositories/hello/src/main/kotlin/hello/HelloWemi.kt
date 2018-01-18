package hello

/**
 *
 */

val Random = java.util.Random()

fun main(args: Array<String>) {
    println("Hello from compiled Wemi file!")

    println("Kotlin version is ${kotlin.KotlinVersion.CURRENT}")

    Greeter("Hello {}!", "Hi {}", "{}, welcome!", "Ahoy, {}!")
            .greet(System.getProperty("user.name"))
}