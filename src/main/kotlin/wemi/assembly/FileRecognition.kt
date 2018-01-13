package wemi.assembly

import wemi.util.pathHasExtension

/**
 * Contains utilities for recognizing file type classes, from the [wemi.Keys.assembly] point of view.
 *
 * Based on https://github.com/sbt/sbt-assembly
 */
object FileRecognition {

    private val TextExtensions = listOf("txt", "md", "markdown", "html")

    /**
     * @return true if [name] appears to be a name of a readme file
     */
    fun isReadme(name: String): Boolean {
        if (name.contains("readme", ignoreCase = true) ||
                name.contains("about", ignoreCase = true)) {

            return name.pathHasExtension(TextExtensions)
        }
        return false
    }

    /**
     * @return true if [name] appears to be a name of a license file
     */
    fun isLicenseFile(name: String): Boolean {
        if (name.contains("license", ignoreCase = true) ||
                name.contains("licence", ignoreCase = true) ||
                name.contains("notice", ignoreCase = true) ||
                name.contains("copying", ignoreCase = true)) {

            return name.pathHasExtension(TextExtensions)
        }
        return false
    }

    /**
     * @return true if [name] appears to be a name of a system junk file that can be safely discarded
     */
    fun isSystemJunkFile(name: String): Boolean {
        return name.equals(".DS_Store", ignoreCase = true) ||
                name.equals("Thumbs.db", ignoreCase = true)
    }

}