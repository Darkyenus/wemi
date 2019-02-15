package wemi.util

import com.darkyen.tproll.util.PrettyPrinter
import wemi.boot.WemiPathTags
import wemi.boot.WemiRootFolder
import wemi.boot.WemiUnicodeOutputSupported
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

private const val PATH_TAG_BEGIN_DEFAULT = '\u200B' // Zero width space

private const val PATH_TAG_END_DEFAULT = '\u200C' // Zero width non joiner '‌'
private const val PATH_TAG_END_SPACE = '\u00A0' // No-break space ' '

fun StringBuilder.appendPathTagBegin(disguisedAs:Char = '\u0000'):StringBuilder {
    if (disguisedAs != '\u0000') {
        append(disguisedAs)
    }
    if (WemiPathTags) {
        append(PATH_TAG_BEGIN_DEFAULT)
    }
    return this
}

fun StringBuilder.appendPathTagEnd(disguisedAs:Char = '\u0000'):StringBuilder {
    if (WemiPathTags) {
        if (disguisedAs == ' ') {
            append(PATH_TAG_END_SPACE)
            return this
        }
        append(PATH_TAG_END_DEFAULT)
    }
    if (disguisedAs != '\u0000') {
        append(disguisedAs)
    }
    return this
}

private val OPTIONS_NO_FOLLOW_LINKS: Array<LinkOption> = arrayOf(LinkOption.NOFOLLOW_LINKS)
private val OPTIONS_DO_FOLLOW_LINKS: Array<LinkOption> = arrayOf()

private fun appendPathEndExtras(sb:StringBuilder, path:Path) {

    // attempt to read attributes without following links
    val attributes = try {
        Files.readAttributes<BasicFileAttributes>(path, BasicFileAttributes::class.java, *OPTIONS_NO_FOLLOW_LINKS)
    } catch (e: IOException) {
        null
    }

    if (attributes == null) {
        // File does not exist
        sb.appendPathTagEnd(' ')
        sb.append(if (WemiUnicodeOutputSupported) "⌫" else "(x)")
        return
    }

    val symLinkAttributes = if (!attributes.isSymbolicLink) null else try {
        Files.readAttributes<BasicFileAttributes>(path, BasicFileAttributes::class.java, *OPTIONS_NO_FOLLOW_LINKS)
    } catch (e: IOException) {
        null
    }

    // If it is a directory, indicate that
    if (attributes.isDirectory || (symLinkAttributes != null && symLinkAttributes.isDirectory)) {
        sb.append('/')
    }
    sb.appendPathTagEnd(' ')
    var needsSpace = false

    // If it is a symlink, show where it leads
    if (attributes.isSymbolicLink) {
        val symLinkDestination = try { Files.readSymbolicLink(path) } catch (e:IOException) {}
        if (symLinkAttributes == null || symLinkDestination == null) {
            // File does not exist when following links, therefore it is a broken link
            sb.append(if (WemiUnicodeOutputSupported) "⇥" else "->|")
            needsSpace = true
        } else {
            // Where does the file lead when following links?
            sb.append(if (WemiUnicodeOutputSupported) "→ " else "-> ").append(symLinkDestination.toString())
            needsSpace = true
        }
    }

    // If it is a regular file (this or symlink target), show its size
    val attrs = symLinkAttributes ?: attributes
    if (attrs.isRegularFile) {
        if (needsSpace) {
            sb.append(' ')
        }
        sb.format(Color.White).append('(').appendByteSize(attrs.size()).append(')')
    }
}

private fun appendPath(sb: StringBuilder, originalPath: Path) {
    val path = originalPath.normalize()

    val shownPath: Path
    if (path.startsWith(WemiRootFolder)) {
        shownPath = WemiRootFolder.relativize(path)
    } else {
        shownPath = path
    }

    val showPathString = shownPath.toString()
    sb.appendPathTagBegin()
    if (showPathString.isEmpty()) {
        // For empty strings (that is, current directory) write .
        sb.append('.')
    } else {
        sb.append(showPathString)
    }
    appendPathEndExtras(sb, path)
}

/**
 * Custom [Path] pretty printer which replaces unicode for ascii if [WemiUnicodeOutputSupported] is false
 * and surrounds paths in link tags for IDE plugin if [WemiPathTags].
 */
internal class WemiPrettyPrintPathModule : PrettyPrinter.PrettyPrinterModule {
    override fun accepts(item: Any?): Boolean = item is Path

    override fun append(sb: StringBuilder, item: Any?, maxCollectionElements: Int) {
        appendPath(sb, item as Path)
    }
}

/** Same as [WemiPrettyPrintPathModule] but for [File]. */
internal class WemiPrettyPrintFileModule : PrettyPrinter.PrettyPrinterModule {
    override fun accepts(item: Any?): Boolean = item is File

    override fun append(sb: StringBuilder, item: Any?, maxCollectionElements: Int) {
        appendPath(sb, (item as File).toPath())
    }
}

/** Same as [WemiPrettyPrintPathModule] but for [LocatedPath]. */
internal class WemiPrettyPrintLocatedPathModule : PrettyPrinter.PrettyPrinterModule {
    override fun accepts(item: Any?): Boolean = item is LocatedPath

    override fun append(sb: StringBuilder, item: Any?, maxCollectionElements: Int) {
        val locPath = item as LocatedPath
        if (locPath.root == null) {
            appendPath(sb, locPath.file)
        } else {
            val shownPath: Path
            if (locPath.root.startsWith(WemiRootFolder)) {
                shownPath = WemiRootFolder.relativize(locPath.root)
            } else {
                shownPath = locPath.root
            }

            sb.appendPathTagBegin()
            val showPathString = shownPath.toString()
            if (showPathString.isEmpty()) {
                // For empty strings (that is, current directory) write .
                sb.append('.')
            } else {
                sb.append(showPathString)
            }
            sb.append("//").append(locPath.path)
            appendPathEndExtras(sb, locPath.file)
        }
    }
}

/** Provides pretty printing of Kotlin Functions. */
internal class WemiPrettyPrintFunctionModule : PrettyPrinter.PrettyPrinterModule {
    override fun accepts(item: Any?): Boolean = item is Function<*>

    override fun append(sb: StringBuilder, item: Any?, maxCollectionElements: Int) {
        val value = item as Function<*>

        val javaClass = value.javaClass
        sb.append(value.toString()).format(Color.White).append(" (").append(javaClass.name).append(')')
    }
}