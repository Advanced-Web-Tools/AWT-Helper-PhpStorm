package com.awt.helper

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipException

/** Downloads direct package ZIP links into the new project's private library directory. */
object DependencyLibraries {
    fun reload(
        root: Path,
        dependencies: List<Dependency>,
        check: () -> Unit = {},
        progress: (String) -> Unit = {}
    ) {
        val helper = root.resolve(".awt/helper")
        Files.createDirectories(helper)
        FileChannel.open(helper.resolve("dependencies.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            requireNotNull(lock) { "Dependencies are already being reloaded for this project." }
            lock.use { reloadPackages(root, dependencies, check, progress) }
        }
    }

    private fun reloadPackages(
        root: Path,
        dependencies: List<Dependency>,
        check: () -> Unit,
        progress: (String) -> Unit
    ) {
        val downloads = dependencies.filter { it.url.isNotBlank() }
        downloads.forEach(::validateDownload)
        require(downloads.map { it.name }.distinct().size == downloads.size) {
            "Dependency names must be unique."
        }
        val temporary = Files.createTempDirectory(root.resolve(".awt/helper"), "dependency-reload-")
        val replacements = mutableListOf<Pair<Path, Path?>>()
        var preserveBackup = false
        try {
            val fresh = temporary.resolve("fresh")
            // Finish every download before replacing any library in the open project.
            download(fresh, downloads, check, progress)
            check()
            val backup = temporary.resolve("backup")
            Files.createDirectories(backup)
            for (dependency in downloads) {
                check()
                val destination = root.resolve(".awt/dependencies").resolve(dependency.name)
                Files.createDirectories(destination.parent)
                val previous = if (Files.exists(destination)) backup.resolve(dependency.name) else null
                if (previous != null) Files.move(destination, previous)
                replacements.add(destination to previous)
                Files.move(fresh.resolve(".awt/dependencies").resolve(dependency.name), destination)
            }
        } catch (failure: Exception) {
            for ((destination, previous) in replacements.asReversed()) {
                try {
                    Archives.deleteTree(destination)
                    if (previous != null) Files.move(previous, destination)
                } catch (rollbackFailure: Exception) {
                    preserveBackup = true
                    failure.addSuppressed(rollbackFailure)
                }
            }
            if (preserveBackup) {
                throw IllegalStateException(
                    "Dependency reload failed; recovery files were kept in $temporary.", failure
                )
            }
            throw failure
        } finally {
            if (!preserveBackup) Archives.deleteTree(temporary)
        }
    }

    fun validateDownload(dependency: Dependency) {
        if (dependency.url.isBlank()) return
        require(dependency.name.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) {
            "Dependency ${dependency.name} needs a portable package name for downloading."
        }
        val uri = URI(dependency.url.trim())
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) {
            "Dependency ${dependency.name} URL must be a direct HTTP or HTTPS download link to a ZIP package."
        }
    }

    fun download(
        root: Path,
        dependencies: List<Dependency>,
        check: () -> Unit = {},
        progress: (String) -> Unit = {}
    ) {
        dependencies.forEach { dependency ->
            check()
            validateDownload(dependency)
            if (dependency.url.isBlank()) return@forEach
            progress("Downloading dependency ${dependency.name}")
            downloadPackage(root, dependency, check)
        }
    }

    /** Only extracted packages, never temporary downloads, are registered for PHP indexing. */
    fun libraryPaths(root: Path): List<Path> {
        val directory = root.resolve(".awt/dependencies")
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { paths ->
            paths.filter { Files.isDirectory(it) && Files.isRegularFile(it.resolve("manifest.json")) }
                .sorted().toList()
        }
    }

    private fun downloadPackage(root: Path, dependency: Dependency, check: () -> Unit) {
        val destination = root.resolve(".awt/dependencies").resolve(dependency.name)
        require(!Files.exists(destination)) { "Dependency destination already exists: ${dependency.name}." }
        val temporaryRoot = root.resolve(".awt/helper")
        Files.createDirectories(temporaryRoot)
        val temporary = Files.createTempDirectory(temporaryRoot, "dependency-download-")
        try {
            val archive = temporary.resolve("package.zip")
            downloadArchive(dependency.url.trim(), archive, check)
            val payload = temporary.resolve("package")
            try {
                Archives.extract(archive, payload, "manifest.json", check)
            } catch (e: ZipException) {
                throw IllegalArgumentException(
                    "Dependency ${dependency.name} URL must be a direct download link to a ZIP package, not a web page.", e
                )
            }
            val manifest = JsonParser.parseString(Files.readString(payload.resolve("manifest.json"))).asJsonObject
            require(manifest["name"]?.asString == dependency.name) {
                "Downloaded dependency manifest name does not match ${dependency.name}."
            }
            check()
            Files.createDirectories(destination.parent)
            Files.move(payload, destination)
        } finally {
            Archives.deleteTree(temporary)
        }
    }

    private fun downloadArchive(url: String, destination: Path, check: () -> Unit) {
        check()
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", "AWT-Helper/1.0")
        connection.setRequestProperty("Accept", "application/zip, application/octet-stream")
        try {
            val status = connection.responseCode
            require(status in 200..299) { "Dependency download failed (HTTP $status). Check the direct download link." }
            connection.inputStream.use { input ->
                Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW).use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 512L * 1024 * 1024) { "Dependency archive exceeds 512 MB." }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
