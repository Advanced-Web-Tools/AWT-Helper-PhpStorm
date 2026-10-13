package com.awt.helper

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.jetbrains.php.config.PhpProjectConfigurationFacade
import com.jetbrains.php.config.interpreters.PhpInterpreter
import com.jetbrains.php.config.interpreters.PhpInterpretersManagerImpl
import java.awt.Container
import java.nio.file.Files
import javax.swing.JButton

class PhpInterpreterTest : BasePlatformTestCase() {
    fun testBlankWizardInterpreterUsesCurrentProjectAndCanBeInherited() {
        val sourceManager = PhpInterpretersManagerImpl.getInstance(project)
        val defaults = ProjectManager.getInstance().defaultProject
        val targetManager = PhpInterpretersManagerImpl.getInstance(defaults)
        val sourceFacade = PhpProjectConfigurationFacade.getInstance(project)
        val targetFacade = PhpProjectConfigurationFacade.getInstance(defaults)
        val sourceInterpreters = sourceManager.interpreters.toList()
        val targetInterpreters = targetManager.interpreters.toList()
        val sourceName = sourceFacade.projectConfiguration.interpreterName
        val targetName = targetFacade.projectConfiguration.interpreterName
        val framework = Files.createTempDirectory("awt-interpreter-wizard-")
        try {
            val interpreter = PhpInterpreter().apply {
                name = "AWT wizard test interpreter"
                homePath = "/fake/local/php"
                customIni = "/fake/local/php.ini"
                setIsProjectLevel(true)
            }
            sourceManager.addInterpreter(interpreter)
            sourceFacade.projectConfiguration.interpreterName = interpreter.name
            assertEquals(listOf("/fake/local/php", "-c", "/fake/local/php.ini"), Php.arguments(project, ""))
            for (file in listOf("awt", "public/index.php", "bootstrap/boot.php",
                "awt_src/classes/package/manifest/reader/ManifestReader.php",
                "awt_data/config/awt_db.php", "awt_data/config/awt_config.php")) {
                val path = framework.resolve(file)
                Files.createDirectories(path.parent)
                Files.writeString(path, if (file.endsWith("awt_db.php")) "<?php const DB_TYPE = 'mysql';" else "<?php")
            }
            val wizard = AwtWizardPeer(project)
            val panel = wizard.component as Container
            components(panel).filterIsInstance<JButton>().first { it.text == "Next: Framework" }.doClick()
            val row = components(panel).filterIsInstance<Container>().first { container ->
                container.components.filterIsInstance<JBLabel>().any { it.text == "Framework directory" }
            }
            row.components.filterIsInstance<TextFieldWithBrowseButton>().single().text = framework.toString()
            assertNull(wizard.validate())
            val input = wizard.settings
            assertEquals("", input.config.phpExecutable)
            assertEquals(interpreter.id, input.interpreter!!.id)
            Php.inheritInterpreter(defaults, input.interpreter)
            assertEquals(listOf("/fake/local/php", "-c", "/fake/local/php.ini"), Php.arguments(defaults, ""))
        } finally {
            sourceFacade.projectConfiguration.interpreterName = sourceName
            targetFacade.projectConfiguration.interpreterName = targetName
            targetManager.setInterpreters(targetInterpreters)
            sourceManager.setInterpreters(sourceInterpreters)
            Archives.deleteTree(framework)
        }
    }

    fun testUnselectedSingleLocalPhpStormInterpreterIsUsed() {
        val defaults = ProjectManager.getInstance().defaultProject
        val manager = PhpInterpretersManagerImpl.getInstance(defaults)
        val facade = PhpProjectConfigurationFacade.getInstance(defaults)
        val previous = manager.interpreters.toList()
        val name = facade.projectConfiguration.interpreterName
        try {
            facade.projectConfiguration.interpreterName = null
            val interpreter = PhpInterpreter().apply {
                this.name = "AWT sole local interpreter"
                homePath = "/fake/php"
            }
            manager.setInterpreters(listOf(interpreter))
            assertEquals("/fake/php", Php.executable(null, ""))
        } finally {
            manager.setInterpreters(previous)
            facade.projectConfiguration.interpreterName = name
        }
    }

    private fun components(container: Container): List<java.awt.Component> =
        container.components.flatMap { component ->
            listOf(component) + if (component is Container) components(component) else emptyList()
        }
}
