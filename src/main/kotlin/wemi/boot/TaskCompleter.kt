package wemi.boot

import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.ParsedLine
import wemi.AllKeys
import wemi.BuildScriptData
import wemi.boot.TaskParser.TokenType.*
import wemi.util.SimpleHistory
import wemi.util.findCaseInsensitive

/**
 * Provides completion for task lines, parsed by [TaskParser].
 */
internal object TaskCompleter : Completer {

    private val projectCandidates by lazy {
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
                    false))
        }

        candidates
    }

    private val projectCandidatesNoSuffix by lazy {
        val candidates = ArrayList<Candidate>()

        for (name in BuildScriptData.AllProjects.keys) {
            candidates.add(Candidate(
                    name,
                    name + TaskParser.PROJECT_SEPARATOR,
                    null, //"Projects",
                    null,
                    null,
                    null,
                    false))
        }

        candidates
    }

    private val configurationCandidates: List<Candidate> by lazy {
        val candidates = ArrayList<Candidate>()

        for (config in BuildScriptData.AllConfigurations.values) {
            val item = config.name + TaskParser.CONFIGURATION_SEPARATOR
            candidates.add(Candidate(
                    item,
                    item,
                    null, //"Configurations",
                    config.description,
                    null,
                    null,
                    false))
        }

        candidates
    }

    private val configurationCandidatesNoSuffix: List<Candidate> by lazy {
        val candidates = ArrayList<Candidate>()

        for (config in BuildScriptData.AllConfigurations.values) {
            candidates.add(Candidate(
                    config.name,
                    config.name + TaskParser.CONFIGURATION_SEPARATOR,
                    null, //"Configurations",
                    config.description,
                    null,
                    null,
                    false))
        }

        candidates
    }

    private val keyCandidates: List<Candidate> by lazy {
        val candidates = ArrayList<Candidate>()

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

    private val taskSeparatorCandidate = Candidate(
            TaskParser.TASK_SEPARATOR,
            TaskParser.TASK_SEPARATOR,
            null,
            "Task separator",
            null,
            null,
            true
    )

    private val commandCandidates: List<Candidate> = CLI.internalCommands.keys.map { name ->
        Candidate(name,
                name,
                null,
                "[command]",
                null,
                null,
                true
                )
    }

    private fun TaskParser.ParsedTaskLine.retrieveInputKeyForInput(inputIndex:Int):String? {
        val separator = words.getOrNull(inputIndex - 1)
        val inputKey = words.getOrNull(inputIndex - 2)

        return if (separator == null || separator == TaskParser.INPUT_SEPARATOR) {
            inputKey
        } else {
            null
        }
    }

    private fun TaskParser.ParsedTaskLine.retrieveKeyForInput(inputIndex:Int):String? {
        val tokenTypes = this.tokenTypes
        for (index in (0 until minOf(tokenTypes.size, inputIndex)).reversed()) {
            if (tokenTypes[index] == Separator && this.words[index] == TaskParser.TASK_SEPARATOR) {
                // There is no key?
                return null
            }

            if (tokenTypes[index] == Key) {
                return this.words[index]
            }
        }
        return null
    }

    private fun completeInputKeys(key:String?, withSuffix:Boolean, candidates: MutableList<Candidate>) {
        if (key == null) {
            return
        }
        val foundKey = AllKeys.findCaseInsensitive(key) ?: return
        for ((inputKey, description) in foundKey.inputKeys) {
            val keyWithSuffix = "$inputKey${TaskParser.INPUT_SEPARATOR}"

            if (withSuffix) {
                candidates.add(Candidate(
                        keyWithSuffix,
                        keyWithSuffix,
                        null,
                        description,
                        null,
                        null,
                        false
                ))
            } else {
                candidates.add(Candidate(
                        key,
                        keyWithSuffix,
                        null,
                        description,
                        null,
                        null,
                        false
                ))
            }
        }
    }

    private fun completeInputValues(inputKey:String?, candidates: MutableList<Candidate>) {
        // Fill in from history for this key
        val history = SimpleHistory.getExistingHistory(SimpleHistory.inputHistoryName(inputKey)) ?: return
        for (item in history.items) {
            candidates.add(Candidate(item))
        }

        // Also add items from free history, if not the same as previous
        if (inputKey == null) {
            return
        }
        val freeHistory = SimpleHistory.getExistingHistory(SimpleHistory.inputHistoryName(null)) ?: return
        for (item in freeHistory.items) {
            candidates.add(Candidate(item))
        }
    }

    override fun complete(reader: LineReader?, line: ParsedLine?, candidates: MutableList<Candidate>?) {
        if (line !is TaskParser.ParsedTaskLine || candidates == null) {
            return
        }

        val tokenTypes = line.tokenTypes
        val wordIndex = line.positionWord
        val type = tokenTypes.getOrNull(wordIndex)

        if ((type == Input || type == null) && tokenTypes.getOrNull(wordIndex - 2) == InputKey) {
            // User is editing keyed input

            // Fill in from history for this key
            val inputKey = line.retrieveInputKeyForInput(wordIndex)
            completeInputValues(inputKey, candidates)

            // If not currently editing a task, offer to end
            if (type == null) {
                candidates.add(taskSeparatorCandidate)
            }
        } else if (type == null) {
            // User is ready to start typing other part of task, check what was before to give meaningful suggestion
            val prevIndex = wordIndex - 1
            val prevToken = tokenTypes.getOrNull(prevIndex)

            when (prevToken) {
                null -> {
                    if (prevIndex < 0) {
                        // Start of the line, core of the command is needed
                        candidates.addAll(projectCandidates)
                        candidates.addAll(configurationCandidates)
                        candidates.addAll(keyCandidates)
                        candidates.addAll(commandCandidates)
                    } //else we don't know
                }
                Project,
                Configuration -> {
                    // User is now between project/configuration name and separator, nothing to do here
                }
                Input, // User has already given some input,
                Key -> // or user has completed the key
                {
                    // Time for input keys and end of task
                    val key = line.retrieveKeyForInput(wordIndex)
                    completeInputKeys(key, true, candidates)
                    completeInputValues(null, candidates)
                    candidates.add(taskSeparatorCandidate)
                }
                InputKey -> {
                    // User has input key, and the separator is already in place, nothing to do here
                }
                Separator -> {
                    // User has entered something, separated, and now something new starts.
                    // What it is depends on the separator.

                    when (line.words[prevIndex]) {
                        TaskParser.TASK_SEPARATOR -> {
                            // New task, exciting!
                            candidates.addAll(projectCandidates)
                            candidates.addAll(configurationCandidates)
                            candidates.addAll(keyCandidates)
                            candidates.addAll(commandCandidates)
                        }
                        TaskParser.CONFIGURATION_SEPARATOR -> {
                            // Configuration has been entered, time for new one, or perhaps a key?
                            candidates.addAll(configurationCandidates)
                            candidates.addAll(keyCandidates)
                        }
                        TaskParser.INPUT_SEPARATOR -> {
                            // Time to add some input
                            val inputKey = line.retrieveInputKeyForInput(wordIndex)
                            completeInputValues(inputKey, candidates)
                        }
                        TaskParser.PROJECT_SEPARATOR -> {
                            // Project has been added, but configurations and keys are still missing
                            candidates.addAll(configurationCandidates)
                            candidates.addAll(keyCandidates)
                        }
                    }
                }
                Whitespace -> {
                    assert(false) // This is not possible
                }
            }
        } else {
            when (type) {
                Project -> {
                    // Currently editing project, suggest it
                    candidates.addAll(projectCandidatesNoSuffix)
                }
                Configuration -> {
                    // Currently editing configuration, suggest it
                    candidates.addAll(configurationCandidatesNoSuffix)
                }
                Key -> {
                    // Currently editing key, or something that looks like it, which may be configuration or a project!
                    var hasProject = false
                    var index = wordIndex
                    while (index > 0) {
                        index--
                        if (tokenTypes[index] == Separator && line.words[index] == TaskParser.TASK_SEPARATOR) {
                            break
                        }
                        if (tokenTypes[index] == Project) {
                            hasProject = true
                            break
                        }
                    }
                    if (!hasProject) {
                        candidates.addAll(projectCandidates)
                        candidates.addAll(commandCandidates)
                    }
                    candidates.addAll(configurationCandidates)
                    candidates.addAll(keyCandidates)
                }
                InputKey -> {
                    // Editing input key, suggest them
                    completeInputKeys(line.words[wordIndex], false, candidates)
                }
                Input -> {
                    // Editing input
                    val inputKey = line.retrieveInputKeyForInput(wordIndex)
                    completeInputValues(inputKey, candidates)
                    if (inputKey == null) {
                        // This may be a key being typed
                        val key = line.retrieveKeyForInput(wordIndex)
                        completeInputKeys(key, true, candidates)
                    }
                }
                Separator -> {
                    // Caret is at the start of separator, dunno
                }
                Whitespace -> {
                    assert(false) // This is not possible
                }
            }
        }
    }

}