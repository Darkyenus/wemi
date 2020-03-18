package basics

/**
 * [java.util.Random] instance. Used from [basics.Greeter]!
 */
val Random = java.util.Random()

/**
 * Main. Greets the user of this computer with random personalized greeting.
 */
fun main(args: Array<String>) {
    println("Hello from compiled Wemi file!")

    println("Kotlin version is ${KotlinVersion.CURRENT}, Greeter version is ${Version.VERSION}, built at ${Version.BUILD_TIME}")
    println("The random number for today is: $RANDOM_NUMBER")
    println("Is the generated file generated? Answer is: ${generated.Generated.REALLY_GENERATED}")

    val greeter = Greeter("Hello {}!", "Hi {}", "{}, welcome!", "Ahoy, {}!")
    greeter.greet(System.getProperty("user.name"))

    println("Art for today is: ${greeter.artName}")
}