package com.awt.helper

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

fun browseField(directory: Boolean, title: String, project: Project? = null) =
    TextFieldWithBrowseButton().apply {
        val descriptor = FileChooserDescriptor(!directory, directory, false, false, false, false)
            .withTitle(title)
        addBrowseFolderListener(TextBrowseFolderListener(descriptor, project))
    }

/** A two-column form with labels on the left and expanding controls on the right. */
class Form {
    val panel = JPanel(GridBagLayout())
    private var row = 0

    fun add(label: String, component: JComponent) {
        panel.add(JBLabel(label), GridBagConstraints().apply {
            gridx = 0
            gridy = row
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(5)
        })
        panel.add(component, GridBagConstraints().apply {
            gridx = 1
            gridy = row
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(5)
        })
        row++
    }
}

internal fun JTextComponent.onTextChanged(callback: () -> Unit) {
    document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) = callback()
    })
}
