package com.darkyen.wemi.intellij.util

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfoFactory
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Font
import java.io.File
import java.util.regex.Pattern

/**
 * Provides clickable hyperlinks into IntelliJ's Terminal.
 * Hyperlinks are created from file links, typically found in compile error logs, but also in file collection outputs.
 */
class WemiTerminalFilterProvider : ConsoleFilterProvider {

    override fun getDefaultFilters(project: Project): Array<Filter> {
        return arrayOf(WemiTerminalFilter(project))
    }

    class WemiTerminalFilter(private val project:Project) : Filter {

        private val hyperlinkInfoFactory = HyperlinkInfoFactory.getInstance()

        override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
            if (!couldMatchPattern(line)) {
                return null
            }
            val matcher = FilePattern.matcher(line)
            while (matcher.find()) {
                val path = line.substring(matcher.start(1), matcher.end(1))
                val lineNumber = run {
                    val start = matcher.start(2)
                    if (start == -1) {
                        0
                    } else {
                        line.substring(start, matcher.end(2)).toIntOrNull() ?: 0
                    }
                }
                val columnNumber = run {
                    val start = matcher.start(3)
                    if (start == -1) {
                        0
                    } else {
                        line.substring(start, matcher.end(3)).toIntOrNull() ?: 0
                    }
                }

                val file: VirtualFile =
                        if (path.startsWith('/')) {
                            LocalFileSystem.getInstance().findFileByIoFile(File(path))
                        } else {
                            project.guessProjectDir()?.findFileByRelativePath(path)
                        } ?: continue

                val openFileHyperlinkInfo = OpenFileHyperlinkInfo(project, file, lineNumber - 1, columnNumber - 1)
                return Filter.Result(matcher.start(), matcher.end(), openFileHyperlinkInfo, UnderlineTextAttributes)
            }

            return null
        }
    }

    private companion object {
        /** Fast heuristic, determining if given line could ever match [FilePattern] */
        private fun couldMatchPattern(line:String):Boolean {
            var hasSlash = false
            for (c in line) {
                when (c) {
                    '/' -> hasSlash = true
                    '.' -> if (hasSlash) return true
                    ' ' -> hasSlash = false
                }
            }
            return false
        }

        /** Path character */
        private const val P = "[\\p{Alnum}\$()+,-.;=@_]"
        /** Extension character */
        private const val E = "[\\p{Alnum}]"
        /** Optional ANSI control sequence */
        private const val A = "(?:\u001B[0-9;]*m)?"

        /** Matches file path:
         * ```
         * ([p]∗/)+[p]∗.[e]+(:[0-9]+)?
         * ```
         * Which matches paths like:
         * ```
         * foo/bar/Thing.txt
         * /Users/Me/thing.java
         * something//file.kt:12
         * ```
         * but for performance reasons, not:
         * ```
         * foo bar/baz.tar
         * /Users/Me
         * ```
         * which is in context of Wemi acceptable. */
        val FilePattern: Pattern = Pattern.compile(
                "((?:$P*/)+$P*\\.$E+)(?!/)$A(?::$A(\\d+)$A(?::$A(\\d+))?)?")

        val UnderlineTextAttributes = TextAttributes(null, null, null, EffectType.LINE_UNDERSCORE, Font.PLAIN)


    }
}