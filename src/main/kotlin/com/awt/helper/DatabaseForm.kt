package com.awt.helper

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import javax.swing.JComboBox
import javax.swing.JPasswordField

class DatabaseForm {
    val host = JBTextField("localhost")
    val port = JBTextField("3306")
    val name = JBTextField()
    val username = JBTextField()
    val password = JPasswordField()
    val website = JBTextField("My Website")
    val hostPath = JBTextField("/")

    val panel = Form().apply {
        add("Database type", JComboBox(arrayOf("MySQL")))
        add("Database hostname", host)
        add("Database port", port)
        add("Database name", name)
        add("Database username", username)
        add("Database password", password)
        add("Website name", website)
        add("Website hostname path", hostPath)
        add("", JBLabel(
            "<html>The database must already exist. Hostname path is a URL subfolder.<br>" +
                "The local PHP server serves public/ at /.</html>"
        ))
    }.panel

    fun listen(callback: () -> Unit) {
        listOf(host, port, name, username, password, website, hostPath).forEach {
            it.onTextChanged(callback)
        }
    }

    fun read(): DatabaseInput {
        val input = DatabaseInput(
            host = host.text.trim(),
            port = port.text.toIntOrNull() ?: 0,
            name = name.text.trim(),
            username = username.text.trim(),
            password = String(password.password),
            website = website.text.trim(),
            hostPath = hostPath.text.trim().ifBlank { "/" }
        )
        input.validate()
        validateInstallationFields(input)
        return input
    }

    private fun validateInstallationFields(input: DatabaseInput) {
        val values = listOf(input.name, input.username, input.password, input.website)
        require(values.none { it.contains('\n') || it.contains('\r') }) {
            "Installation fields must not contain line breaks."
        }
    }
}
