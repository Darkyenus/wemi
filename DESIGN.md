# General Design

## Tenets, sort of
- Strive for simplicity
- Be explicit, do not hide things
- Have speed and low overhead in mind at all times
- Do not introduce dependencies unless necessary, prefer to (down)load them at runtime, when needed
- Do not be afraid of breaking changes of code, at least during development

## Internal architecture
At the core of WEMI are four intertwined concepts.
- Key
- Scope
- Project
- Configuration

*Projects* and *configurations* hold values addressable by *keys*.
*Scope* is made out of single *project* and an ordered list of zero or more
*configurations* and allows to query values bound to *keys*.

### Keys
Key is represented by an instance of `Key<Value>`. Each key type has a different
name, type (generic `Value`) and is represented by a single instance.
The key also holds its human readable description.

Key itself does not hold any bound value, though it may have a default value,
which is immutable and identical for the whole and each build, and is always considered
last during any *value resolution*.

Values bound to keys are not evaluated when bound, but each time when requested.
In a way, keys are not assigned object of type `Value`, but of type `() -> Value`.
This allows to use keys for both preferences/settings and for operations/tasks.

For example, `projectName` is a key of type `String` that holds the project's name
(used for example when publishing). `clean` is a key of type `Int`, which, when retrieved,
will clear the build files and key cache.

Key may be marked as cached during declaration (not by default). Scopes hold
resolved values of these keys for future invocations. Only keys that never change
value for the build script run/compilation AND are costly to compute should be cached.
Example of cached key is `externalClasspath`, which may have to download dependencies
from the internet and thus may be a slow operation.

Key definition looks like this:
```kotlin
val compile by key<File>("Compile sources and return the result")
```
Internally, the key is defined using `KeyDelegate<Value>` object. It is not important for the general usage,
but it explains how the key definition works. In above example, `Key<File>` is stored inside variable `compile`.
`compile` is also the name of the key and would be used to invoke this key from the user interface.
As seen, key's reference description is also specified during declaration and so would be the default value and
any other options or metadata.

Standard keys are defined in `wemi.Keys` object.

### Projects & Configurations
Build script will typically define one or more projects and may define any number of configurations, if needed.
Projects and configurations are the only objects capable of holding values bound to keys.
These two objects are very similar in their usage. Bound values are set during their declaration and can't be changed,
added nor removed later. Through these objects, bound values are typically only set, but not read/queried.
*Values are bound to projects and configurations, but queried through **scopes**.*

Configurations may be derived from each other. It is then said that the configuration has a parent which is
the configuration it is derived from. The parent configuration is then used during the value *querying*.

Project definition may look like this:
```kotlin
val calculator by project {
    projectGroup set {"com.example"}
    projectName set {"scientific-calculator"}
    projectVersion set {"3.14"}

    libraryDependencies add { dependency("com.example:math:6.28") }

    mainClass set { "com.example.CalculatorMainKt" }
}
```
Like the key, project and configuration (see below) is also declared using a delegate. It is also only required for
the syntax and is not important to know for general usage. Above example declares a project named `calculator`, which is
for later reference in code stored in the `calculator` variable. Rest of the example shows how the keys are bound:
When in the project or configuration initializer, write `<key> set { <value> }`. This works only in the scope of
project or configuration and the braces around value are mandatory and intentional - they signify that the `<value>` is not
evaluated immediately, but later and possibly multiple times. Keys of type `Collection` can be also set using the `add` command,
more on that in the section about *scopes*.

Configuration definition may look like this:
```kotlin
val compilingJava by configuration("Configuration used when compiling Java sources", compiling) {
	sourceRoots set { 
		val base = sourceBase.get()
		listOf(base / "kotlin", base / "java")
	}
}
```
As you can see, it is very similar to the project definition, and the similarity is internal as well.
Configuration however also holds mandatory reference description, like keys, and this example `compilingJava`
configuration is derived from (or *extends* if you will) the `compiling` configuration, which it references as a variable.
Configuration, however, does not have to be derived and in most cases won't be.

It is a soft convention to name configurations with the description of the activity in which the configuration will be used,
with the verb in *present participle*, that is, ending with *-ing*. If the configuration is not used for an activity,
any descriptive name is fine.

Standard configurations are defined in `wemi.Configurations` object.

### Scopes
Scopes are the heart of the *value querying* mechanism. They are not declared, but created at runtime, on demand.
Scopes also manage the key caching system, but that is not their primary role.
They are **composed** out of one *project* and zero or more *configurations* in some order.

This reflects in the key invocation syntax, which is used in the user interface to query/invoke key values.
(Querying and invocation is the same operation, but it makes more sense to talk about querying for keys that bind settings
and about invocation for keys that bind tasks, but this is just a convention and there is no real distinction between the two.)
The syntax is as follows:
```regexp
(<project>"/")?(<config>":")*<key>
```
Where `<project>` is a name of some defined project, `<config>` is a name of some defined configuration and `<key>`
is a name of some defined key. For example `calculator/compiling:clean` would be a query to value bound to key `clean`,
in one configuration `compiling` and in the project `calculator`. While the project part is defined in the syntax as optional, each scope **must**
always have a project. When the project part is missing, the default project of the user interface is automatically used.

A key binding defined in code, i.e. `sourceRoots set { /* this */ }`, is always executed in some implicit scope, only
through which it is possible to query values of other keys - in that same scope. It is impossible (well, at least not advisable) to escape that scope.
For example, when the key binding is being evaluated in scope `myProject/testing:compiling:` it can't drop the `compiling:`
nor any other part of the scope stack. However, it can add more configurations on top of it, by wrapping the key evaluation in
`using (configuration) {}` clause. For example, given following key binding:
```kotlin
compile set {
	val comp = using (compilingJava) { compiler.get() }
	val compOptions = compileOptions.get()
	comp.compileWithOptions(compOptions)
}
```
evaluating `myProject/foo:compile` will then evaluate, in order tasks `myProject/foo:compilingJava:compiler` and
`myProject/foo:compileOptions`. `using` clauses can be freely nested.

Note that you can push only a configuration on the stack, it is not possible to change the project with it.
To see how to evaluate the key in an arbitrary configuration, you can check [how it is implemented in CLI](src/main/kotlin/wemi/boot/CLI.kt).

### Configuration extensions
While the scope system outlined above is powerful, some things are still not possible to do easily in it.
For example, the `compile` key depends on two keys: `compiler` and `compileOptions`.
`compileOptions` is queried directly, but `compiler` is queried through `compilingJava`.
It is possible to override used `compileOptions` by overriding it in the project (when no other configuration is used).
But to override `compiler` one would have to put the override in the definition of `compilingJava` configuration, but that is not possible,
because configuration definitions are immutable. This is where configuration extensions come in to play.

To fix this, you can use `extend (configuration) {}` clause, when defining key bindings. For example, when overriding
`compilingJava:compiler` as seen in previous example, one would type:
```kotlin
compile set { /* see previous section */ }

extend (compilingJava) {
	MyJavaCompiler()
}
```
This will change the querying order when evaluating `compilingJava:compiler` to first check the new extension configuration
and only then, if the key is not bound, check the original configuration. In fact, configurations defined with the
`extend` clause, are implemented by creating a new, anonymous configuration, that extends the original configuration,
and is silently used when accessing the extended configuration in the scope in which the extension is defined.
`extend`s, like `using`s, can be freely nested for even more specific overrides.

#### Querying order
When key in scope is queried, configurations and project in the scope is searched for the value binding in a specified order.
The order is:
1. The rightmost configuration
2. The rightmost configuration's parent, if any 
3. The rightmost configuration's parent's parent, if any
4. And so on, until the base configuration of the rightmost configuration is checked
5. The second rightmost configurations and its parents, like steps 1. to 4.
6. All other configurations from right to left, including and ending with the leftmost configuration, like steps 1. to 5.
7. The project
8. Key's default value
9. Fail

When the binding is encountered, it is evaluated, returned and no more items in the order are checked.
There are multiple methods to query the value, they differ in the behavior of the step 9., when no value is found.
Remember that the value bound is not directly the value, but a function that produces the value, from the scope.

For example, in project defined by this:
```kotlin
val fruit by key<String>("The fruit key to choose the fruit, with no default value")

val fooing by configuration("This is base configuration") {
	fruit set {"Orange"}
}

val mooing by configuration("This is derived from fooing", fooing) {
	fruit set {"Pear"}
}

val booing by configuration("This configuration is also derived", mooing) {
}

val tree by project {
	fruit set {"Coconut"}
}

val bush by project {
	fruit set {"Blueberry"}
}
```
| Query                | Will evaluate to |
|----------------------|------------------|
| `tree/fruit`         | Coconut          |
| `bush/fruit`         | Blueberry          |
| `tree/fooing:fruit`         | Orange          |
| `tree/mooing:fruit`         | Pear          |
| `tree/booing:fruit`         | Pear          |
| `bush/booing:fruit`         | Pear          |
| `bush/booing:fooing:fruit`         | Orange          |
| `tree/booing:mooing:fooing:fruit`         | Orange          |

As mentioned above, there is one more feature, modifying, in our case appending, which is achieved using method `add` instead of `set`.
This is useful when you want to add more elements to a `Collection` bound to a key.
The querying for these keys is similar to how standard keys are used, but when the value bound by `set` is reached,
the order is queried from that point backwards and all `add` added elements are added to the resulting collection.
Note that there must be a point with some concrete set value, if all binding holders have only addition and none has
concrete binding, the query will fail.
Appending however is just a special case of modifying, which allows to arbitrarily modify the value returned by
querying deeper scope. To do that, use method `modify` - the previous value will be available as an argument to the key-setting function.

## Build script
All of the build definitions are inside a build script. Build script is a file, typically in a root directory of a project,
with `.wemi` or `.wemi.kt` (discouraged) extension. The conventional name is `build.wemi`, but that is not enforced.
Build script is written entirely in [Kotlin](http://kotlinlang.org) and is also compiled as such,
no pre-processing is done to modify its text. Anything that is a valid Kotlin is allowed.

The build file can declare its own library dependencies, through specially formatted lines, *directives*,
which are detected by the loader but are considered to be comments in Kotlin. These library dependencies fulfill
the work of plugins in other build systems, but there is nothing special about them, they are just standard
Java/Kotlin/JVM libraries.

### Build script directives
Directives start with `///` which **must** be at the start of the line, anywhere in the file. Space after `///` is optional.

#### Repository directive
```kotlin
/// m2-repository <repo-name> at <url>
```
Given maven2 repository will be used when searching for libraries using the *library directive*.
This is not the same as repositories of the compiled project, see `repositories` key for that.

#### Library directive
```kotlin
/// build-library <group>:<name>:<version>
```
Add library as a dependency for this build script. The library is directly available in the whole build script.
Library is searched for in the repositories specified by *repository directive*.
This is not the same as library dependencies of the compiled project, see `libraryDependencies` key for that.

## Command line user interface
WEMI features a simple interactive user interface, which is launched by default.
It accepts key queries, which are then evaluated, and a few commands. Refer to the `help` command.
To exit, use the `exit` command, or simply EOF (Ctrl-D).

## Distribution and installation
WEMI is distributed as a runnable .jar file called `wemi`, which is also a valid `.sh` file.
This file should be as small as possible, as it will be checked into the version control system of the project,
like wrappers of other build systems. This should ensure that building and updating of the build system and its
files will be as painless as possible.

The prepended `.sh` file conforms to the standard `sh` shell, for maximum compatibility.
