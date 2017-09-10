@file:Suppress("unused")

package wemi

import org.slf4j.LoggerFactory
import wemi.KeyDefaults.applyDefaults
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
        }
        this.project.applyDefaults()
        this.project.initializer()
        this.project.locked = true
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
        this.key = Key(property.name, description, hasDefaultValue, defaultValue, cached)
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
        private val parent: Configuration?,
        private val initializer: Configuration.() -> Unit) : ReadOnlyProperty<Any?, Configuration> {

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
        configuration.initializer()
        configuration.locked = true
        return this
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): Configuration = configuration
}

open class ScopeCache internal constructor(internal val bindingHolder: BindingHolder,
                  internal val parentScope:Scope?) {

    private val scopes:MutableMap<Configuration, ConfigurationScope> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        mutableMapOf<Configuration, ConfigurationScope>()
    }

    private var valueCache:MutableMap<Key<*>, Any?>? = null

    internal fun scope(me:Scope, configuration:Configuration):Scope {
        val scopes = scopes
        synchronized(scopes) {
            return scopes.getOrPut(configuration) {
                ConfigurationScope(configuration, me)
            }
        }
    }

    internal fun <Value>getCached(key:Key<Value>):Value? {
        synchronized(this) {
            val valueCache = this.valueCache ?: return null
            @Suppress("UNCHECKED_CAST")
            return valueCache[key] as Value
        }
    }

    internal fun <Value>putCached(key:Key<Value>, value:Value) {
        synchronized(this) {
            val maybeNullCache = valueCache
            val cache:MutableMap<Key<*>, Any?>
            if (maybeNullCache == null) {
                cache = mutableMapOf()
                this.valueCache = cache
            } else {
                cache = maybeNullCache
            }

            cache.put(key, value)
        }
    }

    internal fun cleanCache():Int {
        synchronized(this) {
            val valueCache = this.valueCache
            this.valueCache = null
            return valueCache?.size ?: 0
        }
    }
}

private class ConfigurationScope(
        bindingHolder: Configuration,
        parentScope:Scope) : ScopeCache(bindingHolder, parentScope), Scope {

    override fun previous(): Scope = parentScope!!

    override val scopeCache: ScopeCache
        get() = this

    override fun scopeToString(): String = parentScope!!.scopeToString() + bindingHolder.name + ":"
}