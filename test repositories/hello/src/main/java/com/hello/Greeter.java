package com.hello;

import java.util.Random;

/**
 *
 */
public class Greeter {

    private final String[] greetings;

    public Greeter(String...greetings) {
        this.greetings = greetings;
    }

    public void greet(String name) {
        final Random random = HelloWemiKt.getRandom();
        System.out.println(this.greetings[random.nextInt(this.greetings.length)].replace("{}", name));
    }
}
