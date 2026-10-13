package com.awt.helper

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.KeyStroke

class ReloadDependenciesActionTest : BasePlatformTestCase() {
    fun testReloadActionAndPlatformShortcutsAreRegistered() {
        val id = "AWT.ReloadDependencies"
        assertTrue(ActionManager.getInstance().getAction(id) is ReloadDependenciesAction)
        val icon = ActionManager.getInstance().getAction(id).templatePresentation.icon
        assertNotNull(icon)
        assertEquals(16, icon!!.iconWidth)
        val manager = KeymapManager.getInstance()
        val keymaps = listOfNotNull(
            manager.getKeymap(KeymapManager.DEFAULT_IDEA_KEYMAP),
            manager.getKeymap(KeymapManager.MAC_OS_X_10_5_PLUS_KEYMAP)
        )
        val shortcuts = keymaps.flatMap { keymap ->
            keymap.getShortcuts(id).filterIsInstance<KeyboardShortcut>()
        }
        assertTrue(shortcuts.any { it.firstKeyStroke == KeyStroke.getKeyStroke("control alt shift R") })
        assertTrue(shortcuts.any { it.firstKeyStroke == KeyStroke.getKeyStroke("meta alt shift R") })
    }
}
