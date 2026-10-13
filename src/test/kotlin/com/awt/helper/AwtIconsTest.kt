package com.awt.helper

import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.image.BufferedImage

class AwtIconsTest : BasePlatformTestCase() {
    fun testLogoLoadsInPluginAndProjectCreation() {
        IconLoader.activate()
        assertEquals(16, AwtIcons.project.iconWidth)
        assertEquals(64, AwtIcons.wizard.iconWidth)
        val icon = IconLoader.getIcon("/META-INF/pluginIcon.svg", AwtIcons::class.java)
        assertEquals(40, icon.iconWidth)
        val image = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            icon.paintIcon(null, graphics, 0, 0)
        } finally {
            graphics.dispose()
        }
        // Verify that the embedded supplied artwork is rendered, rather than an empty SVG.
        assertTrue(image.getRGB(5, 5).ushr(24) > 0)
        assertTrue((0 until 40).any { y ->
            (0 until 40).any { x -> image.getRGB(x, y) and 0x00ffffff > 0x00808080 }
        })
    }
}
