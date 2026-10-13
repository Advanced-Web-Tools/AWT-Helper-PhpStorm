package com.awt.helper

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.jetbrains.php.config.library.PhpIncludePathManager
import com.intellij.execution.RunManager
import java.nio.file.Path

object Secrets {
    private fun attributes(root: Path, key: String) = CredentialAttributes("AWT Helper:${root.toAbsolutePath().normalize()}:$key", null, null, false, false)
    fun get(root: Path, key: String): String = PasswordSafe.instance.getPassword(attributes(root,key)) ?: ""
    fun set(root: Path, key: String, value: String) { PasswordSafe.instance.setPassword(attributes(root,key), value.ifBlank { null }) }
}
fun Project.rootPath(): Path = Path.of(basePath ?: error("Project has no local directory."))
fun notify(project: Project?, message: String, error: Boolean = true) {
    NotificationGroupManager.getInstance().getNotificationGroup("AWT Helper").createNotification(message, if(error) NotificationType.ERROR else NotificationType.INFORMATION).notify(project)
}
object Integration {
    /** Uses the PHP library model rather than ordinary content roots. Keeps user's include paths. */
    fun sync(project: Project, config: HelperConfig, root: Path = project.rootPath()) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val framework = Path.of(config.frameworkPath).toAbsolutePath().normalize()
        require(framework != root.toAbsolutePath().normalize()) { "The framework directory must be separate from the package project directory." }
        validateFramework(framework, config.mode != FrameworkMode.REFERENCE)
        val dependencyPaths = DependencyLibraries.libraryPaths(root).map {
            it.toAbsolutePath().normalize().toString().replace('\\', '/')
        }
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(framework)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(".awt"))
        ApplicationManager.getApplication().runWriteAction {
            for (module in ModuleManager.getInstance(project).modules) {
                val model = ModuleRootManager.getInstance(module).modifiableModel
                try {
                    model.contentEntries.forEach { entry ->
                        if (entry.file?.path == root.toString().replace('\\','/')) {
                            val oldExclusion = config.managedContentExclusion
                            val extraExclusion = if(framework.startsWith(root) && !framework.startsWith(root.resolve(".awt"))) VfsUtil.pathToUrl(framework.toString()) else null
                            if(oldExclusion != null && oldExclusion != extraExclusion) entry.excludeFolders.filter { it.url == oldExclusion }.forEach { entry.removeExcludeFolder(it) }
                            if(extraExclusion != null && entry.excludeFolderUrls.none { it == extraExclusion }) { entry.addExcludeFolder(extraExclusion); config.managedContentExclusion=extraExclusion }
                            else if(extraExclusion == null) config.managedContentExclusion=null
                            val excluded = VfsUtil.pathToUrl(root.resolve(".awt").toString())
                            if (entry.excludeFolderUrls.none { it == excluded }) entry.addExcludeFolder(excluded)
                        }
                    }
                    model.commit()
                } finally { if (!model.isDisposed) model.dispose() }
            }
            val manager = PhpIncludePathManager.getInstance(project)
            val newPath = framework.toString().replace('\\','/')
            val oldPath = config.managedIncludePath
            val includes = manager.includePath.toMutableList()
            if (oldPath != null && oldPath != newPath) includes.removeAll { it == oldPath }
            if (newPath !in includes) { includes.add(newPath); config.managedIncludePath = newPath }
            // If a user already registered it, don't claim ownership or remove it later.
            val ownedDependencies = config.managedDependencyIncludePaths
            includes.removeAll(ownedDependencies.filter { it !in dependencyPaths }.toSet())
            val addedDependencies = dependencyPaths.filter { it !in includes }
            includes.addAll(addedDependencies)
            config.managedDependencyIncludePaths =
                ownedDependencies.filter { it in dependencyPaths } + addedDependencies
            manager.includePath = includes.distinct()
        }
        createConfigurations(project, config)
        config.save(root)
    }
    private fun createConfigurations(project: Project, config: HelperConfig) {
        val manager = RunManager.getInstance(project)
        val types = com.intellij.execution.configurations.ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
        fun ensure(id: String, name: String, managedId: String, deploy: Boolean): com.intellij.execution.RunnerAndConfigurationSettings {
            manager.allSettings.firstOrNull { (it.configuration as? AwtConfiguration)?.managedId == managedId }?.let { return it }
            val type = types.first { it.id == id }
            val settings = manager.createConfiguration(name, type.configurationFactories.single())
            (settings.configuration as AwtConfiguration).apply { this.deploy = deploy; this.managedId = managedId }
            settings.configuration.beforeRunTasks = emptyList()
            manager.addConfiguration(settings)
            return settings
        }
        val build = ensure("AWTBuild", "AWT Build", "build", false)
        ensure("AWTBuild", "AWT Build & Deploy", "deploy", true)
        val server = manager.allSettings.firstOrNull { (it.configuration as? AwtConfiguration)?.managedId == "server" }
        if (config.mode == FrameworkMode.REFERENCE) {
            if (server != null) manager.removeConfiguration(server)
            if (manager.selectedConfiguration == null || manager.selectedConfiguration == server) manager.selectedConfiguration = build
        } else {
            val run = ensure("AWTServer", "AWT Framework Server", "server", false)
            if (manager.selectedConfiguration == null || manager.selectedConfiguration == build) manager.selectedConfiguration = run
        }
    }
}
class AwtStartup : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        try {
            val config = HelperConfig.read(project.rootPath()) ?: return
            ApplicationManager.getApplication().invokeLater { if(!project.isDisposed) runCatching { Integration.sync(project, config) }.onFailure { notify(project, it.message ?: "Cannot synchronize AWT Helper.") } }
        } catch (e: Exception) { notify(project, e.message ?: "Cannot read AWT Helper configuration.") }
    }
}
