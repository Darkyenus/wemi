package wemi

import wemi.compile.KotlinCompilerVersion
import wemi.dependency.Classifier
import wemi.dependency.Dependency
import wemi.dependency.DependencyExclusion
import wemi.dependency.DependencyId
import wemi.dependency.NoClassifier
import wemi.dependency.ScopeCompile
import wemi.dependency.TypeJar
import wemi.keys.kotlinVersion
import java.util.*
import java.util.regex.Pattern
import kotlin.reflect.KProperty0
import kotlin.reflect.jvm.isAccessible

/** Version of Kotlin used for build scripts */
val WemiKotlinVersion = KotlinCompilerVersion.values().last()

internal val AllProjectsMutable: MutableMap<String, Project> = Collections.synchronizedMap(mutableMapOf<String, Project>())
internal val AllKeysMutable: MutableMap<String, Key<*>> = Collections.synchronizedMap(mutableMapOf<String, Key<*>>())
internal val AllConfigurationsMutable: MutableMap<String, Configuration> = Collections.synchronizedMap(mutableMapOf<String, Configuration>())
internal val AllCommandsMutable: MutableMap<String, Command<*>> = Collections.synchronizedMap(mutableMapOf<String, Command<*>>())

/** Immutable view into the list of loaded [Project]s. */
val AllProjects: Map<String, Project>
    get() = AllProjectsMutable

/** Immutable view into the list of loaded [Key]s. */
val AllKeys: Map<String, Key<*>>
    get() = AllKeysMutable

/** Immutable view into the list of loaded [Configuration]s. */
val AllConfigurations: Map<String, Configuration>
    get() = AllConfigurationsMutable

/** Immutable view into the list of loaded [Configuration]s. */
val AllCommands: Map<String, Command<*>>
    get() = AllCommandsMutable

/** Standard function type that is bound as value to the key in [BindingHolder] */
typealias Value<V> = EvalScope.() -> V

/** Value modifier that can be additionally bound to a key in [BindingHolder] */
typealias ValueModifier<V> = EvalScope.(original:V) -> V

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
    val delegate = this.getDelegate() as? archetype
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
    return Dependency(DependencyId("org.jetbrains.kotlin", "kotlin-$name", kotlinVersion.get().string))
}

@DslMarker
annotation class WemiDsl