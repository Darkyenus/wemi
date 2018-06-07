@file:Suppress("unused")

package wemi

import org.slf4j.LoggerFactory
import wemi.util.exists
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty0

private val LOG = LoggerFactory.getLogger("Wemi")

/** Internal structure holding all loaded build script data. */
internal object BuildScriptData {
    val AllProjects: MutableMap<String, Project> = Collections.synchronizedMap(mutableMapOf<String, Project>())
    val AllKeys: MutableMap<String, Key<Any>> = Collections.synchronizedMap(mutableMapOf<String, Key<Any>>())
    val AllConfigurations: MutableMap<String, Configuration> = Collections.synchronizedMap(mutableMapOf<String, Configuration>())
}

/**
 * Delegate used when declaring a new [Project].
 *
 * Mostly boilerplate, but takes care of creating, initializing and registering the [Project].
 */
class ProjectDelegate internal constructor(
        private val projectRoot: Path?,
        private val archetypes: Array<out Archetype>,
        private val initializer: Project.() -> Unit
) : ReadOnlyProperty<Any?, Project> {

    private lateinit var project: Project

    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ProjectDelegate {
        this.project = createProject(property.name, projectRoot, *archetypes, checkRootUnique = true, initializer = initializer)
        return this
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): Project = project
}

/**
 * ADVANCED USERS ONLY!!! Use [ProjectDelegate] instead for most uses.
 *
 * Create project dynamically, like using the [ProjectDelegate], but without the need to create a separate variable
 * for each one.
 *
 * @param name of the project, must be unique
 * @param root of the project
 * @param archetypes to apply, primary first
 * @param checkRootUnique if true, check if no other project is in the [root] and warn if so
 * @param initializer to populate the project's [BindingHolder] with bindings
 */
fun createProject(name:String, root:Path?, vararg archetypes:Archetype, checkRootUnique:Boolean = true, initializer: Project.() -> Unit):Project {
    val usedRoot = root?.toAbsolutePath()
    if (usedRoot != null && !usedRoot.exists()) {
        Files.createDirectories(usedRoot)
    }

    if (archetypes.isEmpty()) {
        LOG.warn("Project {} is being created without any archetype. Such project won't have any functionality.", name)
    } else {
        var baseArchetypeCount = 0
        for (a in archetypes) {
            var archetype = a
            while (true) {
                if (archetype === Archetypes.Base) {
                    baseArchetypeCount++
                }
                archetype = archetype.parent ?: break
            }
        }

        if (baseArchetypeCount == 0) {
            LOG.warn("Project {} is being created without any primary archetype.", name)
        } else if (baseArchetypeCount > 1) {
            LOG.warn("Project {} is being created with {} primary archetypes, there should be only one.", name, baseArchetypeCount)
        }
    }

    val project = Project(name, usedRoot, archetypes)
    // Not added to AllProjects, because build script project may be reloaded multiple times and only final version will be added
    synchronized(BuildScriptData.AllProjects) {
        for ((_, otherProject) in BuildScriptData.AllProjects) {
            if (otherProject.name == name) {
                if (otherProject.projectRoot == null) {
                    throw WemiException("Project named '$name' already exists (without root)")
                } else {
                    throw WemiException("Project named '$name' already exists (at ${otherProject.projectRoot})")
                }
            }
            if (checkRootUnique && otherProject.projectRoot != null && Files.isSameFile(otherProject.projectRoot, usedRoot)) {
                LOG.warn("Project $name is at the same location as project ${otherProject.name}")
            }
        }
        BuildScriptData.AllProjects.put(project.name, project)
    }
    project.apply {
        Keys.projectName set { name }
        if (usedRoot != null) {
            Keys.projectRoot set { usedRoot }
        }

        initializer()
        locked = true
    }

    return project
}

/**
 * Delegate used when declaring a new [Key].
 *
 * Mostly boilerplate, but takes care of creating and registering the [Key].
 */
class KeyDelegate<Value> internal constructor(
        private val description: String,
        private val hasDefaultValue: Boolean,
        private val defaultValue: Value?,
        private val cacheMode: KeyCacheMode<Value>?,
        private val inputKeys: Array<Pair<InputKey, InputKeyDescription>>,
        private val prettyPrinter: ((Value) -> CharSequence)?) : ReadOnlyProperty<Any?, Key<Value>> {

    private lateinit var key: Key<Value>

    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): KeyDelegate<Value> {
        this.key = Key(property.name, description, hasDefaultValue, defaultValue, cacheMode, inputKeys, prettyPrinter)
        @Suppress("UNCHECKED_CAST")
        synchronized(BuildScriptData.AllKeys) {
            val existing = BuildScriptData.AllKeys[this.key.name]
            if (existing != null) {
                throw WemiException("Key ${key.name} already exists (desc: '${existing.description}')")
            }
            BuildScriptData.AllKeys.put(key.name, key as Key<Any>)
        }
        return this
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): Key<Value> = key
}

/**
 * Delegate used when declaring a new [Configuration].
 *
 * Mostly boilerplate, but takes care of creating, initializing and registering the [Configuration].
 */
class ConfigurationDelegate internal constructor(
        private val description: String,
        private val parent: Configuration?,
        private val initializer: Configuration.() -> Unit) : ReadOnlyProperty<Any?, Configuration> {

    private lateinit var configuration: Configuration

    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ConfigurationDelegate {
        this.configuration = createConfiguration(property.name, description, parent, initializer)
        return this
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): Configuration = configuration
}

fun createConfiguration(name:String, description: String, parent: Configuration?, initializer: Configuration.() -> Unit):Configuration {
    val configuration = Configuration(name, description, parent)
    synchronized(BuildScriptData.AllConfigurations) {
        val existing = BuildScriptData.AllConfigurations[configuration.name]
        if (existing != null) {
            throw WemiException("Configuration ${configuration.name} already exists (desc: '${existing.description}')")
        }

        BuildScriptData.AllConfigurations.put(configuration.name, configuration)
    }
    configuration.initializer()
    configuration.locked = true

    return configuration
}

/**
 * Delegate used when creating new [Project] [Archetype].
 */
class ArchetypeDelegate internal constructor(
        private val parent: KProperty0<Archetype>?,
        private var initializer: (Archetype.() -> Unit)?
) : ReadOnlyProperty<Any?, Archetype> {

    private var archetype:Archetype? = null

    /**
     * @see wemi.inject for usage location
     */
    internal fun inject(additionalInitializer:Archetype.() -> Unit) {
        if (this.archetype != null) {
            throw IllegalStateException("$archetype is already initialized")
        }

        val baseInitializer = this.initializer!!
        initializer = {
            baseInitializer()
            additionalInitializer()
        }
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): Archetype {
        var archetype: Archetype? = archetype
        if (archetype != null) {
            return archetype
        }

        val initializer = this.initializer!!
        this.initializer = null // Free to GC

        archetype = Archetype(property.name, parent?.get())
        archetype.initializer()
        archetype.locked = true
        this.archetype = archetype
        return archetype
    }
}
