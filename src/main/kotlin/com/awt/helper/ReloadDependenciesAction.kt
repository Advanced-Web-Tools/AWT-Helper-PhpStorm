package com.awt.helper

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import java.nio.file.Files
import java.nio.file.Path

class ReloadDependenciesAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val root = event.project?.basePath?.let(Path::of)
        event.presentation.isEnabledAndVisible = root != null &&
            Files.isRegularFile(root.resolve(".awt/helper/helper.json"))
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val root = project.rootPath()
        object : Task.Backgroundable(project, "Reload AWT dependencies", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val config = HelperConfig.read(root) ?: error("This project has no AWT Helper configuration.")
                    val packageDirectory = root.resolve(config.packageName).normalize()
                    require(config.packageName.isNotBlank() && packageDirectory.parent == root.normalize()) {
                        "The AWT Helper package directory is invalid."
                    }
                    val manifest = Manifest.read(Files.readString(packageDirectory.resolve("manifest.json")))
                    DependencyLibraries.reload(root, manifest.dependencies,
                        { indicator.checkCanceled() }, { indicator.text = it })
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            runCatching {
                                // Use the latest settings if they changed during the download.
                                Integration.sync(project, HelperConfig.read(root) ?: config)
                                notify(project, "AWT dependencies reloaded.", false)
                            }.onFailure {
                                notify(project, it.message ?: "Dependencies downloaded, but PHP library integration failed.")
                            }
                        }
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    notify(project, e.message ?: "Cannot reload AWT dependencies.")
                }
            }
        }.queue()
    }
}
