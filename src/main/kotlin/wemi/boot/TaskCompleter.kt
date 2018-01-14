package wemi.boot

import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.ParsedLine
import wemi.BuildScriptData
import wemi.boot.TaskParser.TokenType.*
import wemi.util.SimpleHistory

/**
 * Provides completion for task lines, parsed by [TaskParser].
 */
internal object TaskCompleter : Completer {

    private val candidates: List<Candidate> by lazy {
        val candidates = ArrayList<Candidate>()

        for (name in BuildScriptData.AllProjects.keys) {
            val item = name + TaskParser.PROJECT_SEPARATOR
            candidates.add(Candidate(
                    item,
                    item,
                    null, //"Projects",
                    null,
                    null,
                    null,
                    true))
        }

        for (config in BuildScriptData.AllConfigurations.values) {
            val item = config.name + TaskParser.CONFIGURATION_SEPARATOR
            candidates.add(Candidate(
                    item,
                    item,
                    null, //"Configurations",
                    config.description,
                    null,
                    null,
                    true))
        }

        for (key in BuildScriptData.AllKeys.values) {
            candidates.add(Candidate(
                    key.name,
                    key.name,
                    null, //"Keys",
                    key.description,
                    null,
                    null,
                    true))
        }

        candidates
    }

    override fun complete(reader: LineReader?, line: ParsedLine?, candidates: MutableList<Candidate>?) {
        if (line !is TaskParser.ParsedTaskLine || candidates == null) {
            return
        }

        val tokenTypes = line.parsed.tokenTypes
        val wordIndex = line.parsed.positionWord
        val type = tokenTypes.getOrNull(wordIndex)

        if ((type == Input || type == Other) && tokenTypes.getOrNull(wordIndex - 2) == InputKey) {
            // Fill in from history for this key
            val inputKey = line.parsed.tokens[wordIndex - 2]
            val history = SimpleHistory.getExistingHistory("input.$inputKey")
            if (history != null) {
                for (item in history.items) {
                    candidates.add(Candidate(item))
                }
            }
        } else {
            candidates.addAll(this.candidates)
        }
    }

}