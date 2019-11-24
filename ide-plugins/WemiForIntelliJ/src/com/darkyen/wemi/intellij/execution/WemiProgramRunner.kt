package com.darkyen.wemi.intellij.execution

import com.intellij.debugger.DebugEnvironment
import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.DefaultDebugEnvironment
import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.XDebugSessionImpl
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise

/** I don't even know what it does. One more abstraction layer I guess. */
class WemiProgramRunner : AsyncProgramRunner<RunnerSettings>() {

	override fun getRunnerId(): String = "WemiProgramRunner"

	override fun canRun(executorId: String, profile: RunProfile): Boolean {
		return (executorId == DefaultRunExecutor.EXECUTOR_ID || executorId == DefaultDebugExecutor.EXECUTOR_ID)
				&& profile is WemiTaskConfiguration
	}

	override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
		if (state !is WemiTaskConfiguration.WemiRunProfileState) {
			throw AssertionError("Only WemiRunProfileState is supported, got $state")
		}
		FileDocumentManager.getInstance().saveAllDocuments()

		if (state.debugPort <= 0) {
			val result = state.execute(environment.executor, this)
			onProcessStarted(environment.runnerSettings, result)
			return resolvedPromise(RunContentBuilder(result, environment).showRunContent(environment.contentToReuse))
		} else {
			val connection = RemoteConnection(true, "localhost", state.debugPort.toString(),
					/* Wemi is a server when debugging build scripts, but forked processes connect to IDE */ !state.options.debugWemiItself)
			val debugEnvironment: DebugEnvironment = DefaultDebugEnvironment(environment, state, connection, Long.MAX_VALUE)
			val debuggerSession = DebuggerManagerEx.getInstanceEx(environment.project).attachVirtualMachine(debugEnvironment) ?: return resolvedPromise(null)
			val executionResult = debuggerSession.process.executionResult

			onProcessStarted(environment.runnerSettings, executionResult)

			return resolvedPromise(XDebuggerManager.getInstance(environment.project).startSession(environment, object : XDebugProcessStarter() {
				override fun start(session: XDebugSession): XDebugProcess {
					val sessionImpl = session as XDebugSessionImpl
					sessionImpl.addExtraActions(*executionResult.actions)
					if (executionResult is DefaultExecutionResult) {
						sessionImpl.addRestartActions(*executionResult.restartActions)
					}
					return JavaDebugProcess.create(session, debuggerSession)
				}
			}).runContentDescriptor)
		}
	}
}