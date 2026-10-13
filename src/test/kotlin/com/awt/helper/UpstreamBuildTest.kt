package com.awt.helper

import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.nio.file.*
import java.util.zip.ZipFile
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/** Opt-in executable contract test using the untouched, published upstream archives. */
class UpstreamBuildTest {
    @Test fun officialBuildPromptsButDoesNotInstallAndDeployUsesActualFlags() {
        val php=System.getProperty("awt.php")
        val fixtures=System.getProperty("awt.fixtures")
        assumeTrue("Pass -PawtPhp and -PawtFixtures to run published-release PHP contract tests",php != null && fixtures != null)
        val root=Files.createTempDirectory("awt official build with spaces ")
        val request=AtomicReference<String>()
        val server=HttpServer.create(InetSocketAddress("localhost",0),0)
        server.createContext("/dev/install") { exchange ->
            request.set(exchange.requestBody.bufferedReader().readText())
            val body="Installed on Test site".toByteArray()
            exchange.sendResponseHeaders(200,body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val config=HelperConfig(mode=FrameworkMode.REFERENCE,deploymentAddress="http://localhost:${server.address.port}")
            ProjectFiles.prepare(root,Manifest(name="Blog"),config,Path.of(fixtures,"template.zip"),Path.of(fixtures,"build.zip"))
            ProjectFiles.writeBuildEnvironment(root,config,"test-secret")
            val build=Php.capture(listOf(php!!) + Commands.build(false,false),root,Commands.input(false))
            assertTrue(build.contains("Install package now?")); assertTrue(build.contains("Installation skipped."))
            assertNull(request.get())
            val archive=Files.list(root.resolve("releases")).use { it.filter { p -> p.toString().endsWith(".zip") }.findFirst().orElseThrow() }
            ZipFile(archive.toFile()).use { zip ->
                assertNotNull(zip.getEntry("manifest.json")); assertNotNull(zip.getEntry("main.php"))
                val files=zip.entries().asSequence().map { it.name }.toList()
                assertTrue(files.any { it.contains("Blog") }); assertFalse(files.any { it.contains("PackageName") })
            }
            val deploy=Php.capture(listOf(php) + Commands.build(true,true,"update"),root)
            assertTrue(deploy.contains("Installed on Test site"))
            assertTrue(request.get().contains("test-secret")); assertTrue(request.get().contains("update"))
            assertTrue(Files.exists(root.resolve("build/.file_cache.json")))
            // The IDE uses this preflight to check extensions without doing work on the UI thread.
            request.set(null)
            val preflight="require 'build/runtime/run.php';"
            val viaIde=Php.capture(listOf(php,"-r",preflight,"--") + Commands.build(true,false).drop(1),root)
            assertTrue(viaIde.contains("Installed on Test site")); assertNotNull(request.get())
        } finally { server.stop(0); Archives.deleteTree(root) }
    }
}
