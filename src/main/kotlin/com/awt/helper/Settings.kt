package com.awt.helper

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import javax.swing.JComponent
import com.intellij.ui.components.JBScrollPane
import java.nio.file.*

class AwtSettings(private val project: Project) : Configurable {
    private var form: ConfigForm? = null
    private var baseline: HelperConfig? = null
    private var originalSecret = ""
    private var running = false
    override fun getDisplayName() = "AWT Helper"
    override fun createComponent(): JComponent {
        val f = ConfigForm(project); form = f
        reset()
        return JBScrollPane(f.panel)
    }
    override fun reset() {
        val root = project.basePath?.let { Path.of(it) }
        baseline = root?.let { HelperConfig.read(it) } ?: HelperConfig()
        originalSecret = root?.let { Secrets.get(it,"development") } ?: ""
        form?.load(baseline!!); form?.secret?.text = originalSecret
    }
    override fun isModified(): Boolean = runCatching { form?.read(baseline!!) != baseline || String(form!!.secret.password) != originalSecret }.getOrDefault(true)
    override fun apply() {
        if(running) throw ConfigurationException("An AWT Helper operation is still running.")
        val f = form ?: return
        val config = try { f.read(baseline ?: HelperConfig()) } catch(e: Exception) { throw ConfigurationException(e.message ?: "Invalid settings.") }
        val root = project.basePath?.let { Path.of(it) } ?: throw ConfigurationException("Open a local AWT project before applying settings.")
        val framework = try { Path.of(config.frameworkPath).toAbsolutePath().normalize() } catch(e: Exception) { throw ConfigurationException("Select a framework directory.") }
        var db: DatabaseInput? = null
        try {
            validateFramework(framework,config.mode == FrameworkMode.EXISTING)
            if(config.mode == FrameworkMode.INSTALLED) {
                require(framework == root.resolve(".awt/framework").toAbsolutePath().normalize()) { "Installed mode is only available for the downloaded framework in .awt/framework. Use EXISTING for an external installation." }
                if(runCatching { validateFramework(framework,true) }.isFailure) {
                    val database = DatabaseForm()
                    val dialog = object : DialogWrapper(project) {
                        init { title = "Install AWT Framework"; init() }
                        override fun createCenterPanel(): JComponent = database.panel
                        override fun doValidate() = runCatching { database.read(); null }.getOrElse { com.intellij.openapi.ui.ValidationInfo(it.message ?: "Invalid database configuration.") }
                    }
                    if(!dialog.showAndGet()) throw ConfigurationException("Installation was cancelled; framework mode has not changed.")
                    db = database.read()
                }
            }
        } catch(e: ConfigurationException) { throw e }
        catch(e: Exception) { throw ConfigurationException(e.message ?: "Invalid framework directory.") }
        val enteredSecret = String(f.secret.password)
        val database = db
        running = true
        object : Task.Backgroundable(project,"Apply AWT Helper settings",true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    Php.check(project,config.phpExecutable,emptyList(),config.mode != FrameworkMode.REFERENCE,indicator)
                    if(database != null) Php.install(project,config,root,database,indicator)
                    else {
                        if(config.mode == FrameworkMode.INSTALLED && baseline?.mode != FrameworkMode.INSTALLED) {
                            config.hostnamePath = Php.sitePath(project,config,indicator)
                            Php.enableDevelopment(config,root)
                        }
                        if(config.mode != FrameworkMode.INSTALLED || baseline?.mode == FrameworkMode.INSTALLED) {
                            if(config.mode == FrameworkMode.INSTALLED) Php.configureDevelopment(config,root,enteredSecret)
                            else {
                                Secrets.set(root,"development",enteredSecret)
                                ProjectFiles.writeBuildEnvironment(root,config,enteredSecret)
                            }
                        }
                    }
                    indicator.checkCanceled()
                    ApplicationManager.getApplication().invokeLater {
                        if(!project.isDisposed) runCatching { Integration.sync(project,config); reset(); notify(project,"AWT Helper settings updated.",false) }.onFailure { notify(project,it.message ?: "Cannot synchronize AWT settings.") }
                    }
                } catch(e: com.intellij.openapi.progress.ProcessCanceledException) { throw e }
                catch(e: Exception) { notify(project,e.message ?: "Cannot apply AWT Helper settings.") }
                finally { running=false }
            }
        }.queue()
    }
    override fun disposeUIResources() { form=null }
}
