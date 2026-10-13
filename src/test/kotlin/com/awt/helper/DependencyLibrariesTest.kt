package com.awt.helper

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DependencyLibrariesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun reloadReplacesDownloadsAndPreservesLibrariesWithoutUrls() {
        val root = temporary.newFolder().toPath()
        val core = root.resolve(".awt/dependencies/Core")
        val local = root.resolve(".awt/dependencies/Local")
        Files.createDirectories(core)
        Files.createDirectories(local)
        Files.writeString(core.resolve("old.php"), "old version")
        Files.writeString(local.resolve("keep.php"), "local library")
        withDownload(packageZip("Core")) { url ->
            DependencyLibraries.reload(root, listOf(Dependency("Core", "*", url), Dependency("Local")))
            DependencyLibraries.reload(root, listOf(Dependency("Core", "*", url), Dependency("Local")))
        }
        assertTrue(Files.isRegularFile(core.resolve("Core.php")))
        assertFalse(Files.exists(core.resolve("old.php")))
        assertEquals("local library", Files.readString(local.resolve("keep.php")))
        assertNoTemporaryDownloads(root)
    }

    @Test fun failedReloadKeepsExistingLibrariesUntilAllDownloadsSucceed() {
        val root = temporary.newFolder().toPath()
        val core = root.resolve(".awt/dependencies/Core")
        Files.createDirectories(core)
        Files.writeString(core.resolve("keep.php"), "old version")
        withDownload(packageZip("Core")) { coreUrl ->
            withDownload("<html>Download page</html>".toByteArray()) { badUrl ->
                expectFailure {
                    DependencyLibraries.reload(root, listOf(
                        Dependency("Core", "*", coreUrl), Dependency("Other", "*", badUrl)
                    ))
                }
            }
        }
        assertEquals("old version", Files.readString(core.resolve("keep.php")))
        assertFalse(Files.exists(core.resolve("Core.php")))
        assertFalse(Files.exists(root.resolve(".awt/dependencies/Other")))
        assertNoTemporaryDownloads(root)
    }

    @Test fun canceledReloadRollsBackAlreadyReplacedLibraries() {
        val root = temporary.newFolder().toPath()
        val core = root.resolve(".awt/dependencies/Core")
        Files.createDirectories(core)
        Files.writeString(core.resolve("keep.php"), "old version")
        val canceled = IllegalStateException("Canceled reload")
        withDownload(packageZip("Core")) { coreUrl ->
            withDownload(packageZip("Other")) { otherUrl ->
                try {
                    DependencyLibraries.reload(root, listOf(
                        Dependency("Core", "*", coreUrl), Dependency("Other", "*", otherUrl)
                    ), {
                        if (Files.exists(core.resolve("Core.php"))) throw canceled
                    })
                    fail("Expected cancellation after the first replacement")
                } catch (e: IllegalStateException) {
                    assertSame(canceled, e)
                }
            }
        }
        assertEquals("old version", Files.readString(core.resolve("keep.php")))
        assertFalse(Files.exists(core.resolve("Core.php")))
        assertFalse(Files.exists(root.resolve(".awt/dependencies/Other")))
        assertNoTemporaryDownloads(root)
    }

    @Test fun rejectsConcurrentReloads() {
        val root = temporary.newFolder().toPath()
        val helper = root.resolve(".awt/helper")
        Files.createDirectories(helper)
        FileChannel.open(helper.resolve("dependencies.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                expectFailure { DependencyLibraries.reload(root, emptyList()) }
            }
        }
        // A completed operation releases the lock for the next invocation.
        DependencyLibraries.reload(root, emptyList())
        assertNoTemporaryDownloads(root)
    }

    @Test fun downloadsWrappedPackagesAndSkipsDependenciesWithoutUrls() {
        val root = temporary.newFolder().toPath()
        withDownload(packageZip("Core")) { url ->
            DependencyLibraries.download(root, listOf(Dependency("Core", "*", url), Dependency("Existing")))
        }
        val library = root.resolve(".awt/dependencies/Core")
        assertTrue(Files.isRegularFile(library.resolve("manifest.json")))
        assertEquals("<?php class DownloadedCore {}", Files.readString(library.resolve("Core.php")))
        assertEquals(listOf(library), DependencyLibraries.libraryPaths(root))
        assertFalse(Files.exists(root.resolve(".awt/dependencies/Existing")))
        assertNoTemporaryDownloads(root)
    }

    @Test fun rejectsWebPagesHttpErrorsAndWrongPackagesWithoutLeavingLibraries() {
        for ((bytes, status) in listOf(
            "<html>Download page</html>".toByteArray() to 200,
            byteArrayOf() to 404,
            packageZip("Other") to 200
        )) {
            val root = temporary.newFolder().toPath()
            withDownload(bytes, status) { url ->
                expectFailure { DependencyLibraries.download(root, listOf(Dependency("Core", "*", url))) }
            }
            assertFalse(Files.exists(root.resolve(".awt/dependencies/Core")))
            assertNoTemporaryDownloads(root)
        }
    }

    @Test fun rejectsArchiveTraversalAndUnsafeDownloadNames() {
        val root = temporary.newFolder().toPath()
        withDownload(packageZip("Core", "../escape.php")) { url ->
            expectFailure { DependencyLibraries.download(root, listOf(Dependency("Core", "*", url))) }
            expectFailure { DependencyLibraries.download(root, listOf(Dependency("../Core", "*", url))) }
        }
        assertFalse(Files.exists(root.resolve(".awt/dependencies/Core")))
        assertFalse(Files.exists(root.resolve("escape.php")))
        assertNoTemporaryDownloads(root)
        expectFailure { DependencyLibraries.validateDownload(Dependency("Core", "*", "file:///tmp/core.zip")) }
    }

    @Test fun cancellationCleansUpAndExistingLibrariesArePreserved() {
        val root = temporary.newFolder().toPath()
        val canceled = IllegalStateException("Canceled download")
        withDownload(packageZip("Core")) { url ->
            try {
                var checks = 0
                DependencyLibraries.download(root, listOf(Dependency("Core", "*", url)), {
                    if (++checks == 3) throw canceled
                })
                fail("Expected cancellation")
            } catch (e: IllegalStateException) {
                assertSame(canceled, e)
            }
            assertNoTemporaryDownloads(root)
            assertFalse(Files.exists(root.resolve(".awt/dependencies/Core")))

            val destination = root.resolve(".awt/dependencies/Core")
            Files.createDirectories(destination)
            Files.writeString(destination.resolve("keep.php"), "original")
            expectFailure { DependencyLibraries.download(root, listOf(Dependency("Core", "*", url))) }
            assertEquals("original", Files.readString(destination.resolve("keep.php")))
        }
    }

    private fun packageZip(name: String, extraEntry: String? = null): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            val entries = linkedMapOf(
                "release/manifest.json" to json.toJson(Manifest(name = name)),
                "release/Core.php" to "<?php class DownloadedCore {}"
            )
            extraEntry?.let { entries[it] = "<?php" }
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun withDownload(bytes: ByteArray, status: Int = 200, test: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/download") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            test("http://127.0.0.1:${server.address.port}/download")
        } finally {
            server.stop(0)
        }
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected dependency download failure")
        } catch (e: Exception) {
            assertFalse(e.message.isNullOrBlank())
        }
    }

    private fun assertNoTemporaryDownloads(root: Path) {
        val helper = root.resolve(".awt/helper")
        if (Files.isDirectory(helper)) {
            Files.list(helper).use { paths ->
                assertFalse(paths.anyMatch { it.fileName.toString().startsWith("dependency-") })
            }
        }
    }
}
