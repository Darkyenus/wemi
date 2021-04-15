@file:Suppress("unused", "ClassName")

package wemi

import org.slf4j.LoggerFactory
import wemi.boot.MachineReadableFormatter
import wemi.util.exists
import wemi.util.path
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
    val AllCommands: MutableMap<String, Command<*>> = Collections.synchronizedMap(mutableMapOf<String, Command<*>>())

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
 * Delegate class takes care of creating, initializing and registering the [Project].
 *
 * @param projectRoot path from which all other paths in the project are derived from (null = not set)
 * @param initializer function which creates key value bindings for the [Project]
 */
class ProjectDelegate(
    private val projectRoot: Path? = path("."),
    private vararg val archetypes: Archetype = Archetypes.DefaultArchetypes,
    private var initializer: (Project.() -> Unit)?
) : ReadOnlyProperty<Any?, Project>, BindingHolderInitializer {

    constructor(vararg archetypes: Archetype = Archetypes.DefaultArchetypes, initializer: (Project.() -> Unit)?)
    : this(projectRoot = path("."), archetypes = *archetypes, initializer = initializer)

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
 * ADVANCED USERS ONLY!!! Use [project] instead for most uses.
 *
 * Create project dynamically, like using the [project], but without the need to create a separate variable
 * for each one.
 *
 * @param name of the project, must be unique and valid Java-like identifier
 * @param root of the project
 * @param archetypes to apply, primary first
 * @param checkRootUnique if true, check if no other project is in the [root] and warn if so
 * @param initializer to populate the project's [BindingHolder] with bindings (null is used only internally)
 */
fun createProject(name:String, root:Path?, vararg archetypes: Archetype, checkRootUnique:Boolean = true, initializer: (Project.() -> Unit)?): Project {
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

private val NO_INPUT_KEYS = emptyArray<Pair<InputKey, InputKeyDescription>>()

/**
 * Delegate used when declaring a new [Key].
 *
 * Mostly boilerplate, but takes care of creating and registering the [Key].
 */
class KeyDelegate<V> private constructor(
    private val description: String,
    private val hasDefaultValue: Boolean,
    private val defaultValue: V?,
    private val inputKeys: Array<Pair<InputKey, InputKeyDescription>>,
    private val prettyPrinter: PrettyPrinter<V>?,
    private val machineReadableFormatter: MachineReadableFormatter<V>?) : ReadOnlyProperty<Any?, Key<V>> {

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
    constructor(description: String, defaultValue: V, inputKeys: Array<Pair<InputKey, InputKeyDescription>> = NO_INPUT_KEYS, prettyPrinter: PrettyPrinter<V>? = null, machineReadableFormatter: MachineReadableFormatter<V>? = null)
            : this(description, true, defaultValue, inputKeys, prettyPrinter, machineReadableFormatter)

    /**
     * Create a new [Key] without default value.
     *
     * @see [key] with default value for exact documentation
     */
    constructor(description: String, inputKeys: Array<Pair<InputKey, InputKeyDescription>> = NO_INPUT_KEYS, prettyPrinter: PrettyPrinter<V>? = null, machineReadableFormatter: MachineReadableFormatter<V>? = null)
            : this(description, false, null, inputKeys, prettyPrinter, machineReadableFormatter)

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
 * Delegate takes care of creating, initializing and registering the [Configuration].
 *
 * @param description of the configuration, to be shown in help UI
 * @param axis of the new configuration, none (null) by default
 * @param initializer function which creates key value bindings for the [Configuration]
 */
class ConfigurationDelegate(
    private val description: String,
    private val axis: Axis? = null,
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
 * ADVANCED USERS ONLY!!! Use [configuration] instead for most uses.
 *
 * Create configuration dynamically, like using the [configuration], but without the need to create a separate variable
 * for each one.
 *
 * @param name of the configuration, must be unique and valid Java-like identifier
 * @param description of the configuration
 * @param axis of the configuration
 * @param initializer to populate the configuration's [BindingHolder] with bindings (null is used only internally)
 */
fun createConfiguration(name:String, description: String, axis: Axis?, initializer: (Configuration.() -> Unit)?): Configuration {
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
class ArchetypeDelegate(
    private val parent: KProperty0<Archetype>? = null,
    private var initializer: (Archetype.() -> Unit)?
) : ReadOnlyProperty<Any?, Archetype> {

    private var archetype: Archetype? = null

    /**
     * @see wemi.inject for usage location
     */
    internal fun inject(additionalInitializer: Archetype.() -> Unit) {
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

/**
 * Create a new [Command].
 * To be used as a variable delegate target, example:
 * ```
 * val myCommand by command<Unit>("Description", {
 *      val param = read("someParam")
 *      someKey set { param }
 * }) {
 *      someOtherKey.get()
 * }
 * ```
 *
 * Two commands must not share the same name. Command name is derived from the name of the variable this
 * delegate is created by. (Command in example would be called `myCommand`.)
 *
 * This delegate object takes care of creating, initializing and registering the [Command].
 *
 * @param description of the command
 * @param setupBinding called to initialize the binding using given input
 * @param execute to execute the command - run in a scope whose top binding is the binding previously passed to setupBinding
 */
class CommandDelegate<T>(
    private val description: String,
    private val setupBinding: CommandBindingHolder.() -> Unit,
    private val execute: Value<T>
)
    : ReadOnlyProperty<Any?, Command<T>> {

    private lateinit var command: Command<T>

    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): CommandDelegate<T> {
        this.command = createCommand(property.name, description, setupBinding, execute)
        return this
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): Command<T> = command
}

/**
 * ADVANCED USERS ONLY!!! Use [command] instead for most uses.
 *
 * Create command dynamically, like using the [command], but without the need to create a separate variable
 * for each one.
 *
 * @param name of the command, must be unique and valid Java-like identifier
 * @param description of the command
 * @param setupBinding called to initialize the binding using given input
 * @param execute to execute the command - run in a scope whose top binding is the binding previously passed to setupBinding
 */
fun <T> createCommand(name:String, description: String, setupBinding: CommandBindingHolder.() -> Unit, execute: Value<T>): Command<T> {
    val command = Command(name, description, setupBinding, execute)
    synchronized(BuildScriptData.AllCommands) {
        val existing = BuildScriptData.AllCommands[command.name]
        if (existing != null) {
            throw WemiException("Command ${command.name} already exists (desc: '${existing.description}')")
        }

        BuildScriptData.AllCommands.put(command.name, command)
    }

    return command
}