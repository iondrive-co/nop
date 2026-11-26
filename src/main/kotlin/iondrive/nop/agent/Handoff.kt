package iondrive.nop.agent

import iondrive.nop.agent.transcript.Normalize
import java.io.IOException
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

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

    /** Builds the handoff from the event log without loading the conversation into memory. */
    internal fun write(log: EventLog, at: Path): Written =
        write(log.file, at) { scan -> log.readEvents { it.forEach(scan) } }

    @Suppress("UNUSED_PARAMETER")
    fun write(
        sessionId: String,
        events: List<AgentEvent>,
        target: Provider,
        at: Path = nextPath(sessionId),
    ): Written = write(EventLog.sessionsDir().resolve("$sessionId.jsonl"), at) { scan -> events.forEach(scan) }

    private fun write(
        sourceLog: Path,
        at: Path,
        read: ((AgentEvent) -> Unit) -> Unit,
    ): Written {
        check(isAvailable(at)) { "A handoff or its history already exists at $at" }
        val historyPath = historyPath(at)
        // The staged conversation lets the history open with the task and transcript paths.
        // Stage outside the handoffs tree so backup only sees finished files.
        val staging = Files.createTempDirectory(OwnerOnly.directory(Accounts.dataRoot()), ".handoff-")
        val conversation = staging.resolve("conversation.md")
        val history = staging.resolve("history.md")
        val brief = staging.resolve("brief.md")
        try {
            val scan = Files.newOutputStream(OwnerOnly.file(conversation)).bufferedWriter().use { writer ->
                Scan(writer).also { read(it::accept) }
            }
            val details = scan.finish()
            val text = brief(details, historyPath)
            Files.newOutputStream(OwnerOnly.file(history)).bufferedWriter().use {
                writeHistory(it, sourceLog, conversation, details)
            }
            Files.newOutputStream(OwnerOnly.file(brief)).bufferedWriter().use { it.append(text) }
            OwnerOnly.directory(at.parent)
            Files.move(history, historyPath)
            try {
                Files.move(brief, at)
            } catch (e: IOException) {
                try {
                    Files.deleteIfExists(historyPath)
                } catch (cleanup: IOException) {
                    e.addSuppressed(cleanup)
                }
                throw e
            }
            return Written(at, fromScreenOnly = details.fromScreenOnly, text = text)
        } finally {
            Files.deleteIfExists(conversation)
            Files.deleteIfExists(history)
            Files.deleteIfExists(brief)
            Files.deleteIfExists(staging)
        }
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
    fun summary(events: List<AgentEvent>, target: Provider): String =
        brief(Scan().also { events.forEach(it::accept) }.finish(), null)

    private fun brief(details: Details, history: Path?): String {
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
        section("Original Task", details.task, 1_000)
        section("Recent User Instructions", details.recentPrompts.joinToString("\n\n"), 1_900)
        section("Remaining Work", details.remainingWork, 3_000)
        val files = details.files()
        val fileCount = details.created.size + details.changed.size
        section("Files Modified", files.drop((fileCount - 12).coerceAtLeast(0)).joinToString("\n") +
            if (fileCount > 12) "\n(${fileCount - 12} earlier files omitted; see history.)" else "", 1_000)
        section("Commands Run", details.commands.entries.toList().takeLast(5)
            .joinToString("\n") { (cmd, code) -> commandLine(cmd, code, showUnknown = true) }, 800)
        if (details.fromScreenOnly) {
            section("Agent Work Log", "The previous provider's transcript could not be read; " +
                "terminal output is incomplete.\n" + details.screenTail, 1_000)
        } else {
            section("Recent Conversation", details.conversationTail, 1_050)
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
            !CONTEXT.containsMatchIn(value)
    }

    private fun writeHistory(out: Writer, source: Path, conversation: Path, details: Details) {
        out.appendLine("Event log (for exact source records): $source").appendLine()
        out.appendLine("<previous_session>")
        out.appendLine("## Original Task").appendLine(details.task).appendLine()
        if (details.transcripts.isNotEmpty()) {
            out.appendLine("## Source Transcripts")
            details.transcripts.forEach { out.appendLine("- `$it`") }
            out.appendLine()
        }
        if (details.conversationTail.isNotBlank()) {
            out.appendLine("## Conversation History")
            Files.newBufferedReader(conversation).use { it.transferTo(out) }
        } else if (details.screenHistory.isNotBlank()) {
            out.appendLine("## Agent Work Log")
            out.appendLine("(Reconstructed from terminal output — the previous provider's transcript " +
                "could not be read, so this is less complete than it looks.)")
            out.appendLine(details.screenHistory).appendLine()
        }
        if (details.created.isNotEmpty() || details.changed.isNotEmpty()) {
            out.appendLine("## Files Modified")
            details.files().forEach { out.appendLine(it) }
            out.appendLine()
        }
        if (details.commands.isNotEmpty()) {
            out.appendLine("## Commands Run")
            details.commands.forEach { (command, code) -> out.appendLine(commandLine(command, code)) }
            out.appendLine()
        }
        out.appendLine("## Remaining Work").appendLine(details.remainingWork)
        out.append("</previous_session>")
    }

    private fun commandLine(command: String, code: Int?, showUnknown: Boolean = false): String =
        "- `$command`" + when (code) {
            null -> if (showUnknown) " — outcome not recorded" else ""
            0 -> " — succeeded"
            else -> " — **failed (exit $code)**"
        }

    /** Streams conversation text while keeping excerpts and tool metadata for the brief. */
    private class Scan(private val conversation: Writer? = null) {
        private var task: String? = null
        private val recentPrompts = ArrayDeque<String>()
        private val transcripts = linkedSetOf<String>()
        private val conversationTail = Tail(1_000)
        private var hadTranscript = false
        private var hadScreen = false
        private val screenHead = StringBuilder()
        private val screenTail = Tail(SCREEN_LIMIT)
        private var lastWords: String? = null
        private var aborted = false
        private val created = linkedSetOf<String>()
        private val changed = linkedSetOf<String>()
        private val commands = linkedMapOf<String, Int?>()
        private val calls = mutableMapOf<String, MutableList<ToolCall>>()
        private val outcomes = linkedMapOf<String, Outcome>()
        private val digest = MessageDigest.getInstance("SHA-256")

        fun accept(event: AgentEvent) {
            when (event) {
                is AgentEvent.RunStarted -> event.transcriptPath?.let { transcripts += it }
                is AgentEvent.UserMessage -> user(event.text)
                is AgentEvent.AssistantMessage -> assistant(event)
                is AgentEvent.ToolStarted -> toolStarted(event)
                is AgentEvent.ToolFinished -> {
                    val failed = event.isError || (event.exitCode ?: 0) != 0
                    // Move repeated finishes to their last position in the log.
                    outcomes.remove(event.callId)
                    outcomes[event.callId] = Outcome(
                        event.at, event.exitCode ?: if (event.isError) 1 else null,
                        if (failed) Normalize.summarise(event.summary, FAILURE_LIMIT) else null,
                    )
                }
                is AgentEvent.ScreenTail -> {
                    hadScreen = true
                    val room = SCREEN_LIMIT * 2 / 3 - screenHead.length
                    screenHead.append(event.text, 0, minOf(room, event.text.length))
                    screenTail.append(event.text)
                }
                else -> Unit
            }
        }

        private fun user(text: String) {
            val isTask = isUserTask(text)
            val agentMessage = text.startsWith(AgentMessages.ENVELOPE_START)
            if (!isTask && !agentMessage) return
            if (isTask) {
                if (task == null) task = text
                else {
                    if (recentPrompts.size == 3) recentPrompts.removeFirst()
                    recentPrompts.addLast(excerpt(text, 600))
                }
            }
            val speaker = if (agentMessage) "Agent/service message" else "User"
            append("[$speaker]: ")
            append(text)
            append("\n\n")
        }

        private fun assistant(event: AgentEvent.AssistantMessage) {
            hadTranscript = true
            aborted = event.stopReason?.startsWith("aborted") == true
            val words = event.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
            if (words.isNotBlank()) lastWords = excerpt(words, 1_700)
            var started = false
            for (block in event.blocks) {
                val text = when (block) {
                    is Block.Text -> block.text
                    is Block.Thinking -> continue
                    is Block.ToolCall -> {
                        val what = Normalize.describe(block.tool, block.args)
                        if (what.isBlank()) continue
                        "[Tool: ${block.tool}] $what"
                    }
                }
                if (!started) append("[Assistant]:\n")
                started = true
                append(text)
                append("\n")
            }
            if (started) append("\n")
        }

        private fun append(text: String) {
            conversation?.append(text)
            conversationTail.append(text)
        }

        private fun toolStarted(event: AgentEvent.ToolStarted) {
            hadTranscript = true
            val subject = Normalize.describe(event.tool, event.args)
            if (subject.isNotBlank()) {
                when (event.tool) {
                    "Write" -> created += subject
                    "Edit", "NotebookEdit" -> changed += subject
                }
            }
            val command = subject.takeIf { event.tool == "Bash" && INTERESTING_COMMAND.containsMatchIn(it) }
                ?.take(COMMAND_LIMIT)
            if (command != null) commands[command] = null
            // Keep exact retry identity without retaining tool arguments for the whole scan.
            val key = buildString {
                append(event.tool.length).append(':').append(event.tool)
                event.args.toSortedMap().forEach { (key, value) ->
                    append(key.length).append(':').append(key)
                    append(value.length).append(':').append(value)
                }
            }
            val identity = HexFormat.of().formatHex(digest.digest(key.toByteArray()))
            calls.getOrPut(event.callId) { mutableListOf() } += ToolCall(identity, command)
        }

        fun finish(): Details {
            // A successful exact retry clears the earlier failure, including for calls that ran
            // multiple commands. Retrying one of those commands leaves the combined failure.
            val failures = linkedMapOf<List<String>, Outcome>()
            for ((id, outcome) in outcomes) {
                val started = calls[id]
                started?.forEach { call -> call.command?.let { commands[it] = outcome.exitCode } }
                val key = started?.map { it.identity } ?: listOf("", id)
                if (outcome.failure == null) failures.remove(key)
                else failures[key] = outcome
            }
            val pending = calls.entries.sumOf { (id, started) -> if (id in outcomes) 0 else started.size }
            val remaining = remainingWork(pending, failures.values)
            changed -= created
            val screen = screenTail.text()
            val history = if (!screenTail.truncated) screen.trim()
                else "$screenHead\n…\n" + screen.takeLast(SCREEN_LIMIT / 3).trimEnd()
            val tail = conversationTail.text().trim()
            return Details(
                task = task ?: "Continue the work already in progress in this project.",
                recentPrompts = recentPrompts,
                transcripts = transcripts,
                conversationTail = if (conversationTail.truncated) "(earlier turns omitted)\n$tail" else tail,
                fromScreenOnly = !hadTranscript && hadScreen,
                screenTail = screen.takeLast(800),
                screenHistory = history,
                created = created,
                changed = changed,
                commands = commands.entries.toList().takeLast(MAX_COMMANDS).associate { it.key to it.value },
                remainingWork = remaining,
            )
        }

        private fun remainingWork(pending: Int, failures: Collection<Outcome>): String = buildString {
            val words = lastWords
            if (words != null) append("The previous agent's last message was:\n\n").append(words)
            else append("The previous agent left no closing message.")
            if (aborted) append("\n\nIts final turn was interrupted, so it may be mid-change.")
            if (pending > 0) append("\n\nAt the switch, $pending tool call(s) had no recorded finish; their outcomes are unknown.")
            if (failures.isNotEmpty()) {
                append("\n\nHistorical failures (verify whether they still need attention):")
                failures.sortedBy { it.at }.takeLast(MAX_FAILURES).forEach { append("\n- ").append(it.failure) }
            }
            append("\n\nPick the task up from here rather than starting discovery again.")
        }
    }

    private data class ToolCall(val identity: String, val command: String?)
    private data class Outcome(val at: Long, val exitCode: Int?, val failure: String?)

    private data class Details(
        val task: String,
        val recentPrompts: List<String>,
        val transcripts: Set<String>,
        val conversationTail: String,
        val fromScreenOnly: Boolean,
        val screenTail: String,
        val screenHistory: String,
        val created: Set<String>,
        val changed: Set<String>,
        val commands: Map<String, Int?>,
        val remainingWork: String,
    ) {
        fun files(): Sequence<String> =
            created.asSequence().map { "- Created: `$it`" } + changed.asSequence().map { "- Modified: `$it`" }
    }

    private class Tail(private val limit: Int) {
        private val buffer = StringBuilder(limit)
        var truncated = false
            private set

        fun append(text: String) {
            if (text.length >= limit) {
                truncated = truncated || buffer.isNotEmpty() || text.length > limit
                buffer.setLength(0)
                buffer.append(text, text.length - limit, text.length)
            } else {
                val discard = buffer.length + text.length - limit
                if (discard > 0) {
                    truncated = true
                    buffer.delete(0, discard)
                }
                buffer.append(text)
            }
        }

        fun text(): String = buffer.toString()
    }

    internal fun fromScreenOnly(log: EventLog): Boolean = try {
        log.readEvents(::fromScreenOnly)
    } catch (_: IOException) {
        false
    }

    fun fromScreenOnly(events: List<AgentEvent>): Boolean = fromScreenOnly(events.asSequence())

    private fun fromScreenOnly(events: Sequence<AgentEvent>): Boolean {
        var hadScreen = false
        for (event in events) {
            if (event is AgentEvent.AssistantMessage || event is AgentEvent.ToolStarted) return false
            if (event is AgentEvent.ScreenTail) hadScreen = true
        }
        return hadScreen
    }

    private val CONTEXT =
        Regex("^<(environment_context|user_instructions|local-command-caveat|local-command-stdout|command-name)>")

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
