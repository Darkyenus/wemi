package com.darkyen.wemi.intellij.util

import com.intellij.execution.process.OSProcessHandler
import com.intellij.util.io.BaseDataReader
import com.intellij.util.io.BaseOutputReader

class OSProcessHandlerForWemi(process: Process, commandLine: String) : OSProcessHandler(process, commandLine, Charsets.UTF_8) {

	private object SaneOptions: BaseOutputReader.Options() {
		override fun splitToLines(): Boolean = false

		override fun sendIncompleteLines(): Boolean = true

		override fun policy(): BaseDataReader.SleepingPolicy = BaseDataReader.SleepingPolicy.BLOCKING

		override fun withSeparators(): Boolean = true
	}

	override fun readerOptions(): BaseOutputReader.Options = SaneOptions
}