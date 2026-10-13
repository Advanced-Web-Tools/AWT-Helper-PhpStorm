package com.awt.helper

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AwtProcessHandlerTest : BasePlatformTestCase() {
    fun testColorsAreDecodedAfterSecretsSplitAcrossChunksAreRedacted() {
        val process = object : Process() {
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun waitFor() = 0
            override fun exitValue() = 0
            override fun destroy() {}
        }
        val handler = AwtProcessHandler(process, "AWT test", "test-secret")
        val output = mutableListOf<Pair<String, Key<*>>>()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                output.add(event.text to outputType)
            }
        })
        handler.notifyTextAvailable("\u001b[32mInstalled on Test site: test-", ProcessOutputTypes.STDOUT)
        handler.notifyTextAvailable("secret\u001b[0m\n", ProcessOutputTypes.STDOUT)
        val text = output.joinToString("") { it.first }
        assertEquals("Installed on Test site: [redacted]\n", text)
        assertTrue(output.any { it.second != ProcessOutputTypes.STDOUT })
        assertTrue(handler.deploymentSucceeded)
        handler.startNotify()
        assertTrue(handler.waitFor(5000))
    }

    fun testFailureOutputDoesNotConfirmSuccessfulDeployment() {
        val output = DeploymentOutput()
        output.append("\u001b[31mError: Deployment failed: Installed on Other site\u001b[0m\n")
        output.finish()
        assertFalse(output.succeeded)
        output.append("\u001b[32mInstal")
        output.append("led on Test site\u001b[0m")
        output.finish()
        assertTrue(output.succeeded)
    }
}
