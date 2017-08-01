@file:Suppress("unused")

import org.slf4j.LoggerFactory
import wemi.WemiException
import java.io.File
import java.util.*
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

private val LOG = LoggerFactory.getLogger("Wemi")

/** Internal structure holding all loaded build script data. */
internal object BuildScriptData {
    val AllProjects: MutableMap<String, Project> = Collections.synchronizedMap(mutableMapOf<String, Project>())
    val AllKeys: MutableMap<String, Key<Any>> = Collections.synchronizedMap(mutableMapOf<String, Key<Any>>())
    val AllConfigurations: MutableMap<String, Configuration> = Collections.synchronizedMap(mutableMapOf<String, Configuration>())
}

abstract class ConfigurationHolder(val parent: ConfigurationHolder?) {
    abstract val name:String

    private val keyValueBindingLock = Object()
    private val keyValueBinding = HashMap<Key<Any>, Any>()

    infix fun <Value> Key<Value>.set(value:Value) {
        @Suppress("UNCHECKED_CAST")
        synchronized(keyValueBindingLock) {
            keyValueBinding.put(this as Key<Any>, value as Any)
        }
    }

    /** Return the value bound to this key in this scope.
     * Throws exception if no value set. */
    fun <Value> Key<Value>.get():Value {
        var holder:ConfigurationHolder = this@ConfigurationHolder
        synchronized(keyValueBindingLock) {
            while (true) {
                @Suppress("UNCHECKED_CAST")
                if (holder.keyValueBinding.containsKey(this as Key<Any>)) {
                    return holder.keyValueBinding[this] as Value
                }

                holder = holder.parent ?: break
            }
        }

        // We have to check default value
        if (hasDefaultValue) {
            @Suppress("UNCHECKED_CAST")
            return defaultValue as Value
        } else {
            throw WemiException("$name is not assigned in ${this@ConfigurationHolder.name}")
        }
    }

    /** Return the value bound to this key in this scope.
     * Returns [unset] if no value set.
     * @param acceptDefault if key has default value, return that, otherwise return [unset] */
    fun <Value> Key<Value>.getOrElse(unset:Value, acceptDefault:Boolean = true):Value {
        var holder:ConfigurationHolder = this@ConfigurationHolder
        synchronized(keyValueBindingLock) {
            while (true) {
                @Suppress("UNCHECKED_CAST")
                if (holder.keyValueBinding.containsKey(this as Key<Any>)) {
                    return holder.keyValueBinding[this] as Value
                }

                holder = holder.parent ?: break
            }
        }

        // We have to check default value
        @Suppress("UNCHECKED_CAST")
        if (acceptDefault && this.hasDefaultValue) {
            return this.defaultValue as Value
        } else {
            return unset
        }
    }

    /** Return the value bound to this key in this scope.
     * Returns `null` if no value set. */
    fun <Value> Key<Value>.getOrNull():Value? {
        var holder:ConfigurationHolder = this@ConfigurationHolder
        synchronized(keyValueBindingLock) {
            while (true) {
                @Suppress("UNCHECKED_CAST")
                if (holder.keyValueBinding.containsKey(this as Key<Any>)) {
                    return holder.keyValueBinding[this] as Value
                }

                holder = holder.parent ?: break
            }
        }
        return this.defaultValue
    }
}

class ProjectDelegate<This> internal constructor(
        private val projectRoot: File,
        private val initializer: Project.() -> Unit
) : ReadOnlyProperty<This, Project> {

    private lateinit var project: Project

    operator fun provideDelegate(thisRef: This, property: KProperty<*>): ProjectDelegate<This> {
        this.project = Project(property.name, projectRoot.absoluteFile)
        synchronized(BuildScriptData.AllProjects) {
            for ((_, project) in BuildScriptData.AllProjects) {
                if (project.name == this.project.name) {
                    throw WemiException("Project named '${this.project.name}' already exists (at ${project.projectRoot})")
                }
                if (project.projectRoot == this.project.projectRoot) {
                    LOG.warn("Project ${this.project.name} is at the same location as project ${project.name}")
                }
            }
            BuildScriptData.AllProjects.put(project.name, project)
        }
        this.project.initializer()
        return this
    }

    override fun getValue(thisRef: This, property: KProperty<*>): Project = project
}

class KeyDelegate<This, Value> internal constructor(
        private val description:String,
        private val hasDefaultValue:Boolean,
        private val defaultValue:Value?) : ReadOnlyProperty<This, Key<Value>> {

    private lateinit var key:Key<Value>

    operator fun provideDelegate(thisRef: This, property: KProperty<*>): KeyDelegate<This, Value> {
        this.key = Key(property.name, description, hasDefaultValue, defaultValue)
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

    override fun getValue(thisRef: This, property: KProperty<*>): Key<Value> = key
}

class ConfigurationDelegate<This> internal constructor(
        private val description: String,
        private val parent: ConfigurationHolder?) : ReadOnlyProperty<This, Configuration> {

    private lateinit var configuration:Configuration

    operator fun provideDelegate(thisRef: This, property: KProperty<*>): ConfigurationDelegate<This> {
        this.configuration = Configuration(property.name, description, parent)
        synchronized(BuildScriptData.AllConfigurations) {
            val existing = BuildScriptData.AllConfigurations[configuration.name]
            if (existing != null) {
                throw WemiException("Configuration ${configuration.name} already exists (desc: '${existing.description}')")
            }

            BuildScriptData.AllConfigurations.put(configuration.name, configuration)
        }
        return this
    }

    override fun getValue(thisRef: This, property: KProperty<*>): Configuration = configuration

}