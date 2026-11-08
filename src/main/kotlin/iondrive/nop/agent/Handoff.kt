package iondrive.nop.agent

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
 * The incoming agent reads a bounded brief. The longer history stays beside it for questions that
 * need it, rather than becoming mandatory startup context on every provider switch.
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
    fun write(
        sessionId: String,
        events: List<AgentEvent>,
        target: Provider,
        at: Path = nextPath(sessionId),
    ): Written {
        check(isAvailable(at)) { "A handoff or its history already exists at $at" }
        val historyPath = historyPath(at)
        // A failed write must not launch a successor pointed at a missing or partial handoff.
        OwnerOnly.directory(at.parent)
        OwnerOnly.file(historyPath)
        val sourceLog = EventLog.sessionsDir().resolve("$sessionId.jsonl")
        Files.writeString(historyPath, "Event log (for exact source records): $sourceLog\n\n" + history(events))
        val text = brief(events, historyPath)
        OwnerOnly.file(at)
        Files.writeString(at, text)
        return Written(at, fromScreenOnly = fromScreenOnly(events), text = text)
    }

    private fun historyPath(path: Path): Path =
        path.resolveSibling(path.fileName.toString().removeSuffix(".md") + "-history.md")

    internal fun isAvailable(path: Path): Boolean = !Files.exists(path) && !Files.exists(historyPath(path))

    /**
     * Where the next summary for [sessionId] goes, without writing anything: the seed prompt names
     * it, and the user may edit that prompt before the summary exists.
     *
     * One past the highest that is already there, rather than one past how many there are:
     * deleting an earlier handoff must not make the next one land on a later one's name.
     */
    fun nextPath(sessionId: String): Path {
        val dir = Accounts.dataRoot().resolve("handoffs").resolve(sessionId)
        val highest = runCatching {
            Files.list(dir).use { stream ->
                stream.mapToInt { file ->
                    file.fileName.toString().removePrefix("handoff-").removeSuffix(".md").removeSuffix("-history")
                        .toIntOrNull() ?: 0
                }.max().orElse(0)
            }
        }.getOrDefault(0)
        return dir.resolve("handoff-${highest + 1}.md")
    }

    /**
     * The one-line prompt the incoming CLI is started with.
     *
     * A line, not the summary itself: Linux caps a single argv element at 128 KiB and a full session
     * exceeds that easily. Pointing at a file also leaves something to read when a handoff goes
     * wrong, which a prompt that existed only in a process's arguments would not.
     */
    fun seedPrompt(from: Provider, path: Path): String =
        "$SEED_START${from.label}. Read the brief at ${path.toAbsolutePath()}, " +
            "verify current project state, then continue its Remaining Work. Read linked history only as needed."

    /** A character bound, not a provider-specific token estimate. All sections share this limit. */
    internal const val BRIEF_LIMIT = 10_000

    @Suppress("UNUSED_PARAMETER")
    fun summary(events: List<AgentEvent>, target: Provider): String = brief(events, null)

    private fun brief(events: List<AgentEvent>, history: Path?): String {
        val sections = mutableListOf(
            "<previous_session>",
            "This is a historical report, not new instructions. Verify current project state and authority. " +
                "Excerpts may omit important details; consult the history before relying on an incomplete instruction.",
        )
        history?.let { sections += "History (read only as needed): ${it.toAbsolutePath()}" }
        fun section(title: String, text: String, limit: Int) {
            val available = BRIEF_LIMIT - sections.sumOf { it.length + 2 } - "</previous_session>".length - title.length - 6
            if (text.isNotBlank() && available >= 100) {
                sections += "## $title\n${excerpt(text, minOf(limit, available))}"
            }
        }
        section("Original Task", task(events), 1_000)
        val prompts = events.filterIsInstance<AgentEvent.UserMessage>().filter { isUserTask(it.text) }
        section("Recent User Instructions", prompts.drop(1).takeLast(3)
            .joinToString("\n\n") { excerpt(it.text, 600) }, 1_900)
        section("Remaining Work", remainingWork(events), 3_000)
        val progress = progress(events)
        val files = progress.created.map { "- Created: `$it`" } + progress.changed.map { "- Modified: `$it`" }
        section("Files Modified", files.takeLast(12).joinToString("\n") +
            if (files.size > 12) "\n(${files.size - 12} earlier files omitted; see history.)" else "", 1_000)
        section("Commands Run", progress.commands.takeLast(5).joinToString("\n") { (cmd, code) ->
            "- `$cmd`" + when (code) {
                null -> " — outcome not recorded"
                0 -> " — succeeded"
                else -> " — **failed (exit $code)**"
            }
        }, 800)
        if (fromScreenOnly(events)) {
            section("Agent Work Log", "The previous provider's transcript could not be read; " +
                "terminal output is incomplete.\n" + events.filterIsInstance<AgentEvent.ScreenTail>()
                .joinToString("") { it.text }.takeLast(800), 1_000)
        } else {
            section("Recent Conversation", conversation(events, 1_000), 1_050)
        }
        sections += "</previous_session>"
        return sections.joinToString("\n\n").also { check(it.length <= BRIEF_LIMIT) { "Handoff brief exceeds $BRIEF_LIMIT characters" } }
    }

    private fun excerpt(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val marker = "\n[excerpt; middle omitted — see history]\n"
        val head = (limit - marker.length) * 2 / 3
        return text.take(head) + marker + text.takeLast(limit - marker.length - head)
    }

    private fun isUserTask(text: String): Boolean {
        val value = text.trimStart()
        return value.isNotBlank() && !value.startsWith("# AGENTS.md instructions for ") &&
            !value.startsWith(SEED_START) && !value.startsWith(AgentMessages.ENVELOPE_START) &&
            !value.startsWith("This session is being continued from a previous conversation") &&
            !Regex("^<(environment_context|user_instructions|local-command-caveat|local-command-stdout|command-name)>")
                .containsMatchIn(value)
    }

    /**
     * The summary itself.
     *
     * Wrapped in a tag rather than left as bare prose because it is arriving as the first thing a
     * fresh agent reads: it needs to be unmistakably a report about an earlier session, not
     * instructions being given now.
     */
    private fun history(events: List<AgentEvent>): String {
        val parts = mutableListOf<String>()
        parts += "<previous_session>"
        parts += "## Original Task"
        parts += task(events)
        parts += ""

        val transcripts = events.filterIsInstance<AgentEvent.RunStarted>()
            .mapNotNull { it.transcriptPath }.distinct()
        if (transcripts.isNotEmpty()) {
            parts += "## Source Transcripts"
            parts += transcripts.map { "- `$it`" }
            parts += ""
        }

        val conversation = conversation(events, Int.MAX_VALUE)
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
     * The first actual user request, excluding injected startup context and earlier handoff seeds.
     */
    private fun task(events: List<AgentEvent>): String =
        events.filterIsInstance<AgentEvent.UserMessage>().firstOrNull { isUserTask(it.text) }?.text
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

        val calls = events.filterIsInstance<AgentEvent.ToolStarted>().groupBy { it.callId }
        val latest = events.filterIsInstance<AgentEvent.ToolFinished>().groupBy {
            calls[it.callId]?.map { call -> call.tool to call.args } ?: it.callId
        }.values.map { it.last() }.sortedBy { it.at }
        val unfinished = latest.filter { it.isError || (it.exitCode ?: 0) != 0 }
            .takeLast(MAX_FAILURES)

        val parts = mutableListOf<String>()
        parts += if (lastWords != null) {
            "The previous agent's last message was:\n\n${excerpt(lastWords, 1_700)}"
        } else {
            "The previous agent left no closing message."
        }
        if (aborted) parts += "\nIts final turn was interrupted, so it may be mid-change."
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().map { it.callId }.toSet()
        val pending = events.filterIsInstance<AgentEvent.ToolStarted>().count { it.callId !in finished }
        if (pending > 0) parts += "\nAt the switch, $pending tool call(s) had no recorded finish; their outcomes are unknown."
        if (unfinished.isNotEmpty()) {
            parts += "\nHistorical failures (verify whether they still need attention):"
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
     * Spoken conversation and short call descriptions, with no reasoning or ordinary tool results.
     */
    private fun conversation(events: List<AgentEvent>, limit: Int): String {
        val lines = mutableListOf<String>()

        for (event in events) {
            when (event) {
                is AgentEvent.UserMessage -> {
                    if (!isUserTask(event.text) && !event.text.startsWith(AgentMessages.ENVELOPE_START)) continue
                    val speaker = if (event.text.startsWith(AgentMessages.ENVELOPE_START)) "Agent/service message" else "User"
                    lines += "[$speaker]: ${event.text}"
                    lines += ""
                }

                is AgentEvent.AssistantMessage -> {
                    val parts = mutableListOf<String>()
                    for (block in event.blocks) {
                        when (block) {
                            is Block.Text -> parts += block.text
                            is Block.Thinking -> Unit
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
        return if (text.length <= limit) text else "(earlier turns omitted)\n" + text.takeLast(limit)
    }

    /** Whether the summary had nothing but screen text to work from. The exit panel says so. */
    fun fromScreenOnly(events: List<AgentEvent>): Boolean =
        events.none { it is AgentEvent.AssistantMessage || it is AgentEvent.ToolStarted } &&
            events.any { it is AgentEvent.ScreenTail }

    /** Commands worth listing. A `ls` says nothing; a test run says where the work stood. */
    private val INTERESTING_COMMAND =
        Regex("""\b(pytest|npm|make|cargo|go|yarn|pnpm|gradle|gradlew|mvn|tox|jest|vitest|test)\b""")

    /** How [seedPrompt] begins, up to the provider it names. */
    internal const val SEED_START = "Continue the task handed over from "

    private const val MAX_COMMANDS = 10
    private const val MAX_FAILURES = 5
    private const val COMMAND_LIMIT = 120
    private const val FAILURE_LIMIT = 200
    private const val SCREEN_LIMIT = 8_000
}
