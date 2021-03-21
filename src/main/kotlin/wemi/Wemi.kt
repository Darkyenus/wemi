package wemi

import wemi.boot.MachineReadableFormatter
import wemi.compile.KotlinCompilerVersion
import wemi.dependency.Classifier
import wemi.dependency.TypeJar
import wemi.dependency.Dependency
import wemi.dependency.DependencyExclusion
import wemi.dependency.DependencyId
import wemi.dependency.NoClassifier
import wemi.dependency.ScopeCompile
import java.nio.file.Path
import java.util.regex.Pattern
import kotlin.reflect.KProperty0
import kotlin.reflect.jvm.isAccessible

/** Version of Kotlin used for build scripts */
val WemiKotlinVersion = KotlinCompilerVersion.values().last()

/** Immutable view into the list of loaded [Project]s. */
val AllProjects: Map<String, Project>
    get() = BuildScriptData.AllProjects

/** Immutable view into the list of loaded [Key]s. */
val AllKeys: Map<String, Key<*>>
    get() = BuildScriptData.AllKeys

/** Immutable view into the list of loaded [Configuration]s. */
val AllConfigurations: Map<String, Configuration>
    get() = BuildScriptData.AllConfigurations

/** Standard function type that is bound as value to the key in [BindingHolder] */
typealias Value<V> = EvalScope.() -> V

/** Value modifier that can be additionally bound to a key in [BindingHolder] */
typealias ValueModifier<V> = EvalScope.(original:V) -> V

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
 * @param inputKeys
 */
fun <V> key(description: String, defaultValue: V, inputKeys: Array<Pair<InputKey, InputKeyDescription>> = NO_INPUT_KEYS, prettyPrinter: PrettyPrinter<V>? = null, machineReadableFormatter: MachineReadableFormatter<V>? = null): KeyDelegate<V> {
    return KeyDelegate(description, true, defaultValue, inputKeys, prettyPrinter, machineReadableFormatter)
}

/**
 * Create a new [Key] without default value.
 *
 * @see [key] with default value for exact documentation
 */
fun <V> key(description: String, inputKeys: Array<Pair<InputKey, InputKeyDescription>> = NO_INPUT_KEYS, prettyPrinter: PrettyPrinter<V>? = null, machineReadableFormatter: MachineReadableFormatter<V>? = null): KeyDelegate<V> {
    return KeyDelegate(description, false, null, inputKeys, prettyPrinter, machineReadableFormatter)
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
 * @param axis of the new configuration, none (null) by default
 * @param initializer function which creates key value bindings for the [Configuration]
 */
fun configuration(description: String, axis: Axis? = null, initializer: Configuration.() -> Unit): ConfigurationDelegate {
    return ConfigurationDelegate(description, axis, initializer)
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

/**
 * Injects given archetype initializer into the [Archetype].
 * To be invoked in [wemi.plugin.PluginEnvironment.initialize],
 * as it can be called only on unlocked [Archetype] properties created by [archetype] delegate.
 *
 * [injectedInitializer] will be run when the receiver lazy [Archetype] is created, after the base initializer.
 *
 * @throws IllegalArgumentException when given property is not created by [archetype]
 * @throws IllegalStateException when given property's archetype is already created
 */
fun KProperty0<Archetype>.inject(injectedInitializer:Archetype.() -> Unit) {
    this.isAccessible = true // Delegate is stored as not accessible
    val delegate = this.getDelegate() as? ArchetypeDelegate
            ?: throw IllegalArgumentException("Property $this is not created by archetype()")

    delegate.inject(injectedInitializer)
}

/** Convenience Dependency creator. */
fun dependency(
    group: String, name: String, version: String,
    classifier: Classifier = NoClassifier, type: String = TypeJar, scope: wemi.dependency.DepScope = ScopeCompile,
    optional: Boolean = false, snapshotVersion: String = "",
    exclusions: List<DependencyExclusion> = emptyList(),
    dependencyManagement: List<Dependency> = emptyList()
): Dependency {
    return Dependency(
        DependencyId(group, name, version, classifier, type, snapshotVersion),
        scope,
        optional,
        exclusions,
        dependencyManagement
    )
}

/** Used to parse Gradle-like dependency specifiers. The inner pattern is based on what Maven expects, with added ~
 * due to https://github.com/jitpack/jitpack.io/issues/1145 */
private val DependencyShorthandRegex = Pattern.compile("^([A-Za-z0-9.~_-]+):([A-Za-z0-9.~_-]+):([A-Za-z0-9.~_-]+)(?::([A-Za-z0-9.~_-]+))?(?:@([A-Za-z0-9.~_-]+))?$")

/** Convenience Dependency creator using Gradle dependency notation.
 * That is: "group:name:version:classifier@type", where classifier and extension (along with their preceding : and @)
 * is optional. */
fun dependency(
    groupNameVersionClassifierType: String,
    scope: wemi.dependency.DepScope = ScopeCompile, optional: Boolean = false,
    exclusions: List<DependencyExclusion> = emptyList(),
    snapshotVersion: String = "",
    dependencyManagement: List<Dependency> = emptyList()
): Dependency {
    val match = DependencyShorthandRegex.matcher(groupNameVersionClassifierType)
    if (!match.matches())
        throw WemiException("dependency($groupNameVersionClassifierType) needs to conform to 'group:name:version:classifier@type' pattern (last two parts being optional)")

    val group = match.group(1)!!
    val name = match.group(2)!!
    val version = match.group(3)!!
    val classifier = match.group(4) ?: NoClassifier
    val type = match.group(5) ?: TypeJar

    return Dependency(DependencyId(group, name, version, classifier, type, snapshotVersion), scope, optional, exclusions, dependencyManagement)
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
 * - And more, see [Maven Central](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.jetbrains.kotlin%22)
 */
fun EvalScope.kotlinDependency(name: String): Dependency {
    return Dependency(DependencyId("org.jetbrains.kotlin", "kotlin-$name", Keys.kotlinVersion.get().string))
}

@DslMarker
annotation class WemiDsl