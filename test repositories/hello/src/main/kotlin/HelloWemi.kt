import com.hello.Greeter

/**
 *
 */

fun main(args: Array<String>) {
    println("Hello from compiled Wemi file!")

    Greeter("Hello {}!", "Hi {}", "{}, welcome!", "Ahoy, {}!")
            .greet(System.getProperty("user.name"))
}