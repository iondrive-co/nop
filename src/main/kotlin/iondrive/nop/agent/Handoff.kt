package iondrive.nop.agent

import iondrive.nop.Log
import iondrive.nop.agent.transcript.Normalize
import java.nio.file.Files
import java.nio.file.Path

/**
 * Turning what one provider did into something the next one can carry on from.
 *
 * This is the point of the feature. Everything else — the isolated homes, the quota watching, the
 * transcript tailers — exists so that at the moment Claude runs out, there is a truthful account of
 * the session to hand Codex, and the work continues instead of being restarted from the prompt.
 *
 * The summary is shaped for the provider receiving it. Claude regenerates its own reasoning, so
 * handing it a previous model's thinking is noise; Codex takes reasoning well, so it gets it. Both
 * get the same facts underneath — the task, the conversation, what was changed, what was run, and
 * what is left.
 */
object Handoff {

    /** A built handoff: where it was written, and whether it had a real transcript behind it. */
    data class Written(val path: Path, val fromScreenOnly: Boolean, val text: String)

    /**
     * Builds the summary for [target] and writes it beside the session's log.
     *
     * Outside the project, deliberately. A summary file in the repository is a file the user has to
     * notice, gitignore and clean up — and the one place it must not appear is a commit made by the
     * very session it describes.
     */
    fun write(sessionId: String, events: List<AgentEvent>, target: Provider): Written {
        val text = summary(events, target)
        val dir = Accounts.dataRoot().resolve("handoffs").resolve(sessionId)
        val path = runCatching {
            OwnerOnly.directory(dir)
            // One past the highest that is already there, rather than one past how many there are:
            // deleting an earlier handoff must not make the next one land on a later one's name.
            val highest = Files.list(dir).use { stream ->
                stream.mapToInt { file ->
                    file.fileName.toString().removePrefix("handoff-").removeSuffix(".md")
                        .toIntOrNull() ?: 0
                }.max().orElse(0)
            }
            dir.resolve("handoff-${highest + 1}.md").also {
                OwnerOnly.file(it)
                Files.writeString(it, text)
            }
        }.onFailure { Log.warn("could not write the handoff summary: $it") }
            .getOrDefault(dir.resolve("handoff-1.md"))

        return Written(path, fromScreenOnly = fromScreenOnly(events), text = text)
    }

    /**
     * The one-line prompt the incoming CLI is started with.
     *
     * A line, not the summary itself: Linux caps a single argv element at 128 KiB and a full session
     * exceeds that easily. Pointing at a file also leaves something to read when a handoff goes
     * wrong, which a prompt that existed only in a process's arguments would not.
     */
    fun seedPrompt(from: Provider, path: Path): String =
        "Continue the task handed over from ${from.label}. Read ${path.toAbsolutePath()} in full " +
            "before doing anything else, then carry on from its Remaining Work section."

    /**
     * The summary itself.
     *
     * Wrapped in a tag rather than left as bare prose because it is arriving as the first thing a
     * fresh agent reads: it needs to be unmistakably a report about an earlier session, not
     * instructions being given now.
     */
    fun summary(events: List<AgentEvent>, target: Provider): String {
        val parts = mutableListOf<String>()
        parts += "<previous_session>"
        parts += "## Original Task"
        parts += task(events)
        parts += ""

        val conversation = conversation(events, target)
        if (conversation.isNotBlank()) {
            parts += "## Conversation History"
            parts += conversation
            parts += ""
        }

        // The screen tail is the degraded path, and only ever used when nothing better exists: a
        // transcript that was read gives a far better account of the same session.
        if (conversation.isBlank()) {
            val screen = events.filterIsInstance<AgentEvent.ScreenTail>().joinToString("") { it.text }
            if (screen.isNotBlank()) {
                parts += "## Agent Work Log"
                parts += "(Reconstructed from terminal output — the previous provider's transcript " +
                    "could not be read, so this is less complete than it looks.)"
                parts += Normalize.summarise(screen, SCREEN_LIMIT)
                parts += ""
            }
        }

        val progress = progress(events)
        if (progress.created.isNotEmpty() || progress.changed.isNotEmpty()) {
            parts += "## Files Modified"
            progress.created.forEach { parts += "- Created: `$it`" }
            progress.changed.forEach { parts += "- Modified: `$it`" }
            parts += ""
        }

        if (progress.commands.isNotEmpty()) {
            parts += "## Commands Run"
            progress.commands.forEach { (command, exitCode) ->
                // The exit code, where there is one. "Ran the tests" and "ran the tests and they
                // failed" are different pieces of news, and the second is the one that matters.
                parts += "- `$command`" + when (exitCode) {
                    null -> ""
                    0 -> " — succeeded"
                    else -> " — **failed (exit $exitCode)**"
                }
            }
            parts += ""
        }

        parts += "## Remaining Work"
        parts += remainingWork(events)
        parts += "</previous_session>"
        return parts.joinToString("\n")
    }

    /**
     * What the session was for.
     *
     * The first thing the user typed. In a TUI that is exactly the task — there is no separate
     * field for it, and no prompt template wrapped around it, so the first user message is both the
     * most reliable and the most honest answer available.
     */
    private fun task(events: List<AgentEvent>): String =
        events.filterIsInstance<AgentEvent.UserMessage>().firstOrNull()?.text
            ?: "Continue the work already in progress in this project."

    /**
     * Where the previous provider actually got to.
     *
     * Its last words are the best available statement of that, because an agent's final message is
     * usually a summary of what it did and what it did not finish. A turn that was cut off says so
     * instead, which is more useful than a confident-sounding paragraph would be.
     */
    private fun remainingWork(events: List<AgentEvent>): String {
        val assistant = events.filterIsInstance<AgentEvent.AssistantMessage>()
        val aborted = assistant.lastOrNull()?.stopReason?.startsWith("aborted") == true
        val lastWords = assistant.asReversed()
            .firstNotNullOfOrNull { turn ->
                turn.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
                    .takeIf { it.isNotBlank() }
            }

        val unfinished = events.filterIsInstance<AgentEvent.ToolFinished>()
            .filter { it.isError || (it.exitCode ?: 0) != 0 }
            .takeLast(MAX_FAILURES)

        val parts = mutableListOf<String>()
        parts += if (lastWords != null) {
            "The previous agent's last message was:\n\n$lastWords"
        } else {
            "The previous agent left no closing message."
        }
        if (aborted) parts += "\nIts final turn was interrupted, so it may be mid-change."
        if (unfinished.isNotEmpty()) {
            parts += "\nThese did not succeed and may still need attention:"
            unfinished.forEach { parts += "- ${Normalize.summarise(it.summary, FAILURE_LIMIT)}" }
        }
        parts += "\nPick the task up from here rather than starting discovery again."
        return parts.joinToString("\n")
    }

    /** Files touched and commands run, from the tool calls in the log. */
    private fun progress(events: List<AgentEvent>): Progress {
        val created = linkedSetOf<String>()
        val changed = linkedSetOf<String>()
        val commands = linkedMapOf<String, Int?>()
        val commandByCall = mutableMapOf<String, MutableList<String>>()

        for (event in events) {
            if (event !is AgentEvent.ToolStarted) continue
            val subject = Normalize.describe(event.tool, event.args)
            if (subject.isBlank()) continue
            when (event.tool) {
                "Write" -> created += subject
                "Edit", "NotebookEdit" -> changed += subject
                "Bash" -> if (INTERESTING_COMMAND.containsMatchIn(subject)) {
                    val cmd = subject.take(COMMAND_LIMIT)
                    commands[cmd] = null
                    commandByCall.getOrPut(event.callId) { mutableListOf() } += cmd
                }
            }
        }
        // Exit codes are attached afterwards so a command is listed once, with its outcome, rather
        // than once when it started and again when it finished.
        for (event in events) {
            if (event !is AgentEvent.ToolFinished) continue
            commandByCall[event.callId]?.forEach { command ->
                commands[command] = event.exitCode ?: if (event.isError) 1 else null
            }
        }
        // A file that was created and then edited is a created file; saying both is noise.
        changed -= created
        return Progress(
            created = created.toList(),
            changed = changed.toList(),
            commands = commands.map { it.key to it.value }.takeLast(MAX_COMMANDS),
        )
    }

    private data class Progress(
        val created: List<String>,
        val changed: List<String>,
        val commands: List<Pair<String, Int?>>,
    )

    /**
     * The conversation, shaped for who is reading it.
     *
     * Claude regenerates its own reasoning from scratch, so a previous model's thinking is noise to
     * it — worse than noise, since it reads as settled conclusions that were never checked. Codex
     * takes reasoning well and does better for having it.
     *
     * Antigravity is grouped with Claude, and by default rather than by measurement: nobody has run
     * the comparison, and of the two behaviours the one that withholds another model's reasoning is
     * the one whose failure is a thinner prompt rather than a confidently wrong premise. There is
     * also nothing to withhold today — its transcript carries no reasoning for nop to read (see
     * [iondrive.nop.agent.transcript.AntigravityTailer]) — so this only starts to matter if that
     * ever changes.
     */
    private fun conversation(events: List<AgentEvent>, target: Provider): String {
        val includeThinking = target == Provider.OpenAI
        val lines = mutableListOf<String>()

        for (event in events) {
            when (event) {
                is AgentEvent.UserMessage -> {
                    lines += "[User]: ${event.text}"
                    lines += ""
                }

                is AgentEvent.AssistantMessage -> {
                    val parts = mutableListOf<String>()
                    for (block in event.blocks) {
                        when (block) {
                            is Block.Text -> parts += block.text
                            is Block.Thinking -> if (includeThinking) parts += "[Reasoning]: ${block.text}"
                            is Block.ToolCall -> {
                                val what = Normalize.describe(block.tool, block.args)
                                if (what.isNotBlank()) parts += "[Tool: ${block.tool}] $what"
                            }
                        }
                    }
                    if (parts.isNotEmpty()) {
                        lines += "[Assistant]:"
                        lines += parts
                        lines += ""
                    }
                }

                else -> Unit
            }
        }

        val text = lines.joinToString("\n").trim()
        // A long session would otherwise arrive as a wall of text the new agent spends its first
        // turn reading. The tail is what matters: it is where the work got to.
        return if (text.length <= CONVERSATION_LIMIT) text else "(earlier turns omitted)\n" +
            text.takeLast(CONVERSATION_LIMIT)
    }

    /** Whether the summary had nothing but screen text to work from. The exit panel says so. */
    fun fromScreenOnly(events: List<AgentEvent>): Boolean =
        events.none { it is AgentEvent.AssistantMessage || it is AgentEvent.ToolStarted } &&
            events.any { it is AgentEvent.ScreenTail }

    /** Commands worth listing. A `ls` says nothing; a test run says where the work stood. */
    private val INTERESTING_COMMAND =
        Regex("""\b(pytest|npm|make|cargo|go|yarn|pnpm|gradle|gradlew|mvn|tox|jest|vitest|test)\b""")

    private const val MAX_COMMANDS = 10
    private const val MAX_FAILURES = 5
    private const val COMMAND_LIMIT = 120
    private const val FAILURE_LIMIT = 200
    private const val CONVERSATION_LIMIT = 24_000
    private const val SCREEN_LIMIT = 8_000
}
