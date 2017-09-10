package wemi.assembly

/**
 * Contains utilities for recognizing file type classes, from the [wemi.Keys.assembly] point of view.
 *
 * Based on https://github.com/sbt/sbt-assembly
 */
object FileRecognition {

    private fun extension(name:String):String? {
        val extensionSeparator = name.lastIndexOf('.')
        return if (extensionSeparator == -1) {
            null
        } else {
            name.substring(extensionSeparator + 1)
        }
    }

    fun isReadme(name:String):Boolean {
        if (name.contains("readme", ignoreCase = true) ||
                name.contains("about", ignoreCase = true)) {
            val extension = FileRecognition.extension(name)

            return extension == null ||
                    extension.equals("txt", ignoreCase = true) ||
                    extension.equals("md", ignoreCase = true) ||
                    extension.equals("markdown", ignoreCase = true)
        }
        return false
    }

    fun isLicenseFile(name:String):Boolean {
        if (name.contains("license", ignoreCase = true) ||
                name.contains("licence", ignoreCase = true) ||
                name.contains("notice", ignoreCase = true) ||
                name.contains("copying", ignoreCase = true)) {
            val extension = FileRecognition.extension(name)

            return extension == null ||
                    extension.equals("txt", ignoreCase = true) ||
                    extension.equals("md", ignoreCase = true) ||
                    extension.equals("markdown", ignoreCase = true)
        }
        return false
    }

    fun isSystemJunkFile(name:String):Boolean {
        return name.equals(".DS_Store", ignoreCase = true) ||
                name.equals("Thumbs.db", ignoreCase = true)
    }

}