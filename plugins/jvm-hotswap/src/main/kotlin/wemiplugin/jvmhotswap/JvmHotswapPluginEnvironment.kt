package wemiplugin.jvmhotswap

import wemi.Archetypes
import wemi.inject
import wemi.plugin.PluginEnvironment

/**
 * Initializes the plugin environment.
 */
class JvmHotswapPluginEnvironment : PluginEnvironment {

    override fun initialize() {
        JvmHotswap//Init

        Archetypes::JVMBase.inject {
            JvmHotswap.runHotswap set JvmHotswap.Defaults.RunHotswap
            JvmHotswap.hotswapAgentPort set { 5015 }
        }
    }

}