package com.awt.helper

import com.intellij.execution.configurations.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.Executor
import com.intellij.execution.process.*
import com.intellij.execution.ExecutionException
import com.intellij.openapi.project.Project
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.util.Key
import org.jdom.Element
import java.nio.file.Path
import java.net.*
import javax.swing.JComponent

class AwtBuildType : ConfigurationType {
    override fun getDisplayName() = "AWT Build"
    override fun getConfigurationTypeDescription() = "Build or deploy using the official AWT build environment"
    override fun getIcon() = AwtIcons.project
    override fun getId() = "AWTBuild"
    override fun getConfigurationFactories() = arrayOf(AwtFactory(this, false))
}
class AwtServerType : ConfigurationType {
    override fun getDisplayName() = "AWT Framework Server"
    override fun getConfigurationTypeDescription() = "Run the local AWT PHP development server"
    override fun getIcon() = AwtIcons.project
    override fun getId() = "AWTServer"
    override fun getConfigurationFactories() = arrayOf(AwtFactory(this, true))
}
class AwtFactory(type: ConfigurationType, private val server: Boolean) : ConfigurationFactory(type) {
    override fun getId() = if(server) "AWTServerFactory" else "AWTBuildFactory"
    override fun createTemplateConfiguration(project: Project) = AwtConfiguration(project, this, if(server) "AWT Framework Server" else "AWT Build", server)
}
class AwtConfiguration(project: Project, factory: ConfigurationFactory, name: String, val server: Boolean) : RunConfigurationBase<Any>(project,factory,name) {
    var deploy = false
    var managedId = ""
    var useProjectSettings = true
    var deploymentAction: String? = null
    var custom = HelperConfig()
    override fun readExternal(element: Element) {
        super.readExternal(element)
        deploy = element.getAttributeValue("deploy") == "true"
        managedId = element.getAttributeValue("managedId") ?: ""
        useProjectSettings = element.getAttributeValue("useProjectSettings") != "false"
        deploymentAction = element.getAttributeValue("deploymentAction")
        element.getChildText("awtOptions")?.let { custom = json.fromJson(it, HelperConfig::class.java) }
    }
    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.setAttribute("deploy", deploy.toString()); element.setAttribute("managedId", managedId)
        element.setAttribute("useProjectSettings", useProjectSettings.toString())
        deploymentAction?.let { element.setAttribute("deploymentAction", it) }
            ?: element.removeAttribute("deploymentAction")
        element.removeChildren("awtOptions")
        element.addContent(Element("awtOptions").setText(json.toJson(custom)))
    }
    fun effective(projectDefaults: HelperConfig? = null): HelperConfig {
        val base = if (useProjectSettings) {
            projectDefaults ?: HelperConfig.read(project.rootPath())
                ?: error("This project has no AWT Helper configuration.")
        } else custom
        return base.copy(action = if (server) base.action else deploymentAction ?: base.action)
    }

    internal fun deploymentCompleted(root: Path, deployed: HelperConfig, exitCode: Int, confirmed: Boolean) {
        if (server || !deploy || exitCode != 0 || !confirmed || deployed.action != "install") return
        val defaults = if (useProjectSettings) HelperConfig.read(root) else null
        val current = defaults ?: custom
        // Do not change a new deployment target selected while this process was running.
        if (current.deploymentAddress != deployed.deploymentAddress ||
            current.remoteInstallPath != deployed.remoteInstallPath) return
        if (deploymentAction != null && deploymentAction != "install") return
        if (defaults?.action == "install") defaults.copy(action = "update").save(root)
        deploymentAction = "update"
        if (!useProjectSettings) custom = custom.copy(action = "update")
    }
    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = AwtRunEditor(project, server)
    override fun checkConfiguration() {
        try {
            val config = effective()
            Php.executable(project, config.phpExecutable)
            if(server && config.mode != FrameworkMode.REFERENCE) {
                validateFramework(Path.of(config.frameworkPath), true)
                require(config.port in 1..65535 && config.host.isNotBlank()) { "Invalid server host or port." }
            } else require(java.nio.file.Files.isRegularFile(project.rootPath().resolve("build/runtime/run.php"))) { "AWT build environment is missing." }
        } catch (e: Exception) { throw RuntimeConfigurationError(e.message ?: "Invalid AWT configuration.") }
    }
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = object : CommandLineState(environment) {
        override fun startProcess(): ProcessHandler {
            val config = effective(); val root = project.rootPath()
            val startServer = server && !Commands.runIsBuild(config.mode)
            val actualDeploy = !server && deploy
            var lockChannel: java.nio.channels.FileChannel? = null
            var buildLock: java.nio.channels.FileLock? = null
            try {
                val php = Php.arguments(project, config.phpExecutable)
                val args: List<String>
                val working: Path
                if(startServer) {
                    validateFramework(Path.of(config.frameworkPath), true)
                    try { ServerSocket().use { it.reuseAddress = false; it.bind(InetSocketAddress(config.host, config.port)) } }
                    catch (e: Exception) { throw ExecutionException("Cannot bind ${config.host}:${config.port}. The port may be in use; choose another port in AWT Helper.") }
                    working = Path.of(config.frameworkPath).resolve("public")
                    args = Server.arguments(root,config)
                } else {
                    val lockFile=root.resolve(".awt/helper/build.lock")
                    java.nio.file.Files.createDirectories(lockFile.parent)
                    lockChannel=java.nio.channels.FileChannel.open(lockFile,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE)
                    buildLock=try { lockChannel!!.tryLock() } catch(e: java.nio.channels.OverlappingFileLockException) { null }
                    require(buildLock != null) { "Another AWT build or deploy is running for this project. Wait for it or stop it before starting another." }
                    ProjectFiles.writeBuildEnvironment(root, config, if(actualDeploy) Secrets.get(root,"development") else "")
                    working = root
                    // Check extensions in the child, keeping process launch nonblocking in the IDE.
                    val extensions = if(actualDeploy) listOf("zip", "curl") else listOf("zip")
                    val preflight = "foreach (" + extensions.joinToString(",", "[", "]") { phpString(it) } + " as \$e) {if(!extension_loaded(\$e)){fwrite(STDERR,'Missing PHP extension: '.\$e.PHP_EOL);exit(1);}} require 'build/runtime/run.php';"
                    args = listOf("-r", preflight, "--") + Commands.build(actualDeploy, config.fast, config.action).drop(1)
                }
                val line = GeneralCommandLine(php + args).withWorkDirectory(working.toFile()).withCharset(Charsets.UTF_8)
                val process = line.createProcess()
                val secret = if(actualDeploy) Secrets.get(root,"development") else ""
                val handler = AwtProcessHandler(process, if(startServer) "AWT Framework Server" else "AWT Build", secret)
                if(!startServer) handler.addProcessListener(object : ProcessListener {
                    override fun processTerminated(event: ProcessEvent) {
                        val complete = Runnable {
                            try {
                                if (!project.isDisposed) {
                                    deploymentCompleted(root, config, event.exitCode, actualDeploy && handler.deploymentSucceeded)
                                }
                            } catch (e: Exception) {
                                notify(project, e.message ?: "Deployment completed, but the next deployment action could not be saved.")
                            } finally {
                                buildLock?.release()
                                lockChannel?.close()
                            }
                        }
                        if (actualDeploy) ApplicationManager.getApplication().invokeLater(complete)
                        else complete.run()
                    }
                })
                if(!startServer) process.outputStream.use { it.write(Commands.input(actualDeploy).toByteArray()) }
                if(startServer && config.openBrowser) {
                    // Readiness comes from this process' startup message, not a connection to someone else's server.
                    handler.addProcessListener(object : ProcessListener {
                        private var opened = false
                        private val startup = StringBuilder()
                        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                            if(startup.length < 8192) startup.append(event.text)
                            if (!opened && startup.contains("Development Server") && startup.contains("started")) {
                                opened = true
                                ApplicationManager.getApplication().invokeLater { if(!handler.isProcessTerminated) BrowserUtil.browse(Server.browserUrl(config)) }
                            }
                        }
                    })
                }
                return handler
            } catch(e: ExecutionException) { buildLock?.release(); lockChannel?.close(); throw e }
            catch(e: Exception) { buildLock?.release(); lockChannel?.close(); throw ExecutionException(e.message ?: "Cannot start AWT operation.") }
        }
    }
}
class AwtRunEditor(private val project: Project, private val server: Boolean) : SettingsEditor<AwtConfiguration>() {
    private val form = ConfigForm(project, showFramework = server)
    private val useProject = com.intellij.ui.components.JBCheckBox("Use AWT Helper project settings", true)
    private val mode = javax.swing.JComboBox(arrayOf("Build", "Build & Deploy"))
    private val panel = javax.swing.JPanel(java.awt.BorderLayout()).apply {
        add(javax.swing.JPanel().apply { add(useProject); if(!server) add(mode) }, java.awt.BorderLayout.NORTH)
        add(form.panel, java.awt.BorderLayout.CENTER)
    }
    init {
        form.path.isEnabled=false; form.mode.isEnabled=false
        form.path.toolTipText="Change framework path and mode in Settings → AWT Helper."
        form.mode.toolTipText=form.path.toolTipText
        useProject.addActionListener { updateOverrides() }
        mode.addActionListener { updateOverrides() }
        if (!server) panel.add(com.intellij.ui.components.JBLabel(
            "<html>Deployment action is saved for this run configuration.<br>" +
                "After a successful install, the next deployment uses update.</html>"
        ), java.awt.BorderLayout.SOUTH)
    }
    private fun updateOverrides() {
        listOf(form.php, form.host, form.port, form.browser, form.release, form.format,
            form.address, form.endpoint, form.fast).forEach { it.isEnabled = !useProject.isSelected }
        form.action.isEnabled = !server && mode.selectedIndex == 1
    }
    override fun createEditor(): JComponent = panel
    override fun resetEditorFrom(s: AwtConfiguration) {
        useProject.isSelected = s.useProjectSettings; mode.selectedIndex = if(s.deploy) 1 else 0
        form.load(s.effective(HelperConfig.read(project.rootPath()) ?: s.custom))
        form.secret.text = Secrets.get(project.rootPath(),"development")
        updateOverrides()
    }
    override fun applyEditorTo(s: AwtConfiguration) {
        s.useProjectSettings = useProject.isSelected; s.deploy = mode.selectedIndex == 1
        s.deploymentAction = if (server) null else form.action.selectedItem as String
        if(!s.useProjectSettings) {
            val projectConfig=HelperConfig.read(project.rootPath()) ?: s.custom
            s.custom = form.read(projectConfig).copy(mode=projectConfig.mode,frameworkPath=projectConfig.frameworkPath,hostnamePath=projectConfig.hostnamePath)
        }
        val updatedSecret=String(form.secret.password)
        val root=project.rootPath()
        val projectConfig=HelperConfig.read(root)
        if(updatedSecret != Secrets.get(root,"development")) {
            if(projectConfig?.mode == FrameworkMode.INSTALLED) Php.configureDevelopment(projectConfig,root,updatedSecret)
            else Secrets.set(root,"development",updatedSecret)
        }
    }
}

/** Preflight PHP and database checks are cancellable background work. */
class AwtRunner : com.intellij.execution.runners.AsyncProgramRunner<RunnerSettings>() {
    override fun getRunnerId() = "AWTHelperRunner"
    override fun canRun(executorId: String, profile: RunProfile) = executorId == com.intellij.execution.executors.DefaultRunExecutor.EXECUTOR_ID && profile is AwtConfiguration
    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): org.jetbrains.concurrency.Promise<com.intellij.execution.ui.RunContentDescriptor?> {
        val promise=org.jetbrains.concurrency.AsyncPromise<com.intellij.execution.ui.RunContentDescriptor?>()
        val configuration=environment.runProfile as AwtConfiguration
        object : com.intellij.openapi.progress.Task.Backgroundable(environment.project,"Prepare AWT operation",true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                try {
                    val c=configuration.effective()
                    val serve=configuration.server && c.mode != FrameworkMode.REFERENCE
                    if(configuration.deploy && !configuration.server) require(Secrets.get(environment.project.rootPath(),"development").isNotBlank()) { "Set a development secret in Settings → AWT Helper before deploying." }
                    Php.check(environment.project,c.phpExecutable,if(serve) listOf("PDO") else if(configuration.deploy && !configuration.server) listOf("zip","curl") else listOf("zip"),serve,indicator)
                    if(serve) {
                        c.hostnamePath=Php.sitePath(environment.project,c,indicator)
                        if(configuration.useProjectSettings) c.save(environment.project.rootPath())
                        else configuration.custom.hostnamePath=c.hostnamePath
                    }
                    indicator.checkCanceled()
                } catch(e: Exception) { promise.setError(ExecutionException(e.message ?: "AWT preflight failed.")); throw e }
            }
            override fun onSuccess() {
                try {
                    val result=state.execute(environment.executor,environment.runner) ?: throw ExecutionException("AWT execution returned no process.")
                    promise.setResult(com.intellij.execution.runners.RunContentBuilder(result,environment).showRunContent(environment.contentToReuse))
                } catch(e: Exception) { promise.setError(e) }
            }
            override fun onCancel() { promise.setError(ExecutionException("AWT operation cancelled.")) }
            override fun onThrowable(error: Throwable) { promise.setError(error) }
        }.queue()
        return promise
    }
}
