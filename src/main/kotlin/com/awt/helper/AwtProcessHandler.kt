package com.awt.helper

import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/** Redact secrets before decoding ANSI escape sequences for the Run console. */
class AwtProcessHandler(process: Process, name: String, private val secret: String = "") :
    KillableProcessHandler(process, name, StandardCharsets.UTF_8) {
    private val filters = ConcurrentHashMap<Key<*>, Redactor>()
    private val decoder = AnsiEscapeDecoder()
    private val deploymentOutput = DeploymentOutput()
    val deploymentSucceeded: Boolean get() = deploymentOutput.succeeded

    override fun notifyTextAvailable(text: String, outputType: Key<*>) {
        val safe = filters.computeIfAbsent(outputType) { Redactor(secret) }.append(text)
        publish(safe, outputType)
    }

    private fun publish(text: String, outputType: Key<*>) {
        if (text.isEmpty()) return
        if (outputType == ProcessOutputTypes.STDOUT) deploymentOutput.append(text)
        decoder.escapeText(text, outputType) { colored, attributes ->
            super.notifyTextAvailable(colored, attributes)
        }
    }

    override fun onOSProcessTerminated(exitCode: Int) {
        filters.forEach { (key, filter) -> publish(filter.finish(), key) }
        deploymentOutput.finish()
        super.onOSProcessTerminated(exitCode)
    }
}

internal class DeploymentOutput {
    private val pending = StringBuilder()
    @Volatile var succeeded = false
        private set

    @Synchronized fun append(text: String) {
        pending.append(text)
        while (true) {
            val newline = pending.indexOf("\n")
            if (newline < 0) return
            inspect(pending.substring(0, newline))
            pending.delete(0, newline + 1)
        }
    }

    @Synchronized fun finish() {
        inspect(pending.toString())
        pending.setLength(0)
    }

    private fun inspect(line: String) {
        val plain = line.replace(ANSI_ESCAPE, "").trim()
        if (plain.startsWith("Installed on ")) succeeded = true
    }

    private companion object {
        val ANSI_ESCAPE = Regex("\u001b\\[[0-?]*[ -/]*[@-~]")
    }
}
