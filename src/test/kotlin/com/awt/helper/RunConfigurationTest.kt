package com.awt.helper

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComboBox

class RunConfigurationTest : BasePlatformTestCase() {
    fun testServerRunDoesNotOverrideDeploymentDefaults() {
        val configuration = AwtServerType().configurationFactories.single()
            .createTemplateConfiguration(project)
        configuration.deploymentAction = "install"
        assertEquals("update", configuration.effective(HelperConfig(action = "update")).action)
    }

    fun testActionSelectionIsSavedWhenUsingProjectSettings() {
        val root = Files.createTempDirectory("awt-run-editor-")
        val testProject = object : Project by project {
            override fun getBasePath(): String = root.toString()
        }
        try {
            val defaults = HelperConfig(mode = FrameworkMode.REFERENCE, action = "install")
            defaults.save(root)
            val configuration = configuration(testProject).apply { deploy = true }
            val editor = AwtRunEditor(testProject, false)
            editor.resetFrom(configuration)
            val action = components(editor.component).filterIsInstance<JComboBox<*>>()
                .first { it.itemCount == 2 && it.getItemAt(0) == "install" }
            action.selectedItem = "update"
            editor.applyTo(configuration)
            assertTrue(configuration.useProjectSettings)
            assertEquals("update", configuration.effective().action)
            assertEquals("install", HelperConfig.read(root)!!.action)

            val xml = Element("configuration")
            configuration.writeExternal(xml)
            val restored = configuration(testProject)
            restored.readExternal(xml)
            assertEquals("update", restored.effective().action)
            editor.resetFrom(restored)
            assertEquals("update", action.selectedItem)
            com.intellij.openapi.util.Disposer.dispose(editor)
        } finally {
            Archives.deleteTree(root)
        }
    }

    fun testSuccessfulInstallSwitchesBothRunAndProjectDefaultsToUpdate() {
        val root = Files.createTempDirectory("awt-deploy-state-")
        try {
            val defaults = HelperConfig(action = "install")
            defaults.save(root)
            val configuration = configuration(project).apply { deploy = true }
            configuration.deploymentCompleted(root, defaults, 1, true)
            configuration.deploymentCompleted(root, defaults, 0, false)
            assertNull(configuration.deploymentAction)
            assertEquals("install", HelperConfig.read(root)!!.action)

            configuration.deploymentCompleted(root, defaults, 0, true)
            assertEquals("update", configuration.deploymentAction)
            assertEquals("update", HelperConfig.read(root)!!.action)
        } finally {
            Archives.deleteTree(root)
        }
    }

    fun testCustomDeploymentAndOrdinaryBuildKeepProjectDefaults() {
        val root = Files.createTempDirectory("awt-custom-deploy-")
        try {
            val defaults = HelperConfig(action = "install")
            defaults.save(root)
            val configuration = configuration(project).apply {
                useProjectSettings = false
                custom = defaults.copy()
            }
            configuration.deploymentCompleted(root, defaults, 0, true)
            assertEquals("install", configuration.custom.action)
            configuration.deploy = true
            configuration.deploymentCompleted(root, defaults, 0, true)
            assertEquals("update", configuration.effective().action)
            assertEquals("install", HelperConfig.read(root)!!.action)
        } finally {
            Archives.deleteTree(root)
        }
    }

    private fun configuration(testProject: Project) = AwtBuildType().configurationFactories.single()
        .createTemplateConfiguration(testProject)

    private fun components(container: Container): List<java.awt.Component> =
        container.components.flatMap { component ->
            listOf(component) + if (component is Container) components(component) else emptyList()
        }
}
