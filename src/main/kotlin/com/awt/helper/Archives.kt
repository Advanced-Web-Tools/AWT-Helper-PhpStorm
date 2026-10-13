package com.awt.helper

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.util.zip.ZipFile
import java.net.HttpURLConnection
import java.net.URI
import com.google.gson.JsonParser

/** Operations take a cancellation callback; callers supply ProgressIndicator.checkCanceled. */
class ReleaseClient(private val check: () -> Unit = {}, private val progress: (String) -> Unit = {}) {
    data class Asset(val name: String, val url: String)
    data class Release(val version: String, val assets: List<Asset>, val prerelease: Boolean = false, val latest: Boolean = false)
    fun list(repository: String): List<Release> {
        val result = mutableListOf<Release>()
        for (page in 1..1000) {
            check()
            val connection = connect("https://api.github.com/repos/Advanced-Web-Tools/$repository/releases?per_page=100&page=$page")
            val array = connection.inputStream.use { JsonParser.parseString(it.bufferedReader().readText()).asJsonArray }
            connection.disconnect()
            if (array.isEmpty) break
            array.forEach { element ->
                val r = element.asJsonObject
                if (!r["draft"].asBoolean) result.add(Release(r["tag_name"].asString, r["assets"].asJsonArray.map { a -> Asset(a.asJsonObject["name"].asString, a.asJsonObject["browser_download_url"].asString) }, r["prerelease"].asBoolean))
            }
            if (array.size() < 100) break
        }
        require(result.isNotEmpty()) { "No published release exists for $repository. A main-branch download is never used." }
        if(result.any { !it.prerelease }) {
            val latestConnection=connect("https://api.github.com/repos/Advanced-Web-Tools/$repository/releases/latest")
            val tag=try { latestConnection.inputStream.use { JsonParser.parseString(it.bufferedReader().readText()).asJsonObject["tag_name"].asString } } finally { latestConnection.disconnect() }
            return result.map { it.copy(latest=it.version == tag) }
        }
        return result
    }
    fun download(release: Release, name: String, destination: Path) {
        val asset = release.assets.singleOrNull { it.name == name } ?: error("Release ${release.version} does not contain required asset $name.")
        progress("Downloading $name (${release.version})")
        val c = connect(asset.url)
        try {
            c.inputStream.use { input -> Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW).use { output ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) { check(); val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 512L * 1024 * 1024) { "Archive exceeds 512 MB." }; output.write(buffer, 0, count) }
            } }
        } finally { c.disconnect() }
    }
    private fun connect(url: String): HttpURLConnection {
        check()
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "AWT-Helper/1.0")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val status = c.responseCode
        if (status !in 200..299) {
            val reset = c.getHeaderField("X-RateLimit-Reset")
            val retry = c.getHeaderField("Retry-After")
            c.disconnect()
            error(if (status == 403 || status == 429) "GitHub rate limit or access denied (HTTP $status). Retry after ${retry ?: "reset time $reset"}." else "GitHub download failed (HTTP $status). Check your network connection.")
        }
        return c
    }
    companion object {
        fun select(releases: List<Release>, version: String): Release = if (version == "Latest") (releases.firstOrNull { it.latest } ?: releases.firstOrNull { !it.prerelease }) ?: error("No published stable release is available for Latest. Select a published prerelease explicitly.") else releases.firstOrNull { it.version == version } ?: error("Release $version is no longer available.")
    }
}
object Archives {
    /** Only removes archive wrappers when a known payload sentinel is found. */
    fun extract(archive: Path, target: Path, sentinel: String, check: () -> Unit = {}) {
        require(!Files.exists(target)) { "Extraction target already exists: $target" }
        ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= 100000) { "Archive contains too many files." }
            entries.forEach { e -> safeRelative(e.name) }
            val roots = entries.filter { !it.isDirectory && (it.name == sentinel || it.name.endsWith("/$sentinel")) }.map { it.name.removeSuffix(sentinel) }
            require(roots.size == 1) { "Archive has no unique $sentinel payload root." }
            val prefix = roots.single()
            Files.createDirectories(target)
            var total = 0L
            entries.filter { it.name.startsWith(prefix) && it.name != prefix }.forEach { e ->
                check()
                val relative = safeRelative(e.name.removePrefix(prefix))
                val out = target.resolve(relative).normalize()
                require(out.startsWith(target.normalize())) { "Archive path escapes destination." }
                if (e.isDirectory) Files.createDirectories(out) else {
                    Files.createDirectories(out.parent)
                    zip.getInputStream(e).use { input -> Files.newOutputStream(out, StandardOpenOption.CREATE_NEW).use { output ->
                        val buffer = ByteArray(65536)
                        while (true) { check(); val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 1024L * 1024 * 1024) { "Expanded archive exceeds 1 GB." }; output.write(buffer, 0, n) }
                    } }
                }
            }
        }
    }
    private fun safeRelative(name: String): Path {
        require(name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') && !name.contains(':') && !name.contains('\u0000')) { "Unsafe archive path." }
        require(name.split('/').none { it == ".." }) { "ZIP path traversal rejected." }
        val path = Path.of(name).normalize()
        require(!path.isAbsolute && !path.startsWith("..")) { "ZIP path traversal rejected." }
        return path
    }
    fun replaceTemplate(root: Path, name: String, check: () -> Unit = {}) {
        Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.forEach { file ->
            check()
            val bytes = Files.readAllBytes(file)
            // NUL-containing content and invalid UTF-8 are binary; preserve every byte.
            if (!bytes.contains(0.toByte())) {
                val text = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
                if (text != null && text.contains("PackageName")) Files.writeString(file, text.replace("PackageName", name))
            }
        } }
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).filter { it != root }.forEach { path ->
            check()
            if (path.fileName.toString().contains("PackageName")) Files.move(path, path.resolveSibling(path.fileName.toString().replace("PackageName", name)))
        } }
    }
    fun deleteTree(root: Path) {
        if (Files.exists(root)) Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
object ProjectFiles {
    fun prepare(root: Path, manifest: Manifest, config: HelperConfig, templateArchive: Path, buildArchive: Path, frameworkArchive: Path? = null, images: PackageImages = PackageImages(), check: () -> Unit = {}) {
        manifest.validate()
        Archives.extract(templateArchive, root.resolve(manifest.name), "manifest.json", check)
        Archives.replaceTemplate(root.resolve(manifest.name), manifest.name, check)
        images.copyTo(root.resolve(manifest.name))
        Files.writeString(root.resolve("${manifest.name}/manifest.json"), json.toJson(images.applyTo(manifest)))
        val build = root.resolve(".awt/helper/build-stage")
        Archives.extract(buildArchive, build, "build/runtime/constants.php", check)
        Files.list(build).use { paths -> paths.forEach { Files.move(it, root.resolve(it.fileName)) } }
        Files.delete(build)
        val constants = root.resolve("build/runtime/constants.php")
        val source = Files.readString(constants)
        val pattern = Regex("define\\('NAME',\\s*''\\);")
        require(pattern.findAll(source).count() == 1) { "Unsupported build constants: expected a single empty NAME define." }
        Files.writeString(constants, pattern.replace(source) { "define('NAME', ${phpString(manifest.name)});" })
        if (frameworkArchive != null) Archives.extract(frameworkArchive, root.resolve(".awt/framework"), "awt", check)
        config.packageName = manifest.name
        config.save(root)
        writeBuildEnvironment(root, config, "")
        Files.writeString(root.resolve(".gitignore"), ".awt/\nbuild/dev_env.json\nbuild/.file_cache.json\nreleases/\n.idea/\n")
    }
    fun writeBuildEnvironment(root: Path, config: HelperConfig, secret: String) {
        val path = root.resolve("build/dev_env.json")
        require(Files.isRegularFile(path)) { "Build environment is missing build/dev_env.json." }
        val obj = JsonParser.parseString(Files.readString(path)).asJsonObject
        obj.addProperty("release_folder", config.releaseFolder)
        obj.addProperty("build_save_format", config.buildSaveFormat)
        obj.addProperty("address", config.deploymentAddress)
        obj.addProperty("remote_install_path", config.remoteInstallPath)
        obj.addProperty("devSecret", secret)
        obj.addProperty("action", config.action)
        writeAtomic(path, json.toJson(obj))
        restrictSecrets(path)
    }
}
