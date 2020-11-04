package com.darkyen.wemi.intellij.util

import com.intellij.execution.process.ProcessIOExecutorService
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.ConcurrencyUtil
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe class which consolidates information about cancellation from two different sources,
 * that is from [ProgressIndicator] (which is polled) and from manual calls.
 */
class SessionState(private val indicator: ProgressIndicator) {

	private val state = AtomicInteger(State.RUNNING.ordinal)
	var exitCode:Int = Int.MIN_VALUE
		private set

	private val indicatorLock = Object()
	private val indicatorPoller = ProcessIOExecutorService.INSTANCE.submit {
		ConcurrencyUtil.runUnderThreadName("Wemi SessionCancellation ProgressIndicator checker") {
			while (state.get() == State.RUNNING.ordinal) {
				if (synchronized(indicatorLock) { indicator.isCanceled }) {
					cancel(false)
					break
				}

				try {
					Thread.sleep(100)
				} catch (e:InterruptedException) {
					break
				}
			}
		}
	}

	private val listeners = ArrayList<Listener>()

	fun addListener(listener:Listener) {
		synchronized(listeners) {
			assert(listener !in listeners)
			listeners.add(listener)
		}
	}

	fun removeListener(listener:Listener) {
		synchronized(listeners) {
			listeners.remove(listener)
		}
	}

	fun cancel(force:Boolean) {
		val newState = if (force) State.CANCELLED_FORCE else State.CANCELLED

		// Can really cancel only if running or upgrading severity of cancellation
		if (state.compareAndSet(State.RUNNING.ordinal, newState.ordinal)
				|| (force && state.compareAndSet(State.CANCELLED.ordinal, State.CANCELLED_FORCE.ordinal))) {
			// Cancelled!

			// Update indicator
			synchronized(indicatorLock) {
				if (!indicator.isCanceled) {
					indicator.cancel()
				}
			}

			// Notify listeners
			notifyStateChanged(newState)
		}
	}

	private fun notifyStateChanged(newState:State) {
		synchronized(listeners) {
			for (listener in listeners) {
				listener.sessionStateChange(newState)
			}
		}
	}

	fun finish(code:Int) {
		if (state.compareAndSet(State.RUNNING.ordinal, State.FINISHED.ordinal)) {
			exitCode = code
			notifyStateChanged(State.FINISHED)
		} else if (state.compareAndSet(State.CANCELLED.ordinal, State.FINISHED_CANCELLED.ordinal)
				|| state.compareAndSet(State.CANCELLED_FORCE.ordinal, State.FINISHED_CANCELLED.ordinal)) {
			exitCode = code
			notifyStateChanged(State.FINISHED_CANCELLED)
		}
	}

	fun isCancelled():Boolean {
		val state = state.get()
		return state == State.CANCELLED.ordinal || state == State.CANCELLED_FORCE.ordinal
	}

	/** @throws ProcessCanceledException if cancelled */
	fun checkCancelled() {
		if (isCancelled()) {
			throw ProcessCanceledException()
		}
	}

	enum class State {
		RUNNING,
		CANCELLED,
		CANCELLED_FORCE,
		FINISHED_CANCELLED,
		FINISHED
	}

	interface Listener {
		fun sessionStateChange(newState:State)
	}
}