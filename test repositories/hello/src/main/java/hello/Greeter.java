package hello;

import java.util.Random;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

/**
 * This is a Java class for greeting people.
 * This documentation is here just to demo the <a href="https://github.com/Kotlin/dokka">Dokka</a> integration.
 * <br>
 * It can be created by executing <code>archivingDocs:archive</code>.
 */
public class Greeter {

    /**
     * Private logger. Not visible in generated documentation, by default.
     */
    private static final Logger LOG = LoggerFactory.getLogger(Greeter.class);

    private final String[] greetings;

    /**
     * Create greeter that uses given greetings.
     * @param greetings parametrized strings, all <code>{}</code>'s will be replaced with the greeted person's name.
     */
    public Greeter(String...greetings) {
        this.greetings = greetings;
    }

    /**
     * Create a random greeting.
     * @param name to be greeted
     * @return personalized greeting
     */
    public String createGreeting(String name) {
        final Random random = HelloWemiKt.getRandom();
        return this.greetings[random.nextInt(this.greetings.length)].replace("{}", name);
    }

    /**
     * Greet person named <code>name</code> to the <code>stdout</code>.
     */
    public void greet(String name) {
        final String greeting = createGreeting(name);
        System.out.println(greeting);
        LOG.warn("Greeted {}", name);
    }

    /**
     * Main. Does nothing useful.
     *
     * @param args ignored
     */
    public static void main(String[] args){
        System.out.println("I am a Greeter! Hi!");
    }
}
