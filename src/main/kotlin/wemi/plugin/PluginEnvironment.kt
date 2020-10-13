package wemi.plugin

/**
 * Interface to be implemented by a Wemi plugin, that allows it to inject its own settings into the environment
 * and perform other initialization.
 *
 * Uses [java.util.ServiceLoader], so when implementing, add fully classified name of the class into your
 * plugin's `META-INF/services/wemi.plugin.PluginEnvironment` file. Such classes must have no-arg constructor!
 */
interface PluginEnvironment {

    /**
     * Perform whatever global initialization this plugin needs.
     * This initialization MUST NOT be dependent on the system environment, such as OS, installed programs or SDKs,
     * internet connection, etc.
     * Initialization that may fail should be done in (optionally cached) [wemi.Key]-bound functions.
     *
     * For example, bind whatever keys are needed to plugin's normal function.
     * Do not modify bindings of keys that do not belong to this plugin!
     *
     * Use this ONLY for keys that are independently usable by all projects, that is, generic tasks and settings,
     * that must be invoked explicitly and don't change the characteristics of the project.
     *
     * Example:
     * ```
     * fun initialize() {
     *      Archetypes::Base.inject {
     *          myKey set { "foo" }
     *      }
     * }
     * ```
     *
     * @see wemi.inject
     */
    fun initialize()

}