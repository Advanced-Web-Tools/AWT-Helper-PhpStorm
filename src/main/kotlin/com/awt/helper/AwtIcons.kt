package com.awt.helper

import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil

object AwtIcons {
    val logo = IconLoader.getIcon("/icons/awt-logo.png", AwtIcons::class.java)
    // Use the resource's intrinsic size; IconLoader can initially return placeholder dimensions.
    private const val LOGO_SIZE = 1254f
    @JvmField val project = IconUtil.scale(logo, null, 16f / LOGO_SIZE)
    val wizard = IconUtil.scale(logo, null, 64f / LOGO_SIZE)
}
