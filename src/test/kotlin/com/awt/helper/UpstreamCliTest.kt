package com.awt.helper

import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.nio.file.*
import java.net.*
import java.util.concurrent.TimeUnit

class UpstreamCliTest {
    @Test fun originalCliParsesInstallationInputWithSpacesQuotesAndPort() {
        val php=System.getProperty("awt.php")
        val fixtures=System.getProperty("awt.fixtures")
        assumeTrue(php != null && fixtures != null && Files.isRegularFile(Path.of(fixtures,"framework.zip")))
        val root=Files.createTempDirectory("awt-cli-contract-")
        try {
            Archives.extract(Path.of(fixtures,"framework.zip"),root.resolve("framework"),"awt")
            val fw=root.resolve("framework")
            val db=DatabaseInput("db.local",3307,"existing","user name","fixture 'password' with \\ and \"", "My Website","/sajt1/public")
            val input=Commands.installerInput(db)
            val script="require " + phpString(fw.resolve("awt_src/classes/cli/CLIHandler.php").toString()) + ";echo json_encode((new \\cli\\CLIHandler())->parseInput(trim(stream_get_contents(STDIN))));"
            val result=Php.capture(listOf(php!!,"-r",script),null,input.lineSequence().first())
            assertEquals(json.toJsonTree(db.installerArguments()),com.google.gson.JsonParser.parseString(result))
            val help=Php.capture(listOf(php,"awt"),fw,"help install\n")
            assertTrue(help.contains("seven arguments")); assertTrue(help.contains("host_path"))
        } finally { Archives.deleteTree(root) }
    }
    @Test fun realPhpServerServesUrlPrefixAndStopsItsOwnProcess() {
        val php=System.getProperty("awt.php")
        assumeTrue(php != null)
        val root=Files.createTempDirectory("awt server space ")
        var process: Process?=null
        try {
            val public=root.resolve(".awt/framework/public")
            Files.createDirectories(public)
            Files.writeString(public.resolve("index.php"),"<?php header('Content-Type: text/plain');echo \$_SERVER['REQUEST_URI'];")
            Files.writeString(public.resolve("asset.css"),"body { color: red; }")
            val port=ServerSocket(0).use { it.localPort }
            val config=HelperConfig(host="127.0.0.1",port=port,hostnamePath="/sajt1/public")
            process=ProcessBuilder(listOf(php!!) + Server.arguments(root,config)).directory(public.toFile()).start()
            val url=URI(Server.browserUrl(config)+"hello").toURL()
            var response: String?=null
            repeat(100) {
                if(response == null) {
                    response=runCatching { (url.openConnection() as HttpURLConnection).apply { connectTimeout=100; readTimeout=100 }.inputStream.use { it.bufferedReader().readText() } }.getOrNull()
                    if(response == null) Thread.sleep(50)
                }
            }
            assertEquals("/hello",response)
            val css=URI(Server.browserUrl(config)+"asset.css").toURL().readText()
            assertEquals("body { color: red; }",css)
            assertEquals("/dev/install",URI("http://127.0.0.1:$port/dev/install").toURL().readText())
            process.destroy()
            assertTrue(process.waitFor(5,TimeUnit.SECONDS)); assertFalse(process.isAlive)
        } finally { if(process?.isAlive == true) process.destroyForcibly(); Archives.deleteTree(root) }
    }
}
