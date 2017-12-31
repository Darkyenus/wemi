# Things to do next

1. JUnit/unit testing support

JUnit 5:
dependencies {
    testCompile("org.junit.jupiter:junit-jupiter-api:5.0.2")
    testRuntime("org.junit.jupiter:junit-jupiter-engine:5.0.2")
}

JUnit 4:
dependencies {
    testCompile("junit:junit:4.12")
    testRuntime("org.junit.vintage:junit-vintage-engine:4.12.2")
}


```
Explicit Dependencies

junit-4.12.jar in test scope: to run tests using JUnit 4.

junit-platform-runner in test scope: location of the JUnitPlatform runner

junit-jupiter-api in test scope: API for writing tests, including @Test, etc.

junit-jupiter-engine in test runtime scope: implementation of the Engine API for JUnit Jupiter

Transitive Dependencies

junit-platform-launcher in test scope

junit-platform-engine in test scope

junit-platform-commons in test scope

opentest4j in test scope
```

http://junit.org/junit5/docs/current/user-guide/#running-tests-config-params

The launching API is in the junit-platform-launcher module.

An example consumer of the launching API is the ConsoleLauncher in the junit-platform-console project.



1. When last task in non-interactive mode fails (exception?), exit with 1 or something

1. Use Path instead of File everywhere

1. Update to latest Idea

1. Update to latest kotlin

1. Javadoc everything

1. New project wizard (IDE)

1. Allow projects to inherit from parent project configuration/config set
	- Maybe simple "setup function" would be enough...

1. IDE (IntelliJ, other Jetbrains later) integrations

1. Allow to build IntelliJ plugins: https://github.com/JetBrains/gradle-intellij-plugin

1. Self hosting

1. Trace debug mode

1. Allow projects to depend on other projects and implement this in IDE plugin

1. Kotlin JS

1. Kotlin Native
