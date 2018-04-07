package wemi

import wemi.compile.KotlinCompilerVersion
import wemi.dependency.*
import java.net.URL
import java.nio.file.Path
import kotlin.reflect.KProperty0

/** Version of Wemi build system */
const val WemiVersion = "0.4-SNAPSHOT"

/** Version of Kotlin used for build scripts */
val WemiKotlinVersion = KotlinCompilerVersion.Version1_2_21

/** Immutable view into the list of loaded projects. */
val AllProjects: Map<String, Project>
    get() = BuildScriptData.AllProjects

/** Immutable view into the list of loaded keys. */
val AllKeys: Map<String, Key<*>>
    get() = BuildScriptData.AllKeys

/** Immutable view into the list of loaded keys. */
val AllConfigurations: Map<String, Configuration>
    get() = BuildScriptData.AllConfigurations

/** Standard function type that is bound as value to the key in [BindingHolder] */
typealias BoundKeyValue<Value> = Scope.() -> Value

/** Value modifier that can be additionally bound to a key in [BindingHolder] */
typealias BoundKeyValueModifier<Value> = Scope.(Value) -> Value

/**
 * Create a new [Project].
 * To be used as a variable delegate target, example:
 * ```
 * val myProject by project(path(".")) {
 *      // Init
 *      projectGroup set {"com.example.my.group"}
 * }
 * ```
 * These variables must be declared in the file-level scope of the build script.
 * Creating projects elsewhere will lead to an undefined behavior.
 *
 * @param projectRoot path from which all other paths in the project are derived from (null = not set)
 * @param initializer function which creates key value bindings for the [Project]
 */
fun project(projectRoot: Path?, vararg archetypes: Archetype, initializer: Project.() -> Unit): ProjectDelegate {
    return ProjectDelegate(projectRoot, archetypes, initializer)
}

private val NO_INPUT_KEYS = emptyArray<Pair<InputKey, InputKeyDescription>>()

/**
 * Create a new [Key] with a default value
 * To be used as a variable delegate target, example:
 * ```
 * val mySetting by key<String>("Key to store my setting")
 * ```
 * These variables must be declared in the file-level scope or in an `object`.
 *
 * Two keys must not share the same name. Key name is derived from the name of the variable this
 * key delegate is created by. (Key in example would be called `mySetting`.)
 *
 * @param description of the key, to be shown in help UI
 * @param defaultValue of the key, used when no binding exists. NOTE: Default value is NOT LAZY like standard binding!
 *          This same instance will be returned on each return, in every scope, so it MUST be immutable!
 *          Recommended to be used only for keys of [Collection]s with empty immutable default.
 * @param cacheMode describes how the key's evaluation result should be cached, null if it should not be cached
 * @param inputKeys
 */
fun <Value> key(description: String, defaultValue: Value, cacheMode: KeyCacheMode<Value>? = CACHE_FOR_RUN, inputKeys: Array<Pair<InputKey, InputKeyDescription>> = NO_INPUT_KEYS, prettyPrinter: ((Value) -> CharSequence)? = null): KeyDelegate<Value> {
    return KeyDelegate(description, true, defaultValue, cacheMode, inputKeys, prettyPrinter)
}

/**
 * Create a new [Key] without default value.
 *
 * @see [key] with default value for exact documentation
 */
fun <Value> key(description: String, cacheMode: KeyCacheMode<Value>? = CACHE_FOR_RUN, inputKeys: Array<Pair<InputKey, InputKeyDescription>> = NO_INPUT_KEYS, prettyPrinter: ((Value) -> CharSequence)? = null): KeyDelegate<Value> {
    return KeyDelegate(description, false, null, cacheMode, inputKeys, prettyPrinter)
}

/**
 * Create a new [Configuration].
 * To be used as a variable delegate target, example:
 * ```
 * val myConfiguration by configuration("Configuration for my stuff") {
 *      // Set what the configuration will change
 *      libraryDependencies add { dependency("com.example:library:1.0") }
 * }
 * ```
 * These variables must be declared in the file-level scope of the build script!
 * Creating configurations elsewhere will lead to an undefined behavior.
 *
 * Two configurations must not share the same name. Configuration name is derived from the name of the variable this
 * configuration delegate is created by. (Configuration in example would be called `myConfiguration`.)
 *
 * @param description of the configuration, to be shown in help UI
 * @param parent of the new configuration, none (null) by default
 * @param initializer function which creates key value bindings for the [Configuration]
 */
fun configuration(description: String, parent: Configuration? = null, initializer: Configuration.() -> Unit): ConfigurationDelegate {
    return ConfigurationDelegate(description, parent, initializer)
}

/** Convenience Dependency creator.
 * Creates [Dependency] with default exclusions. */
fun dependency(group: String, name: String, version: String, preferredRepository: Repository?, vararg attributes: Pair<DependencyAttribute, String>): Dependency {
    return Dependency(DependencyId(group, name, version, preferredRepository, attributes = mapOf(*attributes)))
}

/**
 * Create a new [Archetype].
 * To be used as a variable delegate target, example:
 * ```
 * val myArchetype by archetype {
 *      // Set what the archetype will set
 *      compile set { /* Custom compile process, for example. */ }
 * }
 * ```
 *
 * Two archetypes should not share the same name. Archetype name is derived from the name of the variable this
 * archetype delegate is created by. (Archetype in example would be called `myArchetype`.)
 *
 * @param parent property which holds the parent archetype from which this one inherits its keys, similar to configuration (This is a property instead of [Archetype] directly, because of the need for lazy evaluation).
 * @param initializer function which creates key value bindings for the [Archetype]. Executed lazily.
 */
fun archetype(parent: KProperty0<Archetype>? = null, initializer: Archetype.() -> Unit):ArchetypeDelegate {
        return ArchetypeDelegate(parent, initializer)
}

/** Convenience Dependency creator.
 * @param groupNameVersion Gradle-like semicolon separated group, name and version of the dependency.
 *          If the amount of ':'s isn't exactly 2, or one of the triplet is empty, runtime exception is thrown.
 */
fun dependency(groupNameVersion: String, preferredRepository: Repository?, vararg attributes: Pair<DependencyAttribute, String>): Dependency {
    val first = groupNameVersion.indexOf(':')
    val second = groupNameVersion.indexOf(':', startIndex = maxOf(first + 1, 0))
    val third = groupNameVersion.indexOf(':', startIndex = maxOf(second + 1, 0))
    if (first < 0 || second < 0 || third >= 0) {
        throw WemiException("groupNameVersion must contain exactly two semicolons: '$groupNameVersion'")
    }
    if (first == 0 || second <= first + 1 || second + 1 == groupNameVersion.length) {
        throw WemiException("groupNameVersion must not have empty elements: '$groupNameVersion'")
    }

    val group = groupNameVersion.substring(0, first)
    val name = groupNameVersion.substring(first + 1, second)
    val version = groupNameVersion.substring(second + 1)

    return dependency(group, name, version, preferredRepository, *attributes)
}

/**
 * Convenience [Repository.M2] creator.
 *
 * If the [url] is local, no cache is used. If it is not local (that is, not `file:`),
 * [LocalM2Repository] is used as cache.
 */
fun repository(name: String, url: String, checksum: Repository.M2.Checksum = Repository.M2.Checksum.SHA1): Repository.M2 {
    val usedUrl = URL(url)
    return Repository.M2(name,
            usedUrl,
            if (usedUrl.protocol.equals("file", ignoreCase = true)) null else LocalM2Repository,
            checksum)
}

/**
 * Convenience creator of dependencies on kotlin libraries.
 *
 * Returns dependency on `org.jetbrains.kotlin:kotlin-$name:$Keys.kotlinVersion}`.
 *
 * Example possible `name` values:
 * - `stdlib` standard Kotlin library
 * - `reflect` reflection support library
 * - `stdlib-jdk8` standard library extension for Java 8 JVM
 * - And more, see http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.jetbrains.kotlin%22
 */
fun Scope.kotlinDependency(name: String): Dependency {
    return Dependency(DependencyId("org.jetbrains.kotlin", "kotlin-" + name, Keys.kotlinVersion.get().string, MavenCentral))
}
