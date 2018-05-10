# Wemi JVM-Hotswap Plugin

## Keys
- `wemiplugin.jvmhotswap.Keys.runHotswap` starts the project like normal `run` task, but watches for source code 
	modifications. When modification is detected, it recompiles the project and, if successful, hot-swaps modified 
	classes through attached agent.

## Inner workings
1. `runHotswap` compiles what is needed