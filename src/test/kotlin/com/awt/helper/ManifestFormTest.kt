package com.awt.helper

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JButton
import javax.swing.JTextField
import java.awt.Container
import java.nio.file.Files

class ManifestFormTest : BasePlatformTestCase() {
    fun testImagePickersUseRelativeManifestPathsAndPreserveExistingPackagePaths() {
        val directory = Files.createTempDirectory("awt-manifest-images-")
        try {
            val icon = directory.resolve("chosen.PNG")
            val preview = directory.resolve("chosen.SVG")
            Files.write(icon, byteArrayOf(1, 2, 3))
            Files.writeString(preview, "<svg xmlns=\"http://www.w3.org/2000/svg\"/>")
            val form = ManifestForm()
            form.icon.text = icon.toString()
            form.preview.text = preview.toString()
            assertEquals("data/icon/icon.png", form.read().icon)
            assertEquals("data/preview/preview.svg", form.read().preview_image)
            assertEquals(PackageImages(icon, preview), form.selectedImages())
            form.load(Manifest(icon = "data/icon/custom.png", preview_image = "data/preview/custom.jpg"))
            assertEquals("data/icon/custom.png", form.read().icon)
            assertEquals(PackageImages(), form.selectedImages())
        } finally {
            Archives.deleteTree(directory)
        }
    }

    fun testValidationDoesNotRecursivelyCommitDependencyEdits() {
        val form = ManifestForm()
        form.tableModel.addRow(arrayOf("Core", "*", ""))
        var callbacks = 0
        form.listen {
            callbacks++
            // Bound the reproduction so a regression fails without overflowing the EDT stack.
            assertTrue("Dependency validation reentered while committing an edit", callbacks < 5)
            form.read()
        }

        assertTrue(form.table.editCellAt(0, 0))
        (form.table.editorComponent as JTextField).text = "UpdatedCore"
        val manifest = form.read()

        assertFalse(form.table.isEditing)
        assertEquals("UpdatedCore", manifest.dependencies.single().name)
        assertEquals(1, callbacks)

        // A later edit must still commit after the guard has been released.
        assertTrue(form.table.editCellAt(0, 1))
        (form.table.editorComponent as JTextField).text = ">=27.0.0"
        assertEquals(">=27.0.0", form.read().dependencies.single().version)
        assertEquals(2, callbacks)
    }

    fun testAddDependencyWhileEditingDoesNotCrashValidation() {
        val form = ManifestForm()
        form.tableModel.addRow(arrayOf("Core", "*", ""))
        var callbacks = 0
        form.listen {
            callbacks++
            assertTrue("Dependency validation reentered while adding a row", callbacks < 5)
            // The wizard treats an unfinished new row as invalid settings.
            runCatching { form.read() }
        }
        assertTrue(form.table.editCellAt(0, 0))
        (form.table.editorComponent as JTextField).text = "UpdatedCore"

        findButton(form.panel, "Add").doClick()

        assertEquals(2, form.tableModel.rowCount)
        assertEquals("UpdatedCore", form.tableModel.getValueAt(0, 0))
        assertEquals("", form.tableModel.getValueAt(1, 0))
        assertEquals("*", form.tableModel.getValueAt(1, 1))
        assertEquals(2, callbacks)
    }

    private fun findButton(container: Container, text: String): JButton =
        buttons(container).first { it.text == text }

    private fun buttons(container: Container): List<JButton> = container.components.flatMap {
        when (it) {
            is JButton -> listOf(it)
            is Container -> buttons(it)
            else -> emptyList()
        }
    }
}
