@file:Suppress("unused")

package wemi

import org.slf4j.LoggerFactory
import wemi.boot.MachineReadableFormatter
import wemi.util.exists
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty0

private val LOG = LoggerFactory.getLogger("Wemi")

private typealias BindingHolderInitializer = () -> Unit

/** Internal structure holding all loaded build script data. */
internal object BuildScriptData {
    val AllProjects: MutableMap<String, Project> = Collections.synchronizedMap(mutableMapOf<String, Project>())
    val AllKeys: MutableMap<String, Key<*>> = Collections.synchronizedMap(mutableMapOf<String, Key<*>>())
    val AllConfigurations: MutableMap<String, Configuration> = Collections.synchronizedMap(mutableMapOf<String, Configuration>())

    /**
     * List of lazy initializers.
     * Projects and configurations are initialized lazily, to allow cyclic dependencies.
     *
     * When null, startup initialization has already been done and further initializations should happen eagerly.
     */
    var PendingInitializers:ArrayList<BindingHolderInitializer>? = ArrayList()
        private set

    fun flushInitializers() {
        while (true) {
            val initializerList = PendingInitializers ?: return
            if (initializerList.isEmpty()) {
                break
            }
            PendingInitializers = ArrayList()
            LOG.debug("Flushing initializers")
            for (function in initializerList) {
                function()
            }
        }
        PendingInitializers = null
    }
}

/**
 * Delegate used when declaring a new [Project].
 *
 * Mostly boilerplate, but takes care of creating, initializing and registering the [Project].
 */
class ProjectDelegate internal constructor(
        private val projectRoot: Path?,
        private val archetypes: Array<out Archetype>,
        private var initializer: (Project.() -> Unit)?
) : ReadOnlyProperty<Any?, Project>, BindingHolderInitializer {

    private lateinit var project: Project

    override fun invoke() {
        val project = project
        project.locked = false
        try {
            initializer!!.invoke(project)
        } finally {
            project.locked = true
            initializer = null
        }
    }

    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ProjectDelegate {
        this.project = createProject(property.name, projectRoot, *archetypes, checkRootUnique = true, initializer = null)
        BuildScriptData.PendingInitializers?.add(this) ?: this()
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
 * @param name of the project, must be unique and valid Java-like identifier
 * @param root of the project
 * @param archetypes to apply, primary first
 * @param checkRootUnique if true, check if no other project is in the [root] and warn if so
 * @param initializer to populate the project's [BindingHolder] with bindings (null is used only internally)
 */
fun createProject(name:String, root:Path?, vararg archetypes:Archetype, checkRootUnique:Boolean = true, initializer: (Project.() -> Unit)?):Project {
    val usedRoot = root?.toAbsolutePath()
    if (usedRoot != null && !usedRoot.exists()) {
        Files.createDirectories(usedRoot)
    }

    if (archetypes.isEmpty()) {
        LOG.warn("Project {} is being created without any archetype. Such project won't have any built-in functionality.", name)
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
            if (checkRootUnique && otherProject.projectRoot != null && usedRoot != null && Files.isSameFile(otherProject.projectRoot, usedRoot)) {
                LOG.debug("Project $name is at the same location as project ${otherProject.name}")
            }
        }
        BuildScriptData.AllProjects.put(project.name, project)
    }
    project.apply {
        Keys.projectName set Static(name)
        if (usedRoot != null) {
            Keys.projectRoot set Static(usedRoot)
        }

        initializer?.invoke(this)
        locked = true
    }

    return project
}

/**
 * Delegate used when declaring a new [Key].
 *
 * Mostly boilerplate, but takes care of creating and registering the [Key].
 */
class KeyDelegate<V> internal constructor(
        private val description: String,
        private val hasDefaultValue: Boolean,
        private val defaultValue: V?,
        private val inputKeys: Array<Pair<InputKey, InputKeyDescription>>,
        private val prettyPrinter: PrettyPrinter<V>?,
        private val machineReadableFormatter: MachineReadableFormatter<V>?) : ReadOnlyProperty<Any?, Key<V>> {

    private lateinit var key: Key<V>

    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): KeyDelegate<V> {
        this.key = synchronized(BuildScriptData.AllKeys) {
            var keyName = property.name
            var existing = BuildScriptData.AllKeys[keyName]
            while (existing != null) /* no looping, just break support */ {
                if (thisRef != null) {
                    val simpleName = thisRef.javaClass.simpleName
                    val fullName = thisRef.javaClass.name
                    val oldName = keyName
                    keyName = (if (simpleName.isNullOrEmpty()) fullName else simpleName).replace('.', '_')+"_"+property.name
                    existing = BuildScriptData.AllKeys[keyName]
                    if (existing == null) {
                        LOG.warn("Key {} already exists, renaming duplicate to {}", oldName, keyName)
                        break
                    }
                }
                throw WemiException("Key $keyName already exists (desc: '${existing.description}')")
            }
            val key = Key(property.name, description, hasDefaultValue, defaultValue, inputKeys, prettyPrinter, machineReadableFormatter)
            BuildScriptData.AllKeys[key.name] = key
            key
        }
        return this
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): Key<V> = key
}

/**
 * Delegate used when declaring a new [Configuration].
 *
 * Mostly boilerplate, but takes care of creating, initializing and registering the [Configuration].
 */
class ConfigurationDelegate internal constructor(
        private val description: String,
        private val axis: Axis?,
        private var initializer: (Configuration.() -> Unit)?)
    : ReadOnlyProperty<Any?, Configuration>, BindingHolderInitializer {

    private lateinit var configuration: Configuration

    override fun invoke() {
        val configuration = configuration
        configuration.locked = false
        try {
            initializer!!.invoke(configuration)
        } finally {
            configuration.locked = true
            initializer = null
        }
    }

    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ConfigurationDelegate {
        this.configuration = createConfiguration(property.name, description, axis, null)
        BuildScriptData.PendingInitializers?.add(this) ?: this()
        return this
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): Configuration = configuration
}

/**
 * ADVANCED USERS ONLY!!! Use [ConfigurationDelegate] instead for most uses.
 *
 * Create configuration dynamically, like using the [ConfigurationDelegate], but without the need to create a separate variable
 * for each one.
 *
 * @param name of the configuration, must be unique and valid Java-like identifier
 * @param description of the configuration
 * @param axis of the configuration
 * @param initializer to populate the configuration's [BindingHolder] with bindings (null is used only internally)
 */
fun createConfiguration(name:String, description: String, axis: Axis?, initializer: (Configuration.() -> Unit)?):Configuration {
    val configuration = Configuration(name, description, axis ?: Axis(name))
    synchronized(BuildScriptData.AllConfigurations) {
        val existing = BuildScriptData.AllConfigurations[configuration.name]
        if (existing != null) {
            throw WemiException("Configuration ${configuration.name} already exists (desc: '${existing.description}')")
        }

        BuildScriptData.AllConfigurations.put(configuration.name, configuration)
    }
    initializer?.invoke(configuration)
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
