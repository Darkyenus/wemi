package wemi.boot

import org.jline.utils.AttributedStringBuilder
import org.jline.utils.AttributedStyle
import wemi.ActivityListener
import wemi.Binding
import wemi.EvaluationListener
import wemi.Key
import wemi.Scope
import wemi.util.CliStatusDisplay
import wemi.util.appendShortByteSize
import wemi.util.appendShortTimeDuration
import java.util.concurrent.TimeUnit

private val STATUS_PREFIX = if (WemiUnicodeOutputSupported) "• " else "# "
private val STATUS_INFIX = if (WemiUnicodeOutputSupported) " ‣ " else " > "
private val STATUS_INFIX_PARALLEL = if (WemiUnicodeOutputSupported) " ‣‣ " else " >> "
private val STATUS_ELLIPSIS = if (WemiUnicodeOutputSupported) "…" else "..."
private val STATUS_META_STYLE = AttributedStyle.BOLD.foreground(AttributedStyle.GREEN)
private val STATUS_CONTENT_STYLE = AttributedStyle.DEFAULT.underline()
private val STATUS_CONTENT_ACTIVITY_STYLE = AttributedStyle.DEFAULT.underline().foreground(AttributedStyle.WHITE)
private val STATUS_SIZE_STYLE = AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE)
private val STATUS_TIME_STYLE = AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)

private class StackLevel {
	var key = false
	var text = ""
	val extra = AttributedStringBuilder()
	val parallelActivities = ArrayList<KeyEvaluationStatusListener>(0)

	private var nextActivityProgress = 0L

	fun resetToKey(key:Key<*>) {
		this.key = true
		this.text = key.name
		this.extra.setLength(0)
		this.nextActivityProgress = 0L
	}

	fun resetToActivity(activity:String) {
		this.key = false
		this.text = activity
		this.extra.setLength(0)
		this.nextActivityProgress = 0L
	}

	fun setExtraToDownloadProgress(bytes: Long, totalBytes: Long, durationNs: Long) {
		val now = System.currentTimeMillis()
		if (nextActivityProgress > now) {
			// Do not update too often
			return
		}
		nextActivityProgress = now + 100 //ms

		val message = extra
		message.setLength(0)
		message.style(STATUS_SIZE_STYLE).appendShortByteSize(bytes)
		if (totalBytes > 0 && bytes <= totalBytes) {
			message.append('/').appendShortByteSize(totalBytes)
		}

		val durationSec = durationNs / 1000000000.0
		if (bytes > 0 && durationSec >= 1) {
			val bytesPerSec = bytes.toDouble() / durationSec
			message.append(" at ").appendShortByteSize(bytesPerSec.toLong()).append("/s")

			if (bytes < totalBytes && bytesPerSec > 0) {
				val remainingSeconds = (totalBytes - bytes) / bytesPerSec
				if (remainingSeconds > 5.0 && remainingSeconds <= TimeUnit.DAYS.toSeconds(1)) {
					message.style(STATUS_TIME_STYLE).append(" (ETA ").appendShortTimeDuration((remainingSeconds * 1000.0).toLong()).append(')')
				}
			}
		}
	}

	fun beginParallelActivity(activity: String, renderer:KeyEvaluationStatusListenerRenderer): ActivityListener {
		val parallelActivities = parallelActivities
		val listener = KeyEvaluationStatusListener(renderer, parallelActivities)
		synchronized(parallelActivities) {
			parallelActivities.add(listener)
		}
		listener.beginActivity(activity)
		return listener
	}
}

internal class KeyEvaluationStatusListenerRenderer(private val display: CliStatusDisplay) {
	private val messageBuilder = AttributedStringBuilder()
	private val importantPrefix:Int

	init {
		messageBuilder.style(STATUS_META_STYLE)
		messageBuilder.append(STATUS_PREFIX)
		importantPrefix = messageBuilder.length
	}

	val listener = KeyEvaluationStatusListener(this, null)

	fun redraw() {
		synchronized(this) {
			val mb = messageBuilder
			mb.setLength(importantPrefix)
			listener.drawListener(mb)

			display.setMessage(mb.toAttributedString(), importantPrefix)
		}
	}
}

internal class KeyEvaluationStatusListener(
		private val renderer:KeyEvaluationStatusListenerRenderer,
		private val parallelActivityContainer:ArrayList<KeyEvaluationStatusListener>?)
	: EvaluationListener {

	private val stackLevels = Array(16) { StackLevel() }
	private var stackLevel = 0

	private fun redraw() {
		parallelActivityContainer?.let { parallelActivityContainer ->
			synchronized(parallelActivityContainer) {
				val index = parallelActivityContainer.indexOf(this)
				// Do not redraw if it will not be visible anyway.
				// NOTE: Not perfect, disregards parent listeners.
				if (index != 0) {
					return
				}
			}
		}
		renderer.redraw()
	}

	fun drawListener(mb: AttributedStringBuilder) {
		// Redraw
		val stackLevelCount = minOf(stackLevel, stackLevels.size)
		for (i in 0 until stackLevelCount) {
			if (i > 0) {
				mb.style(STATUS_META_STYLE)
				mb.append(STATUS_INFIX)
			}
			val level = stackLevels[i]
			if (level.key) {
				mb.style(STATUS_CONTENT_STYLE)
			} else {
				mb.style(STATUS_CONTENT_ACTIVITY_STYLE)
			}

			val maxLength = 64
			val text = level.text
			if (text.length > maxLength) {
				mb.append(STATUS_ELLIPSIS).append(text, maxOf(text.length - maxLength - STATUS_ELLIPSIS.length, 0), text.length)
			} else {
				mb.append(text)
			}

			if (i + 1 == stackLevelCount) {
				if (level.extra.isNotEmpty()) {
					mb.append(' ').append(level.extra)
				}
				val parallelActivities = level.parallelActivities
				synchronized(parallelActivities) {
					assert(parallelActivities.all { it.stackLevel > 0 })
					parallelActivities.firstOrNull()
				}?.let { firstParallel ->
					mb.style(STATUS_META_STYLE)
					mb.append(STATUS_INFIX_PARALLEL)
					firstParallel.drawListener(mb)
				}
			}
		}
	}

	override fun keyEvaluationStarted(fromScope: Scope, key: Key<*>) {
		stackLevels.getOrNull(stackLevel++)?.resetToKey(key)
		redraw()
	}

	private fun pop(redraw:Boolean = true) {
		if (stackLevel > 0) {
			stackLevel--
			if (redraw) {
				redraw()
			}
		}
	}

	override fun <V> keyEvaluationSucceeded(binding: Binding<V>, result: V) {
		pop()
	}

	override fun keyEvaluationFailedByNoBinding(withAlternative: Boolean, alternativeResult: Any?) {
		pop()
	}

	override fun keyEvaluationFailedByError(exception: Throwable, fromKey: Boolean) {
		pop()
	}

	override fun beginActivity(activity: String) {
		stackLevels.getOrNull(stackLevel++)?.resetToActivity(activity)
		redraw()
	}

	private val activityProgressBytes_sb = StringBuilder()

	override fun activityDownloadProgress(bytes: Long, totalBytes: Long, durationNs:Long) {
		stackLevels.getOrNull(stackLevel - 1)?.setExtraToDownloadProgress(bytes, totalBytes, durationNs)
		redraw()
	}

	override fun endActivity() {
		pop(redraw = false)

		val parallelActivityContainer = parallelActivityContainer
		if (stackLevel <= 0 && parallelActivityContainer != null) {
			synchronized(parallelActivityContainer) {
				val removed = parallelActivityContainer.remove(this)
				assert(removed)
			}
		}

		redraw()
	}

	override fun beginParallelActivity(activity: String): ActivityListener? {
		return stackLevels.getOrNull(stackLevel - 1)?.beginParallelActivity(activity, renderer)
	}
}