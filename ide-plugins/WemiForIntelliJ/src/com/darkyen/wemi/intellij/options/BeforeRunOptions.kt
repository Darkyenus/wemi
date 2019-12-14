package com.darkyen.wemi.intellij.options

/** Options for Before Run configurations. */
class BeforeRunOptions : RunOptions() {

	fun copyTo(o: BeforeRunOptions) {
		copyTo(o as RunOptions)
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is BeforeRunOptions) return false
		if (!super.equals(other)) return false
		return true
	}

	@Suppress("RedundantOverride")
	override fun hashCode(): Int {
		return super.hashCode()
	}
}