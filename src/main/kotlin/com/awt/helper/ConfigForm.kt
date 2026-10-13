package com.awt.helper

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.net.URI
import javax.swing.JComboBox
import javax.swing.JPasswordField

class ConfigForm(private val project: Project?, showFramework: Boolean = true) {
    val path = browseField(true, "AWT framework directory", project)
    val mode = JComboBox(FrameworkMode.values())
    val php = browseField(false, "Local PHP executable", project)
    val host = JBTextField("localhost")
    val port = JBTextField("3000")
    val browser = JBCheckBox("Open browser when the server is ready", true)
    val release = JBTextField("releases")
    val format = JBTextField("<name>-<version>")
    val address = JBTextField("http://localhost:3000")
    val endpoint = JBTextField("/dev/install")
    val secret = JPasswordField()
    val fast = JBCheckBox("Fast build (--fast)")
    val action = JComboBox(arrayOf("install", "update"))

    val panel = Form().apply {
        if (showFramework) {
            add("Framework path", path)
            add("Framework mode", mode)
        }
        add("PHP interpreter", php)
        add("", JBLabel("Leave empty to use the PhpStorm project interpreter (local only)."))
        if (showFramework) {
            add("Server host", host)
            add("Server port", port)
            add("Browser", browser)
        }
        add("Release folder", release)
        add("Build save format", format)
        add("Build mode", fast)
        add("Deployment address", address)
        add("Remote install path", endpoint)
        add("Development secret", secret)
        add("Deployment action", action)
    }.panel

    fun load(config: HelperConfig) {
        path.text = config.frameworkPath
        mode.selectedItem = config.mode
        php.text = config.phpExecutable
        host.text = config.host
        port.text = config.port.toString()
        browser.isSelected = config.openBrowser
        release.text = config.releaseFolder
        format.text = config.buildSaveFormat
        address.text = config.deploymentAddress
        endpoint.text = config.remoteInstallPath
        fast.isSelected = config.fast
        action.selectedItem = config.action
    }

    fun read(previous: HelperConfig): HelperConfig {
        val config = previous.copy(
            frameworkPath = path.text.trim(),
            mode = mode.selectedItem as FrameworkMode,
            phpExecutable = php.text.trim(),
            host = host.text.trim(),
            port = port.text.toIntOrNull() ?: 0,
            openBrowser = browser.isSelected,
            releaseFolder = release.text.trim(),
            buildSaveFormat = format.text.trim(),
            deploymentAddress = address.text.trim(),
            remoteInstallPath = endpoint.text.trim(),
            fast = fast.isSelected,
            action = action.selectedItem as String
        )
        validate(config)
        return config
    }

    private fun validate(config: HelperConfig) {
        require(config.host.matches(Regex("[A-Za-z0-9.-]+")) && config.port in 1..65535) {
            "Invalid server host or port."
        }
        require(config.releaseFolder.isNotBlank() && config.buildSaveFormat.isNotBlank()) {
            "Release folder and build save format are required."
        }
        require(config.buildSaveFormat.none { it == '/' || it == '\\' } &&
            !config.buildSaveFormat.contains("..")) {
            "Build save format must be a filename template."
        }
        require(URI(config.deploymentAddress).scheme in listOf("http", "https")) {
            "Deployment address must be HTTP or HTTPS."
        }
        require(config.remoteInstallPath.startsWith('/') && !config.remoteInstallPath.contains('?')) {
            "Remote install path must begin with /."
        }
    }
}
