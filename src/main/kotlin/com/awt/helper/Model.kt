package com.awt.helper

import com.google.gson.*
import java.nio.file.*

val json: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
enum class FrameworkMode { EXISTING, REFERENCE, INSTALLED }
data class Dependency(val name: String = "", val version: String = "*", val url: String = "")
data class Manifest(
    var name: String = "PackageName", var version: String = "1.0.0",
    var minimum_awt_version: String = "27.0.0", var maximum_awt_version: String? = null,
    var author: String? = "Your Name", var type: Int = 1, var system_package: Int = 0,
    var description: String? = null, var icon: String? = null, var preview_image: String? = null,
    var license: String? = null, var license_url: String? = null,
    var dependencies: List<Dependency> = emptyList()
) {
    fun validate() {
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) {
            "Package name must start with a letter and contain letters, digits or underscores (AWT folders and PHP identifiers)."
        }
        require(name.uppercase() !in setOf("CON", "PRN", "AUX", "NUL", "CLOCK$", *(1..9).flatMap { listOf("COM$it", "LPT$it") }.toTypedArray())) { "Reserved Windows folder name." }
        require(name.lowercase() !in setOf("class", "trait", "interface", "enum", "function", "namespace", "self", "parent", "static", "match", "readonly", "extends", "implements", "abstract", "final", "public", "protected", "private", "const", "new", "null", "true", "false", "int", "string", "bool", "float", "object", "mixed", "never", "void", "iterable", "array", "callable")) { "Package name is a reserved PHP identifier." }
        require(name.lowercase() !in setOf("build", "releases")) { "Package name conflicts with the build environment project folders." }
        require(name.length <= 128) { "Package name is too long." }
        listOf(version, minimum_awt_version).forEach { require(it.matches(VERSION)) { "Version must be an AWT version, e.g. 1.0.0 or 1.0.0-beta." } }
        maximum_awt_version?.let { require(it.matches(VERSION)) { "Invalid maximum AWT version." } }
        require(type in 0..2) { "Package type must be 0, 1 or 2." }
        require(system_package in 0..1) { "System package must be 0 or 1." }
        val seen = mutableSetOf<String>()
        dependencies.forEach {
            require(it.name.isNotBlank() && it.name != name && seen.add(it.name)) { "Dependency names must be nonempty, unique and different from this package." }
            require(it.version == "*" || it.version.trim().split(Regex("\\s+")).all { part -> part.matches(CONSTRAINT) }) { "Unsupported dependency constraint: ${it.version}. Use *, exact versions or comparisons separated by spaces." }
        }
    }
    companion object {
        val VERSION = Regex("\\d+(?:\\.\\d+){0,2}(?:-[0-9A-Za-z.-]+)?")
        val CONSTRAINT = Regex("(?:>=|<=|>|<|==|=|!=)?\\d+(?:\\.\\d+){0,2}(?:-[0-9A-Za-z.-]+)?")
        fun read(text: String): Manifest {
            val obj = JsonParser.parseString(text).asJsonObject
            for (key in listOf("name", "version", "minimum_awt_version")) require(obj.has(key) && obj[key].isJsonPrimitive && obj[key].asJsonPrimitive.isString) { "Manifest requires string $key." }
            require(obj.has("type") && obj["type"].isJsonPrimitive && obj["type"].asJsonPrimitive.isNumber && obj["type"].asString in listOf("0", "1", "2")) { "Invalid manifest type." }
            require(obj.has("dependencies") && obj["dependencies"].isJsonArray) { "Dependencies must be a list." }
            for (key in listOf("maximum_awt_version", "author", "description", "icon", "preview_image", "license", "license_url")) {
                obj[key]?.let { require(it.isJsonNull || it.isJsonPrimitive && it.asJsonPrimitive.isString) { "$key must be a string or null." } }
            }
            obj["system_package"]?.let {
                require(it.isJsonPrimitive && (it.asJsonPrimitive.isBoolean || it.asJsonPrimitive.isNumber && it.asString in listOf("0", "1"))) { "Invalid system_package." }
                if (it.asJsonPrimitive.isBoolean) obj.addProperty("system_package", if(it.asBoolean) 1 else 0)
            }
            obj["dependencies"].asJsonArray.forEach { d ->
                require(d.isJsonObject && d.asJsonObject["name"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } == true) { "Dependency requires string name." }
                for (k in listOf("version", "url")) d.asJsonObject[k]?.let { require(it.isJsonNull || it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Dependency $k must be a string." } }
                if (d.asJsonObject["version"]?.isJsonNull != false) d.asJsonObject.addProperty("version", "*")
                if (d.asJsonObject["url"]?.isJsonNull != false) d.asJsonObject.addProperty("url", "")
            }
            return json.fromJson(obj, Manifest::class.java).also { it.validate() }
        }
    }
}
data class HelperConfig(
    var schema: Int = 1, var packageName: String = "", var frameworkPath: String = "",
    var mode: FrameworkMode = FrameworkMode.EXISTING, var phpExecutable: String = "",
    var frameworkVersion: String? = null, var templateVersion: String = "", var buildVersion: String = "",
    var host: String = "localhost", var port: Int = 3000, var openBrowser: Boolean = true,
    var websiteName: String = "", var hostnamePath: String = "/",
    var databaseHostname: String? = null, var databasePort: Int? = null,
    var databaseName: String? = null, var databaseUsername: String? = null,
    var releaseFolder: String = "releases", var buildSaveFormat: String = "<name>-<version>",
    var deploymentAddress: String = "http://localhost:3000", var remoteInstallPath: String = "/dev/install",
    var fast: Boolean = false, var action: String = "install", var managedIncludePath: String? = null,
    var managedContentExclusion: String? = null,
    var managedDependencyIncludePaths: List<String> = emptyList()
) {
    fun save(root: Path) {
        val file = root.resolve(".awt/helper/helper.json")
        Files.createDirectories(file.parent)
        writeAtomic(file, json.toJson(this))
    }
    companion object {
        fun read(root: Path): HelperConfig? = root.resolve(".awt/helper/helper.json").takeIf { Files.isRegularFile(it) }?.let { json.fromJson(Files.readString(it), HelperConfig::class.java) }
    }
}
data class DatabaseInput(val host: String, val port: Int, val name: String, val username: String, val password: String, val website: String, val hostPath: String) {
    fun validate() {
        require(host.matches(Regex("[A-Za-z0-9.:-]+"))) { "Invalid database hostname." }
        require(port in 1..65535) { "Database port must be 1–65535." }
        require(name.isNotBlank() && !name.contains(';') && username.isNotBlank() && website.isNotBlank()) { "Database name, username and website name are required." }
        require(hostPath.startsWith('/') && !hostPath.contains('?') && !hostPath.contains('#') && !hostPath.contains("..") && !hostPath.contains('\\') && !hostPath.contains('\n')) { "Website hostname path must be a URL path beginning with /." }
    }
    // Framework uses mysql:host=<host>;dbname=<name> with no separate port argument.
    fun installerArguments() = listOf("install", "mysql", name, "$host;port=$port", username, password, website, hostPath)
}
object Commands {
    fun build(deploy: Boolean, fast: Boolean, action: String = "install") = buildList {
        add("build/runtime/run.php")
        if (deploy) { add("--install"); add("--action=$action") }
        if (fast) add("--fast")
    }
    fun input(deploy: Boolean) = if (deploy) "" else "n\n"
    fun installerInput(db: DatabaseInput): String = db.installerArguments().joinToString(" ") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } + "\nn\nn\n"
    fun server(config: HelperConfig) = listOf("-S", "${config.host}:${config.port}")
    fun runIsBuild(mode: FrameworkMode) = mode == FrameworkMode.REFERENCE
}
fun phpString(value: String) = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
fun validateFramework(path: Path, installed: Boolean) {
    for (file in listOf("awt", "public/index.php", "bootstrap/boot.php", "awt_src/classes/package/manifest/reader/ManifestReader.php", "awt_data/config/awt_db.php", "awt_data/config/awt_config.php")) {
        require(Files.isRegularFile(path.resolve(file))) { "Invalid AWT installation: missing $file." }
    }
    if (installed) {
        val source=Files.readString(path.resolve("awt_data/config/awt_db.php"))
        val databaseType=Regex("const\\s+DB_TYPE\\s*=\\s*(['\"])([^'\"]*)\\1\\s*;").find(source)?.groupValues?.get(2)
        require(!databaseType.isNullOrBlank()) { "Framework is not installed or its DB_TYPE configuration is invalid." }
    }
}

fun writeAtomic(path: Path, content: String) {
    Files.createDirectories(path.parent)
    val temporary = Files.createTempFile(path.parent,".awt-helper-", ".tmp")
    try {
        Files.writeString(temporary,content)
        try { Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
        catch(e: AtomicMoveNotSupportedException) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temporary) }
}
fun restrictSecrets(path: Path) {
    if(Files.getFileStore(path).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
}
