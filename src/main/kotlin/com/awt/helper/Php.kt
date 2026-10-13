package com.awt.helper

import com.intellij.openapi.project.Project
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.config.interpreters.PhpInterpreter
import com.jetbrains.php.config.interpreters.PhpInterpretersManagerImpl
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.progress.ProgressIndicator
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

object Php {
    internal fun selectedInterpreter(project: Project?): PhpInterpreter? {
        val defaults = ProjectManager.getInstance().defaultProject
        val selected = project?.let { PhpProjectConfigurationFacade.getInstance(it).interpreter }
            ?: PhpProjectConfigurationFacade.getInstance(defaults).interpreter
        return selected ?: PhpInterpretersManagerImpl.getInstance(project ?: defaults)
            .interpreters.filter { !it.isRemote }.singleOrNull()
    }

    fun inheritInterpreter(project: Project, source: PhpInterpreter?) {
        if (source == null) return
        val manager = PhpInterpretersManagerImpl.getInstance(project)
        val interpreter = manager.findInterpreterById(source.id) ?: source.clone().also {
            it.name = manager.getUniqueName(source.name)
            // The default project has no project-level interpreter registry.
            it.setIsProjectLevel(!project.isDefault)
            manager.addInterpreter(it)
        }
        val facade = PhpProjectConfigurationFacade.getInstance(project)
        facade.projectConfiguration.interpreterName = interpreter.name
        facade.fireStateChanged()
    }
    fun executable(project: Project?, explicit: String): String {
        if (explicit.isNotBlank()) return explicit
        val interpreter = selectedInterpreter(project)
            ?: error("No PHP interpreter configured. Select a local PHP executable in AWT Helper.")
        require(!interpreter.isRemote) { "AWT Helper supports local PHP interpreters only. Select a local executable." }
        return interpreter.pathToPhpExecutable ?: error("Configured PHP interpreter has no executable.")
    }
    fun arguments(project: Project?, explicit: String): List<String> {
        val exe = executable(project, explicit)
        val selected = selectedInterpreter(project)
        return if (explicit.isBlank() && !selected?.customIni.isNullOrBlank()) listOf(exe, "-c", selected!!.customIni) else listOf(exe)
    }
    fun capture(command: List<String>, directory: Path?, input: String = "", indicator: ProgressIndicator? = null, timeoutSeconds: Long = 60): String {
        val p = ProcessBuilder(command).apply { if (directory != null) directory(directory.toFile()); redirectErrorStream(true) }.start()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<String> {
                p.inputStream.use { stream ->
                    val result = StringBuilder(); val buffer = ByteArray(8192)
                    while (true) { val n = stream.read(buffer); if (n < 0) break; if (result.length < 1024 * 1024) result.append(String(buffer, 0, n, Charsets.UTF_8)) }
                    result.toString()
                }
            }
            p.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (!p.waitFor(100, TimeUnit.MILLISECONDS)) {
                indicator?.checkCanceled()
                require(System.nanoTime() < deadline) { "PHP operation timed out." }
            }
            val out = future.get(5, TimeUnit.SECONDS)
            require(p.exitValue() == 0) { "PHP operation failed. Check interpreter configuration and required PHP extensions." }
            return out
        } finally { if (p.isAlive) p.destroyForcibly(); executor.shutdownNow() }
    }
    fun check(project: Project?, explicit: String, extensions: List<String>, framework: Boolean = false, indicator: ProgressIndicator? = null) {
        val out = capture(arguments(project, explicit) + listOf("-r", "echo json_encode(['version'=>PHP_VERSION_ID,'extensions'=>get_loaded_extensions(),'readline'=>function_exists('readline')]);"), null, indicator = indicator)
        val info = com.google.gson.JsonParser.parseString(out.trim()).asJsonObject
        require(info["version"].asInt >= if(framework) 80200 else 80000) { if(framework) "This AWT release requires PHP 8.2+ (readonly classes)." else "Build environment requires PHP 8.0+." }
        val loaded = info["extensions"].asJsonArray.map { it.asString.lowercase() }
        val missing = extensions.filter { it.lowercase() !in loaded }
        require(missing.isEmpty()) { "Missing PHP extensions: ${missing.joinToString()}. Enable them for the selected CLI interpreter." }
        if ("readline" in extensions) require(info["readline"].asBoolean) { "AWT CLI installer requires PHP readline support." }
    }
    fun testDatabase(project: Project?, explicit: String, db: DatabaseInput, indicator: ProgressIndicator?) {
        db.validate()
        check(project, explicit, listOf("PDO", "pdo_mysql", "readline"), true, indicator)
        val script = "\$c=json_decode(stream_get_contents(STDIN),true);try {new PDO('mysql:host='.\$c['host'].';port='.\$c['port'].';dbname='.\$c['name'],\$c['username'],\$c['password'],[PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION,PDO::ATTR_TIMEOUT=>5]);echo 'OK';}catch(Throwable \$e){echo 'Connection failed (code '.\$e->getCode().'). Verify hostname, port, database, credentials and PDO MySQL configuration.';}"
        val result = capture(arguments(project, explicit) + listOf("-r", script), null, json.toJson(db), indicator)
        require(result == "OK") { result }
    }
    fun sitePath(project: Project?, config: HelperConfig, indicator: ProgressIndicator?): String {
        val dbFile = Path.of(config.frameworkPath).resolve("awt_data/config/awt_db.php")
        val script = "require " + phpString(dbFile.toString()) + ";try {\$pdo=new PDO(DB_TYPE.':host='.DB_HOSTNAME.';dbname='.DB_NAME,DB_USERNAME,DB_PASSWORD,[PDO::ATTR_TIMEOUT=>5,PDO::ATTR_ERRMODE=>PDO::ERRMODE_EXCEPTION]);\$q=\$pdo->query(\"SELECT value FROM awt_setting WHERE package_id=1 AND name='Hostname Path'\");echo json_encode(['path'=>\$q->fetchColumn()?:'/']);}catch(Throwable \$e){echo json_encode(['error'=>'Cannot read the AWT website path. Check database connectivity and the local PHP PDO driver.']);}"
        val result = capture(arguments(project,config.phpExecutable) + listOf("-r",script),null,indicator=indicator)
        val data = com.google.gson.JsonParser.parseString(result).asJsonObject
        require(!data.has("error")) { data["error"].asString }
        return data["path"].asString
    }
    fun install(project: Project?, config: HelperConfig, root: Path, db: DatabaseInput, indicator: ProgressIndicator) {
        testDatabase(project, config.phpExecutable, db, indicator)
        val framework = Path.of(config.frameworkPath)
        validateFramework(framework, false)
        // Supply the original CLI command via its stdin parser, keeping credentials out of process arguments.
        val output = capture(arguments(project, config.phpExecutable) + "awt", framework, Commands.installerInput(db), indicator, 300)
        require(output.contains("Installation completed.")) { "AWT CLI installation did not complete. Verify the database permissions and PHP extensions. The existing database may contain partially created AWT tables." }
        validateFramework(framework, true)
        config.websiteName = db.website; config.hostnamePath = db.hostPath
        config.databaseHostname=db.host; config.databasePort=db.port; config.databaseName=db.name; config.databaseUsername=db.username
        enableDevelopment(config, root)
    }
    fun enableDevelopment(config: HelperConfig, root: Path) {
        val secret = java.security.SecureRandom().let { random -> ByteArray(32).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) } }
        config.deploymentAddress = "http://${config.host}:${config.port}"
        config.remoteInstallPath = "/dev/install"
        configureDevelopment(config, root, secret)
    }
    fun configureDevelopment(config: HelperConfig, root: Path, secret: String) {
        require(secret.isNotBlank()) { "Development secret must not be empty for an installed framework." }
        val framework = Path.of(config.frameworkPath).toAbsolutePath().normalize()
        require(framework == root.resolve(".awt/framework").toAbsolutePath().normalize()) { "Development setup only modifies the framework downloaded into this project." }
        validateFramework(framework, true)
        val path = framework.resolve("awt_data/config/awt_config.php")
        var source = java.nio.file.Files.readString(path)
        for ((key, value) in mapOf("DEBUG" to "true", "REMOTE_INSTALL_FOR_DEVS" to "true", "DEV_SECRET" to phpString(secret))) {
            val pattern = Regex("const\\s+$key\\s*=\\s*[^;]+;")
            require(pattern.findAll(source).count() == 1) { "Unsupported AWT config: missing $key." }
            source = pattern.replace(source) { "const $key = $value;" }
        }
        if (!source.contains(Regex("(?:define\\(\\s*[\'\"]DEV[\'\"]|const\\s+DEV\\s*=)"))) source += "\n// Local development entry point: bootstrap/boot.php checks defined(DEV).\nif (!defined(\'DEV\')) define(\'DEV\', true);\n"
        writeAtomic(path, source)
        restrictSecrets(path)
        restrictSecrets(framework.resolve("awt_data/config/awt_db.php"))
        Secrets.set(root, "development", secret)
        ProjectFiles.writeBuildEnvironment(root, config, secret)
    }
}
