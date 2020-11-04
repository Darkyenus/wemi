package com.darkyen.wemi.intellij.util

import com.intellij.execution.process.ProcessIOExecutorService
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.ArchiveFileSystem
import com.intellij.util.ConcurrencyUtil
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private val LOG = LoggerFactory.getLogger("Streams")

/** Collect all output of the process, log error. If the process does not quit until timeout, kill it. */
fun Process.collectOutputLineAndKill(timeout:Long, timeoutUnit: TimeUnit, useStdOut:Boolean):String? {
    var result:String? = null
    val resultSemaphore = Semaphore(0, false)

    ProcessIOExecutorService.INSTANCE.submit {
        try {
            ConcurrencyUtil.runUnderThreadName("collectOutputAndKill-output($this)") {
                (if (useStdOut) inputStream else errorStream).reader(Charsets.UTF_8).useLines { lines ->
                    result = lines.firstOrNull()
                }
            }
        } finally {
            resultSemaphore.release()
        }
    }

    ProcessIOExecutorService.INSTANCE.submit {
        ConcurrencyUtil.runUnderThreadName("collectOutputAndKill-error($this)") {
            (if (useStdOut) errorStream else inputStream).reader(Charsets.UTF_8).useLines { lines ->
                for (s in lines) {
                    if (s.isNotBlank()) {
                        LOG.warn("Process {} stderr: {}", this, s)
                    }
                }
            }
        }
    }

    if (!waitFor(timeout, timeoutUnit)) {
        destroy()
        if (!waitFor(5, TimeUnit.SECONDS)) {
            destroyForcibly()
        }
    }
    if (!resultSemaphore.tryAcquire(10, TimeUnit.SECONDS)) {
        LOG.error("collectOutputAndKill {} - process output still open", this)
    }

    if (!isAlive && exitValue() != 0) {
        LOG.warn("collectOutputAndKill {} - non-zero exit value ({})", this, exitValue())
        return null
    }

    return result
}

fun VirtualFile?.toPath(): Path? {
    val localPath = this?.let { wrappedFile ->
        val wrapFileSystem = wrappedFile.fileSystem
        if (wrapFileSystem is ArchiveFileSystem) {
            wrapFileSystem.getLocalByEntry(wrappedFile)
        } else {
            wrappedFile
        }
    }

    if (localPath?.isInLocalFileSystem != true) {
        return null
    }

    // Based on LocalFileSystemBase.java
    var path = localPath.path
    if (StringUtil.endsWithChar(path, ':') && path.length == 2 && SystemInfo.isWindows) {
        path += "/"
    }

    return Paths.get(path)
}