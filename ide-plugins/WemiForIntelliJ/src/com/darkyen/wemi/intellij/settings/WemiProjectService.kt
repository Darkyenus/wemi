package com.darkyen.wemi.intellij.settings

import com.darkyen.wemi.intellij.options.ProjectImportOptions
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State

/** Stuff saved per IDE project */
@Service
@State(name = "wemi.WemiProjectService")
class WemiProjectService : PersistentStateComponent<WemiProjectService.State> {

	private var state = State()

	override fun getState(): State = state

	override fun loadState(state: State) {
		this.state = state
	}

	class State {
		var options = ProjectImportOptions()
		var tasks = emptyMap<String, String>()
	}
}