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

        // From PrettyPrintModules
        private val PATH_TAG_BEGIN_DEFAULT = '\u200B'

        private val PATH_TAGS_END = charArrayOf('\u200C', '\u00A0')

        private fun parseColonNumbers(line:String, start:Int, consecutiveNumbers:Int): IntArray {
            val result = IntArray(consecutiveNumbers + 1)
            var resultI = -1
            var endI = start

            forChars@for (i in start until line.length) {
                endI = i
                val c = line[i]
                when (c) {
                    ':' -> {
                        resultI++
                        if (resultI >= consecutiveNumbers) {
                            break@forChars
                        }
                    }
                    in '0'..'9' -> {
                        if (resultI < 0) {
                            break@forChars
                        }
                        result[resultI] *= 10
                        result[resultI] += c - '0'
                    }
                    else -> break@forChars
                }
            }

            result[consecutiveNumbers] = endI
            return result
        }

        private fun getColonNumberLength(line:String, colon:Int):Int {
            if (colon in line.indices && line[colon] == ':') {
                var numberEnd = colon + 1
                while (numberEnd in line.indices && line[numberEnd].isDigit()) {
                    numberEnd++
                }
                return numberEnd - colon
            }
            return 0
        }

        override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
            val results = ArrayList<Filter.ResultItem>()

            var start = 0
            while (true) {
                start = line.indexOf(PATH_TAG_BEGIN_DEFAULT, start)
                if (start == -1) break

                val pathStart = start + 1
                val pathEnd = line.indexOfAny(PATH_TAGS_END, pathStart + 1)

                if (pathEnd == -1) break
                if (pathStart + 1 == pathEnd) {
                    // 0-length path, skip
                    start = pathEnd + 1
                    continue
                }

                val path = line.substring(pathStart, pathEnd)
                val (lineNum, columnNum, colonNumLength) = parseColonNumbers(line, pathEnd + 1, 2)
                start = pathEnd + 1 + colonNumLength

                val file: VirtualFile =
                        if (path.startsWith('/')) {
                            LocalFileSystem.getInstance().findFileByIoFile(File(path))
                        } else {
                            project.guessProjectDir()?.findFileByRelativePath(path)
                        } ?: continue

                val openFileHyperlinkInfo = OpenFileHyperlinkInfo(project, file, lineNum - 1, columnNum - 1)
                // Highlight only the last file part
                var highlightStart = pathStart
                var highlightEnd = pathEnd
                while (highlightEnd - 1 > highlightStart && line[highlightEnd - 1] == '/') {
                    // Exclude trailing / from the highlight
                    highlightEnd -= 1
                }
                line.lastIndexOf('/', highlightEnd - 1).let {
                    val newHighlightStart = it + 1
                    if (newHighlightStart >= highlightStart && newHighlightStart < highlightEnd) {
                        highlightStart = newHighlightStart
                    }

                }
                results.add(Filter.ResultItem(highlightStart, highlightEnd, openFileHyperlinkInfo, UnderlineTextAttributes))
            }

            if (results.isEmpty()) {
                return null
            }

            return Filter.Result(results)
        }
    }

    private companion object {
        val UnderlineTextAttributes = TextAttributes(null, null, null, EffectType.LINE_UNDERSCORE, Font.PLAIN)
    }
}