@file:Suppress("unused")

package wemi

import org.slf4j.LoggerFactory
import wemi.KeyDefaults.BuildDirectory
import wemi.KeyDefaults.Classpath
import wemi.KeyDefaults.Compile
import wemi.KeyDefaults.CompileOutputFile
import wemi.KeyDefaults.CompilerOptions
import wemi.KeyDefaults.JavaExecutable
import wemi.KeyDefaults.JavaHome
import wemi.KeyDefaults.LibraryDependencies
import wemi.KeyDefaults.Repositories
import wemi.KeyDefaults.Run
import wemi.KeyDefaults.RunArguments
import wemi.KeyDefaults.RunDirectory
import wemi.KeyDefaults.RunOptions
import wemi.KeyDefaults.SourceDirectories
import wemi.KeyDefaults.SourceExtensions
import wemi.KeyDefaults.SourceFiles
import java.io.File
import java.util.*
import kotlin.collections.HashMap
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

private val LOG = LoggerFactory.getLogger("Wemi")

/** Internal structure holding all loaded build script data. */
internal object BuildScriptData {
    val AllProjects: MutableMap<String, Project> = Collections.synchronizedMap(mutableMapOf<String, Project>())
    val AllKeys: MutableMap<String, Key<Any>> = Collections.synchronizedMap(mutableMapOf<String, Key<Any>>())
    val AllConfigurations: MutableMap<String, Configuration> = Collections.synchronizedMap(mutableMapOf<String, Configuration>())
}

typealias LazyKeyValue<Value> = ConfigurationHolder.() -> Value

abstract class ConfigurationHolder(val parent: ConfigurationHolder?) {
    abstract val name:String

    private val keyValueBinding = HashMap<Key<*>, LazyKeyValue<Any?>>()

    infix fun <Value> Key<Value>.set(lazyValue:LazyKeyValue<Value>) {
        @Suppress("UNCHECKED_CAST")
        synchronized(keyValueBinding) {
            keyValueBinding.put(this as Key<Any>, lazyValue as LazyKeyValue<Any?>)
        }
    }

    operator fun <Value> Key<Collection<Value>>.plusAssign(lazyValue:LazyKeyValue<Value>) {
        @Suppress("UNCHECKED_CAST")
        synchronized(keyValueBinding) {
            val previous = keyValueBinding[this] as LazyKeyValue<Collection<Value>>?

            val combiner:ConfigurationHolder.()->Collection<Value> = {
                val parentValue = parent?.run { this@plusAssign.getOrNull() }
                val previousValue = previous

                val collection = mutableListOf<Value>()
                if (parentValue != null) {
                    collection.addAll(parentValue)
                }
                if (previousValue != null) {
                    collection.addAll(previousValue())
                }
                collection.add(lazyValue())
                collection
            }

            keyValueBinding.put(this as Key<Any>, combiner)
        }
    }

    private inline fun <Value>unpack(binding:LazyKeyValue<Value>):Value {
        //TODO something more clever?
        return this.binding()
    }

    private inline fun <Value, Output>getKeyValue(key: Key<Value>, otherwise:()->Output):Output where Value : Output {
        var holder: ConfigurationHolder = this@ConfigurationHolder
        while(true) {
            synchronized(holder.keyValueBinding) {
                if (holder.keyValueBinding.containsKey(key)) {
                    @Suppress("UNCHECKED_CAST")
                    val retrievedValue = keyValueBinding[key] as LazyKeyValue<Value>
                    return holder.unpack(retrievedValue)
                }
            }
            holder = holder.parent?:break
        }

        return otherwise()
    }

    /** Return the value bound to this wemi.key in this scope.
     * Throws exception if no value set. */
    fun <Value> Key<Value>.get():Value {
        return getKeyValue(this) {
            // We have to check default value
            if (hasDefaultValue) {
                @Suppress("UNCHECKED_CAST")
                return defaultValue as Value
            } else {
                throw WemiException.KeyNotAssignedException(this, this@ConfigurationHolder.name)
            }
        }
    }

    /** Return the value bound to this wemi.key in this scope.
     * Returns [unset] if no value set.
     * @param acceptDefault if wemi.key has default value, return that, otherwise return [unset] */
    fun <Value> Key<Value>.getOrElse(unset:Value, acceptDefault:Boolean = true):Value {
        return getKeyValue(this) {
            // We have to check default value
            @Suppress("UNCHECKED_CAST")
            if (acceptDefault && this.hasDefaultValue) {
                return this.defaultValue as Value
            } else {
                return unset
            }
        }
    }

    /** Return the value bound to this wemi.key in this scope.
     * Returns `null` if no value set. */
    fun <Value> Key<Value>.getOrNull():Value? {
        return getKeyValue(this) {
            this.defaultValue
        }
    }
}


class ProjectDelegate internal constructor(
        private val projectRoot: File,
        private val initializer: Project.() -> Unit
) : ReadOnlyProperty<Any?, Project> {

    private lateinit var project: Project

    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ProjectDelegate {
        this.project = Project(property.name, projectRoot.absoluteFile)
        synchronized(BuildScriptData.AllProjects) {
            for ((_, project) in BuildScriptData.AllProjects) {
                if (project.name == this.project.name) {
                    throw WemiException("wemi.Project named '${this.project.name}' already exists (at ${project.projectRoot})")
                }
                if (project.projectRoot == this.project.projectRoot) {
                    LOG.warn("wemi.Project ${this.project.name} is at the same location as wemi.project ${project.name}")
                }
            }
            BuildScriptData.AllProjects.put(project.name, project)
        }
        this.project.apply {
            Keys.projectName set { project.name }
            Keys.projectRoot set { project.projectRoot }

            Keys.buildDirectory set KeyDefaults.BuildDirectory
            Keys.sourceDirectories set KeyDefaults.SourceDirectories
            Keys.sourceExtensions set KeyDefaults.SourceExtensions
            Keys.sourceFiles set KeyDefaults.SourceFiles
            Keys.repositories set KeyDefaults.Repositories
            Keys.libraryDependencies set KeyDefaults.LibraryDependencies
            Keys.classpath set KeyDefaults.Classpath
            Keys.javaHome set KeyDefaults.JavaHome
            Keys.javaExecutable set KeyDefaults.JavaExecutable
            Keys.compilerOptions set KeyDefaults.CompilerOptions
            Keys.compileOutputFile set KeyDefaults.CompileOutputFile
            Keys.compile set KeyDefaults.Compile

            //Keys.mainClass TODO Detect main class?
            Keys.runDirectory set KeyDefaults.RunDirectory
            Keys.runOptions set KeyDefaults.RunOptions
            Keys.runArguments set KeyDefaults.RunArguments
            Keys.run set KeyDefaults.Run
        }
        this.project.initializer()
        return this
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): Project = project
}

class KeyDelegate<Value> internal constructor(
        private val description:String,
        private val hasDefaultValue:Boolean,
        private val defaultValue:Value?,
        private val cached:Boolean) : ReadOnlyProperty<Any?, Key<Value>> {

    private lateinit var key: Key<Value>

    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): KeyDelegate<Value> {
        this.key = Key(property.name, description, hasDefaultValue, defaultValue)
        @Suppress("UNCHECKED_CAST")
        synchronized(BuildScriptData.AllKeys) {
            val existing = BuildScriptData.AllKeys[this.key.name]
            if (existing != null) {
                throw WemiException("wemi.Key ${key.name} already exists (desc: '${existing.description}')")
            }
            BuildScriptData.AllKeys.put(key.name, key as Key<Any>)
        }
        return this
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): Key<Value> = key
}

class ConfigurationDelegate internal constructor(
        private val description: String,
        private val parent: ConfigurationHolder?) : ReadOnlyProperty<Any?, Configuration> {

    private lateinit var configuration: Configuration

    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ConfigurationDelegate {
        this.configuration = Configuration(property.name, description, parent)
        synchronized(BuildScriptData.AllConfigurations) {
            val existing = BuildScriptData.AllConfigurations[configuration.name]
            if (existing != null) {
                throw WemiException("wemi.Configuration ${configuration.name} already exists (desc: '${existing.description}')")
            }

            BuildScriptData.AllConfigurations.put(configuration.name, configuration)
        }
        return this
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): Configuration = configuration
}