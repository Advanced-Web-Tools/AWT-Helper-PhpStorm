package com.awt.helper

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.table.DefaultTableModel

class ManifestForm(private val project: Project? = null) {
    private val fields = linkedMapOf<String, JTextField>()
    val icon = imageField("Choose package icon")
    val preview = imageField("Choose package preview")
    private var committingDependencyEdit = false
    val type = JComboBox(arrayOf("0 — System", "1 — Plugin", "2 — Theme"))
    val system = JBCheckBox("System package")
    val tableModel = DefaultTableModel(arrayOf("Name", "Version constraint", "URL (direct download)"), 0)
    val table = JBTable(tableModel).apply {
        toolTipText = "Dependency URLs must be direct HTTP or HTTPS download links to ZIP packages."
    }
    private val form = Form()
    val panel: JComponent = JBScrollPane(form.panel)

    init {
        addManifestFields()
        form.add("Type", type)
        form.add("System", system)
        form.add("Dependencies", createDependenciesPanel())
        loadTemplate()
    }

    fun load(manifest: Manifest) {
        val obj = json.toJsonTree(manifest).asJsonObject
        fields.forEach { (key, field) ->
            val value = obj[key]
            field.text = if (value.isJsonNull) "" else value.asString
        }
        type.selectedIndex = manifest.type
        system.isSelected = manifest.system_package == 1
        tableModel.rowCount = 0
        manifest.dependencies.forEach { dependency ->
            tableModel.addRow(arrayOf(dependency.name, dependency.version, dependency.url))
        }
    }

    fun read(): Manifest {
        commitDependencyEdit()

        val obj = JsonObject()
        fields.forEach { (key, field) ->
            val value = if (field.text.isBlank() && key !in REQUIRED_FIELDS) {
                JsonNull.INSTANCE
            } else {
                JsonPrimitive(field.text.trim())
            }
            obj.add(key, value)
        }
        obj.addProperty("type", type.selectedIndex)
        obj.addProperty("system_package", if (system.isSelected) 1 else 0)
        obj.add("dependencies", json.toJsonTree(readDependencies()))
        return selectedImages().applyTo(Manifest.read(json.toJson(obj))).also { manifest ->
            manifest.dependencies.forEach(DependencyLibraries::validateDownload)
        }
    }

    fun selectedImages() = PackageImages(
        icon = PackageImages.localFile(icon.text),
        preview = PackageImages.localFile(preview.text)
    )

    private fun imageField(title: String) = TextFieldWithBrowseButton().apply {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle(title)
            .withFileFilter { it.extension?.lowercase() in PackageImages.EXTENSIONS }
        addBrowseFolderListener(TextBrowseFolderListener(descriptor, project))
        toolTipText = "Choose a local image to copy into the package, or enter a package-relative path."
    }

    private fun commitDependencyEdit() {
        if (!table.isEditing || committingDependencyEdit) return

        // Swing notifies model listeners before it clears the active cell editor.
        // A validation listener may call read() again during that notification.
        committingDependencyEdit = true
        try {
            table.cellEditor.stopCellEditing()
        } finally {
            committingDependencyEdit = false
        }
    }

    fun listen(callback: () -> Unit) {
        fields.values.forEach { it.onTextChanged(callback) }
        type.addActionListener { callback() }
        system.addActionListener { callback() }
        tableModel.addTableModelListener { callback() }
    }

    private fun addManifestFields() {
        FIELD_NAMES.forEach { key ->
            val component = when (key) {
                "icon" -> icon
                "preview_image" -> preview
                else -> JBTextField().apply { toolTipText = fieldTooltip(key) }
            }
            val field = if (component is TextFieldWithBrowseButton) component.textField else component as JTextField
            fields[key] = field
            val label = when (key) {
                "minimum_awt_version" -> "Minimum AWT version"
                "maximum_awt_version" -> "Maximum AWT version"
                else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
            form.add(label, component)
        }
        form.add("", JBLabel("<html>Choose local icon and preview images with the browse buttons.<br>" +
            "They will be copied into data/icon/ and data/preview/ inside your package.</html>"))
    }

    private fun fieldTooltip(key: String): String = when {
        key.endsWith("version") -> "AWT version: 1, 1.0, 1.0.0, optionally -prerelease"
        key == "name" -> "PHP identifier and folder name; letters, digits and underscores"
        else -> "Optional: empty values are saved as null"
    }

    private fun createDependenciesPanel() = JPanel(BorderLayout()).apply {
        add(JBLabel("<html>URL must be a direct download link to a ZIP package.<br>" +
            "Leave it empty to declare a dependency without downloading it.</html>"), BorderLayout.NORTH)
        add(JBScrollPane(table).apply {
            preferredSize = Dimension(460, 100)
        }, BorderLayout.CENTER)
        add(JPanel().apply {
            add(JButton("Add").apply {
                addActionListener { tableModel.addRow(arrayOf("", "*", "")) }
            })
            add(JButton("Remove").apply {
                addActionListener {
                    if (table.selectedRow >= 0) tableModel.removeRow(table.selectedRow)
                }
            })
        }, BorderLayout.SOUTH)
    }

    private fun readDependencies(): List<Dependency> = (0 until tableModel.rowCount).map { row ->
        Dependency(
            name = dependencyValue(row, 0, ""),
            version = dependencyValue(row, 1, "*"),
            url = dependencyValue(row, 2, "")
        )
    }

    private fun dependencyValue(row: Int, column: Int, default: String): String =
        (tableModel.getValueAt(row, column) ?: default).toString()

    private fun loadTemplate() {
        val template = requireNotNull(javaClass.getResourceAsStream("/awt/template-manifest.json"))
            .bufferedReader().use { it.readText() }
        load(Manifest.read(template))
    }

    private companion object {
        val REQUIRED_FIELDS = setOf("name", "version", "minimum_awt_version")
        val FIELD_NAMES = listOf(
            "name", "version", "minimum_awt_version", "maximum_awt_version", "author",
            "description", "icon", "preview_image", "license", "license_url"
        )
    }
}
