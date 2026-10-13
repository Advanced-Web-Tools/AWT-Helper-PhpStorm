package com.awt.helper

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Local files chosen in the wizard; manifest paths always remain relative to the package. */
data class PackageImages(val icon: Path? = null, val preview: Path? = null) {
    fun applyTo(manifest: Manifest): Manifest = manifest.copy(
        icon = icon?.let { assetPath(it, "icon") } ?: manifest.icon,
        preview_image = preview?.let { assetPath(it, "preview") } ?: manifest.preview_image
    )

    fun copyTo(packageRoot: Path) {
        listOf(icon to "icon", preview to "preview").forEach { (source, kind) ->
            if (source != null) {
                val destination = packageRoot.resolve(assetPath(source, kind))
                Files.createDirectories(destination.parent)
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    companion object {
        val EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "svg", "webp", "avif", "bmp", "ico", "tif", "tiff")

        fun localFile(value: String): Path? {
            if (value.isBlank()) return null
            val path = Path.of(value.trim())
            if (!path.isAbsolute) return null
            require(Files.isRegularFile(path)) { "Selected image file does not exist: $path" }
            assetPath(path, "icon")
            return path
        }

        private fun assetPath(source: Path, kind: String): String {
            val extension = source.fileName.toString().substringAfterLast('.', "").lowercase()
            require(extension in EXTENSIONS) { "Select an image file with a supported extension." }
            return "data/$kind/$kind.$extension"
        }
    }
}
