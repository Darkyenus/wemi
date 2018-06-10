# Wemi JVM-Hotswap Plugin

Add this to your Wemi build script to add `runHotswap` key, which works like ordinary `run` task,
but watches for changes to sources and automatically recompiles when something changes and reloads the changes
into the running process.

```kotlin
@file:BuildDependency("com.darkyen.wemi", "wemi-plugin-jvm-hotswap", WemiVersion)
```
TODO: Not published anywhere yet

## How it works
- `wemiplugin.jvmhotswap.Keys.runHotswap`
	1. Compiles what is needed (in configuration `running:hotswapping:`)
	2. Launches the compiled project, like `run`, but with hotswap agent
	3. Wemi communicates with the hotswap agent via network port defined in `hotswapAgentPort` (5015 by default)
	4. Wemi continuously watches sources for changes, when changes are detected, the project is recompiled
	5. If the recompilation is successful, agent is notified about changed classes and reloads them

The hotswap capability depends on JVM API `java.lang.instrument.Instrumentation.redefineClasses` which has some limited
capabilities, especially around changing class structure, but in general, changing bodies of methods should always work.

Hotswap agent also sets Java property `wemi.hotswap.iteration` to `"0"` and increments it on each successful hotswap.
You may watch this property in your application to detect hotswapping and re-run some initialization code,
so that changes in it can manifest themselves.
