package hello;

import java.util.Random;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

/**
 *
 */
public class Greeter {

    private static final Logger LOG = LoggerFactory.getLogger(Greeter.class);

    private final String[] greetings;

    public Greeter(String...greetings) {
        this.greetings = greetings;
    }

    public String createGreeting(String name) {
        final Random random = HelloWemiKt.getRandom();
        return this.greetings[random.nextInt(this.greetings.length)].replace("{}", name);
    }

    public void greet(String name) {
        final String greeting = createGreeting(name);
        System.out.println(greeting);
        LOG.warn("Greeted {}", name);
    }

    public static void main(String[] args){
        System.out.println("I am a Greeter! Hi!");
    }
}
