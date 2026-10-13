package com.awt.helper

import com.intellij.platform.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.ide.DataManager
import com.jetbrains.php.config.interpreters.PhpInterpreter
import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.ui.*
import com.intellij.openapi.progress.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ide.util.projectWizard.SettingsStep
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import java.nio.file.*
import java.io.IOException

private const val FRAMEWORK_REPO = "Advanced-Web-Tools-Framework"
private const val TEMPLATE_REPO = "TemplatePackage"
private const val BUILD_REPO = "Package_Build_Enviornment"
data class WizardInput(
    val manifest: Manifest,
    val config: HelperConfig,
    val frameworkArchive: Path?,
    val db: DatabaseInput?,
    val images: PackageImages = PackageImages(),
    val interpreter: PhpInterpreter? = null
)
class AwtProjectGenerator : DirectoryProjectGeneratorBase<WizardInput>() {
    override fun getName() = "AWT Package"
    override fun getDescription() = "Create a package from the latest published AWT template and build environment"
    override fun getLogo() = AwtIcons.project
    override fun createPeer(): ProjectGeneratorPeer<WizardInput> = AwtWizardPeer()
    override fun generateProject(project: Project, baseDir: VirtualFile, settings: WizardInput, module: Module) {
        Php.inheritInterpreter(project, settings.interpreter)
        val root = Path.of(baseDir.path)
        object : Task.Backgroundable(project,"Create AWT package",true) {
            override fun run(indicator: ProgressIndicator) {
                var stage: Path? = null
                val created = mutableListOf<Path>()
                try {
                    indicator.text = "Resolving published template and build releases"
                    val client = ReleaseClient({ indicator.checkCanceled() }, { indicator.text = it })
                    val template = ReleaseClient.select(client.list(TEMPLATE_REPO),"Latest")
                    val build = ReleaseClient.select(client.list(BUILD_REPO),"Latest")
                    settings.config.templateVersion=template.version; settings.config.buildVersion=build.version
                    stage = Files.createTempDirectory(root.parent,".awt-create-")
                    val temp = stage!!
                    val templateZip=temp.resolve("template.zip"); val buildZip=temp.resolve("build.zip")
                    client.download(template,"template.zip",templateZip); client.download(build,"build_env.zip",buildZip)
                    val content=temp.resolve("project"); Files.createDirectory(content)
                    if(settings.config.mode != FrameworkMode.EXISTING) settings.config.frameworkPath=root.resolve(".awt/framework").toString()
                    Php.check(project,settings.config.phpExecutable,listOf("zip"),settings.config.mode != FrameworkMode.REFERENCE,indicator)
                    indicator.text="Generating project files"
                    ProjectFiles.prepare(content,settings.manifest,settings.config,templateZip,buildZip,
                        settings.frameworkArchive, settings.images) { indicator.checkCanceled() }
                    DependencyLibraries.download(content, settings.manifest.dependencies,
                        { indicator.checkCanceled() }, { indicator.text = it })
                    // Refuse every top-level collision, except .awt which is wholly owned by this new operation.
                    val top = Files.list(content).use { it.toList() }
                    top.forEach { require(!Files.exists(root.resolve(it.fileName))) { "Project creation would overwrite ${it.fileName}. Choose an empty project directory." } }
                    top.forEach { indicator.checkCanceled(); val destination=root.resolve(it.fileName); Files.move(it,destination); created.add(destination) }
                    if(settings.config.mode == FrameworkMode.INSTALLED) {
                        indicator.text="Testing database and running the AWT CLI installer"
                        Php.install(project,settings.config,root,settings.db ?: error("Database settings are missing."),indicator)
                    } else {
                        validateFramework(Path.of(settings.config.frameworkPath),settings.config.mode == FrameworkMode.EXISTING)
                    }
                    indicator.checkCanceled()
                    settings.config.save(root)
                    ApplicationManager.getApplication().invokeLater {
                        if(!project.isDisposed) runCatching {
                            baseDir.refresh(false,true)
                            Integration.sync(project,settings.config)
                            notify(project,"AWT package created. Choose AWT Build or AWT Build & Deploy; run the framework server separately.",false)
                        }.onFailure { notify(project,it.message ?: "Project files were created, but IDE integration failed. Open Settings → AWT Helper and apply settings.") }
                    }
                } catch(e: ProcessCanceledException) {
                    created.asReversed().forEach(Archives::deleteTree)
                    if(created.any { it.fileName.toString() == ".awt" }) Secrets.set(root,"development","")
                    throw e
                } catch(e: Exception) {
                    created.asReversed().forEach(Archives::deleteTree)
                    if(created.any { it.fileName.toString() == ".awt" }) Secrets.set(root,"development","")
                    notify(project,e.message ?: "AWT project creation failed. Check your network connection and PHP interpreter.")
                } finally {
                    stage?.let(Archives::deleteTree)
                    settings.frameworkArchive?.parent?.let(Archives::deleteTree)
                }
            }
        }.queue()
    }
}
class AwtWizardPeer(private var contextProject: Project? = null) : ProjectGeneratorPeer<WizardInput> {
    private val manifest = ManifestForm(contextProject)
    private val database = DatabaseForm()
    private val php = browseField(false,"Local PHP executable")
    private val interpreterHint = JBLabel("Empty: use the configured PhpStorm interpreter.")
    private val existing = JRadioButton("Use existing AWT installation",true)
    private val download = JRadioButton("Download AWT",false)
    private val path = browseField(true,"Existing AWT installation")
    private val versions = JComboBox<String>(arrayOf("Latest"))
    private val downloadButton = JButton("Download")
    private val refreshButton = JButton("Reload releases")
    private val install = JRadioButton("Install framework",true)
    private val reference = JRadioButton("Use as reference",false)
    private val next = JButton("Next")
    private val back = JButton("Back")
    private val status = JBLabel(" ")
    private val heading = JBLabel("1. Package manifest")
    private val cards = JPanel(CardLayout())
    private val frameworkCard = JPanel(CardLayout())
    private val panel = JPanel(BorderLayout(0,8))
    private var validationCallback: Runnable? = null
    private val listeners = mutableListOf<ProjectGeneratorPeer.SettingsListener>()
    private var step = 0
    @Volatile private var busy = false
    private var releases = emptyList<ReleaseClient.Release>()
    private var downloaded: Path? = null
    private var downloadedVersion: String? = null
    private var downloadedSelection: String? = null
    private var loadError: String? = null
    private var handedOff = false
    private var closed = false
    private var ownerWindow: Window? = null
    init {
        heading.icon = AwtIcons.wizard
        heading.iconTextGap = JBUI.scale(12)
        ButtonGroup().apply { add(existing); add(download) }
        ButtonGroup().apply { add(install); add(reference) }
        val framework = JPanel(BorderLayout(0,8))
        framework.add(JPanel(GridLayout(1,2,8,0)).apply { add(rounded(existing)); add(rounded(download)) },BorderLayout.NORTH)
        frameworkCard.add(Form().apply { add("Framework directory",path); add("",JBLabel("An installed framework is used in place; its configuration is not changed.")) }.panel,"existing")
        frameworkCard.add(Form().apply {
            add("Framework release",versions)
            add("",JPanel().apply { add(downloadButton); add(refreshButton) })
            add("After download",JPanel().apply { add(install); add(reference) })
            add("",JBLabel("<html>Reference mode only indexes framework classes; Run performs Build.<br>Install mode creates a local development installation.</html>"))
        }.panel,"download")
        framework.add(frameworkCard,BorderLayout.CENTER)
        framework.add(Form().apply { add("PHP interpreter",php); add("",interpreterHint) }.panel,BorderLayout.SOUTH)
        cards.add(manifest.panel,"0"); cards.add(framework,"1"); cards.add(database.panel,"2")
        panel.add(heading,BorderLayout.NORTH); panel.add(cards,BorderLayout.CENTER)
        panel.add(JPanel(BorderLayout()).apply { add(status,BorderLayout.NORTH); add(JPanel().apply { add(back); add(next) },BorderLayout.SOUTH) },BorderLayout.SOUTH)
        panel.preferredSize=Dimension(680,540)
        panel.border=JBUI.Borders.empty(8)
        panel.addHierarchyListener {
            val owner=SwingUtilities.getWindowAncestor(panel)
            if(owner != null && ownerWindow == null) {
                ownerWindow=owner
                owner.addWindowListener(object : java.awt.event.WindowAdapter() {
                    override fun windowClosed(e: java.awt.event.WindowEvent) {
                        closed=true
                        if(!handedOff) downloaded?.parent?.let(Archives::deleteTree)
                    }
                })
            }
        }
        existing.addActionListener { switchMode() }; download.addActionListener { switchMode(); loadReleases() }
        install.addActionListener { changed() }; reference.addActionListener { changed() }
        versions.addActionListener { changed() }
        path.textField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() { override fun textChanged(e: javax.swing.event.DocumentEvent) = changed() })
        php.textField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() { override fun textChanged(e: javax.swing.event.DocumentEvent) = changed() })
        manifest.listen { changed() }
        database.listen { changed() }
        back.addActionListener { step--; showStep() }
        next.addActionListener {
            try {
                if(step == 0) { manifest.read(); step=1 }
                else if(step == 1) { frameworkSettings(); step=2 }
                showStep()
            } catch(e: Exception) { status.text=e.message ?: "Invalid settings" }
        }
        downloadButton.addActionListener { downloadFramework() }
        refreshButton.addActionListener { releases=emptyList(); loadReleases() }
        showStep()
    }
    private fun rounded(button: JRadioButton) = JPanel(BorderLayout()).apply {
        border = object : javax.swing.border.AbstractBorder() {
            override fun getBorderInsets(c: Component) = JBUI.insets(10)
            override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
                g.color = UIManager.getColor("Component.borderColor") ?: UIManager.getColor("Label.foreground")
                (g as Graphics2D).apply { setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); drawRoundRect(x,y,width-1,height-1,16,16) }
            }
        }
        add(button,BorderLayout.CENTER)
    }
    private fun switchMode() { (frameworkCard.layout as CardLayout).show(frameworkCard,if(existing.isSelected) "existing" else "download"); changed() }
    private fun showStep() {
        (cards.layout as CardLayout).show(cards,step.toString())
        heading.text=listOf("1. Package manifest","2. Framework","3. Install framework")[step]
        changed()
    }
    private fun changed() {
        next.text = if (step == 0) "Next: Framework" else "Next: Installation"
        interpreterHint.text = runCatching { Php.executable(sourceProject(), "") }.fold(
            { "Empty: use PhpStorm interpreter ($it)." },
            { "Choose a local PHP executable or configure an interpreter in PhpStorm." }
        )
        back.isEnabled=step > 0 && !busy
        next.isVisible=step == 0 || step == 1 && download.isSelected && install.isSelected
        next.isEnabled=!busy && runCatching { if(step==0) manifest.read() else frameworkSettings() }.isSuccess
        refreshButton.isEnabled=!busy
        downloadButton.isEnabled=!busy && releases.isNotEmpty()
        val validation=validate()
        status.text=validation?.message ?: if(busy) "Working…" else downloadedVersion?.let { "Downloaded framework $it" } ?: " "
        listeners.forEach { it.stateChanged(validation == null) }
        validationCallback?.run()
    }
    private fun loadReleases() {
        if(busy || releases.isNotEmpty()) return
        busy=true; changed()
        object : Task.Backgroundable(null,"Load AWT releases",true) {
            private var result=emptyList<ReleaseClient.Release>()
            override fun run(indicator: ProgressIndicator) {
                try { result=ReleaseClient({indicator.checkCanceled()}).list(FRAMEWORK_REPO); loadError=null }
                catch(e: ProcessCanceledException) { throw e }
                catch(e: Exception) { loadError=e.message ?: "Cannot load releases." }
            }
            override fun onFinished() {
                busy=false; releases=result
                versions.removeAllItems(); versions.addItem("Latest"); result.forEach { versions.addItem(it.version) }
                changed()
            }
        }.queue()
    }
    private fun downloadFramework() {
        if(releases.isEmpty()) { loadReleases(); return }
        val selection=versions.selectedItem as String
        val release=ReleaseClient.select(releases,selection)
        busy=true; changed()
        object : Task.Backgroundable(null,"Download AWT ${release.version}",true) {
            private var archive: Path?=null
            override fun run(indicator: ProgressIndicator) {
                val temp=Files.createTempDirectory("awt-framework-")
                try {
                    val file=temp.resolve("framework.zip")
                    ReleaseClient({indicator.checkCanceled(); if(closed) throw ProcessCanceledException()},{indicator.text=it}).download(release,"Advanced-Web-Tools-Framework.zip",file)
                    // Validate downloaded payload before enabling Create.
                    val check=temp.resolve("validation")
                    Archives.extract(file,check,"awt") { indicator.checkCanceled() }
                    validateFramework(check,false); Archives.deleteTree(check)
                    archive=file; loadError=null
                } catch(e: ProcessCanceledException) { Archives.deleteTree(temp); throw e }
                catch(e: Exception) { Archives.deleteTree(temp); loadError=e.message ?: "Download failed." }
            }
            override fun onFinished() {
                if(closed && !handedOff) { archive?.parent?.let(Archives::deleteTree); busy=false; return }
                archive?.let { downloaded?.parent?.let(Archives::deleteTree); downloaded=it; downloadedVersion=release.version; downloadedSelection=selection }
                busy=false; changed()
            }
        }.queue()
    }
    private fun frameworkSettings(): HelperConfig {
        val mode=if(existing.isSelected) FrameworkMode.EXISTING else if(reference.isSelected) FrameworkMode.REFERENCE else FrameworkMode.INSTALLED
        if(mode==FrameworkMode.EXISTING) validateFramework(Path.of(path.text),true)
        else {
            require(downloaded != null && Files.isRegularFile(downloaded)) { loadError ?: "Select a release and click Download." }
            require(downloadedSelection == versions.selectedItem) { "Click Download for the selected version." }
        }
        Php.executable(sourceProject(), php.text.trim())
        return HelperConfig(frameworkPath=path.text.trim(),mode=mode,phpExecutable=php.text.trim(),frameworkVersion=if(mode==FrameworkMode.EXISTING) null else downloadedVersion)
    }
    override fun getComponent(locationField: TextFieldWithBrowseButton, checkValid: Runnable): JComponent { validationCallback=checkValid; return panel }
    override fun getComponent(): JComponent = panel
    private fun sourceProject(): Project? = contextProject
        ?: CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(panel))
        ?: ProjectManager.getInstance().openProjects.filter { !it.isDisposed }.singleOrNull()

    override fun buildUI(settingsStep: SettingsStep) {
        contextProject = settingsStep.context.project
        settingsStep.addSettingsComponent(panel)
        changed()
    }
    override fun getSettings(): WizardInput {
        val result = WizardInput(
            manifest.read(), frameworkSettings(), if (existing.isSelected) null else downloaded,
            if (!existing.isSelected && install.isSelected) database.read() else null,
            manifest.selectedImages(), if (php.text.isBlank()) Php.selectedInterpreter(sourceProject()) else null
        )
        handedOff=true
        if(existing.isSelected) downloaded?.parent?.let(Archives::deleteTree)
        return result
    }
    override fun validate(): ValidationInfo? = try {
        require(!busy) { "Background operation is running." }
        manifest.read()
        require(step > 0) { "Complete package manifest and click Next." }
        val c=frameworkSettings()
        if(c.mode==FrameworkMode.INSTALLED) { require(step==2) { "Click Next to configure installation." }; database.read() }
        null
    } catch(e: Exception) { ValidationInfo(e.message ?: "Invalid AWT project settings.") }
    override fun isBackgroundJobRunning() = busy
    override fun addSettingsListener(listener: ProjectGeneratorPeer.SettingsListener) { listeners.add(listener) }
}
