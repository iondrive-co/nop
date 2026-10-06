package iondrive.nop.agent.transcript

import iondrive.nop.agent.AgentEvent
import iondrive.nop.agent.Antigravity
import iondrive.nop.agent.Block
import iondrive.nop.agent.TokenUsage
import iondrive.nop.agent.arr
import iondrive.nop.agent.long
import iondrive.nop.agent.obj
import iondrive.nop.agent.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Reads an Antigravity run from the conversation's own transcript.
 *
 * `agy` writes one per conversation, at `brain/<id>/.system_generated/logs/transcript.jsonl`: a
 * row per step, carrying the user's prompt (`USER_INPUT`), each model turn with its reasoning and
 * tool calls (`PLANNER_RESPONSE`), and each tool's result (`GENERIC`). That is everything a handoff
 * needs, in a file that belongs to exactly one conversation — so nothing in it can be another
 * tab's.
 *
 * The hard part is knowing which conversation is this run's. The CLI names it, nop does not, and
 * the account-wide records point the wrong way: `history.jsonl` and `cache/last_conversations.json`
 * are shared by every run of the account, and the first prompt of a conversation carries no id at
 * all, so with two tabs on one project they cannot say which tab typed what. What does say is the
 * process: a running `agy` holds `presence/<id>.lock` open for the conversation it is in, so the
 * run's own process tree names its conversation, and a `/clear` that moves it to a new one moves
 * the lock with it ([switched]).
 *
 * When there is no process to ask — a test, or a system without `/proc` — the id the run was
 * resumed with is used, and failing that the newest history line from this project that names
 * a conversation, which is right whenever only one tab is running in the project.
 */
class AntigravityTailer(
    private val home: Path,
    /**
     * The conversations a process tree holds a presence lock for, or null when it cannot be told.
     * Replaceable so a test can stand in for `/proc`.
     */
    private val held: (home: Path, pid: Long) -> List<String>? = ::heldConversations,
) : Tailer {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var conversationId: String? = null

    @Volatile
    private var titledConversationId: String? = null

    /** When the presence locks were last read, so a quiet run does not walk `/proc` every pass. */
    private var heldCheckedAt = 0L

    /**
     * Calls made in the model turn last read and not answered yet, oldest first. The transcript
     * gives a call no id, and answers a turn's calls with one `GENERIC` row each, in order.
     */
    private val pending = ArrayDeque<String>()
    private val startedAtOf = HashMap<String, Long>()

    override fun nativeSessionId(): String? = conversationId

    override fun poll(): List<AgentEvent> {
        val id = conversationId ?: return emptyList()
        if (id == titledConversationId) return emptyList()

        val title = Antigravity.conversationTitle(home, id) ?: return emptyList()
        titledConversationId = id
        return listOf(AgentEvent.SessionTitled(title, System.currentTimeMillis()))
    }

    /** This run's conversation transcript, once the CLI is in a conversation and has written it. */
    override fun locate(run: RunContext): Path? {
        // Until the user types, there is nothing to find; a process walk four times a second for
        // every idle tab buys nothing.
        if (run.pid != null && System.currentTimeMillis() - heldCheckedAt < HELD_CHECK_MS) return null
        val id = conversationOf(run, current = null) ?: return null
        val transcript = Antigravity.conversationTranscript(home, id)
        if (!Files.isRegularFile(transcript)) return null
        conversationId = id
        return transcript
    }

    /** The transcript of the conversation the CLI has moved to, after a `/clear` or a `/resume`. */
    override fun switched(run: RunContext, current: Path): Path? {
        val now = System.currentTimeMillis()
        if (now - heldCheckedAt < HELD_CHECK_MS) return null
        val id = conversationOf(run, current = conversationId) ?: return null
        if (id == conversationId) return null
        val transcript = Antigravity.conversationTranscript(home, id)
        if (!Files.isRegularFile(transcript)) return null
        conversationId = id
        pending.clear()
        return transcript
    }

    /**
     * The conversation this run is in, by the best evidence there is.
     *
     * A process that can be asked is final: when it holds no lock it is not in a conversation yet,
     * whatever the shared files say. [current] is kept while it is still held, so a process holding
     * more than one lock does not flip between them.
     */
    private fun conversationOf(run: RunContext, current: String?): String? {
        val pidOf = run.pid
        if (pidOf != null) {
            heldCheckedAt = System.currentTimeMillis()
            val pid = pidOf() ?: return null
            val locks = held(home, pid)
            if (locks != null) {
                val mine = locks.filterNot { run.foreign(it) }
                if (current != null && current in mine) return current
                return mine.maxByOrNull { Antigravity.conversationTranscript(home, it).modifiedAt() }
                    ?: run.nativeSessionId?.takeIf { current == null }
            }
        }
        return current ?: run.nativeSessionId ?: conversationFromHistory(run)
    }

    override fun parse(line: String): List<AgentEvent> {
        val record = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return emptyList()
        val at = record["created_at"].str()?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: System.currentTimeMillis()
        return when (record["type"].str()) {
            "USER_INPUT" -> userInput(record, at)
            "PLANNER_RESPONSE" -> plannerResponse(record, at)
            "GENERIC" -> toolResult(record, at)
            // The CLI's own notices (a background task finishing, a checkpoint) and its errors are
            // not anything either side said, and the turns around them already carry what they mean.
            else -> emptyList()
        }
    }

    private fun userInput(record: JsonObject, at: Long): List<AgentEvent> {
        val content = record["content"].str() ?: return emptyList()
        // The CLI wraps the prompt with metadata of its own (the time, a settings change); the
        // request inside is what the user typed.
        val text = USER_REQUEST.find(content)?.groupValues?.get(1) ?: content
        if (text.isBlank()) return emptyList()
        return finishPending(at) + AgentEvent.UserMessage(text.trim(), at)
    }

    private fun plannerResponse(record: JsonObject, at: Long): List<AgentEvent> {
        // The model only speaks again once every call of its last turn has been answered, so
        // anything still waiting was answered by a row this reader does not count as a result.
        val events = finishPending(at).toMutableList()
        val step = record["step_index"].str() ?: at.toString()
        val blocks = mutableListOf<Block>()
        record["thinking"].str()?.takeIf { it.isNotBlank() }?.let { blocks += Block.Thinking(it) }
        record["content"].str()?.takeIf { it.isNotBlank() }?.let { blocks += Block.Text(it) }

        val started = mutableListOf<AgentEvent>()
        record["tool_calls"].arr()?.forEachIndexed { i, element ->
            val call = element.obj() ?: return@forEachIndexed
            val name = call["name"].str() ?: return@forEachIndexed
            val (tool, args) = neutral(name, decodedArgs(call["args"].obj()))
            val callId = "${conversationId ?: "agy"}:$step.$i"
            blocks += Block.ToolCall(callId, tool, args)
            started += AgentEvent.ToolStarted(callId, tool, args, at)
            pending.addLast(callId)
            startedAtOf[callId] = at
        }
        if (blocks.isNotEmpty()) {
            events += AgentEvent.AssistantMessage(
                blocks = blocks,
                usage = TokenUsage(
                    inputTokens = record["input_tokens"].long(),
                    outputTokens = record["output_tokens"].long(),
                    cacheReadTokens = record["cache_read_tokens"].long(),
                ),
                at = at,
            )
        }
        return events + started
    }

    private fun toolResult(record: JsonObject, at: Long): List<AgentEvent> {
        val callId = pending.removeFirstOrNull() ?: return emptyList()
        val error = record["error"].str()?.takeIf { it.isNotBlank() }
        val content = record["content"].str().orEmpty()
        val status = record["status"].str()
        return listOf(
            AgentEvent.ToolFinished(
                callId = callId,
                summary = Normalize.summarise(error ?: content.lineSequence().dropWhile { STAMP.matches(it) }.joinToString("\n")),
                isError = error != null || status == "ERROR" || status == "INVALID",
                exitCode = EXIT_CODE.find(content)?.groupValues?.get(1)?.toIntOrNull(),
                durationMs = startedAtOf.remove(callId)?.let { at - it },
                at = at,
            ),
        )
    }

    private fun finishPending(at: Long): List<AgentEvent> {
        if (pending.isEmpty()) return emptyList()
        val done = pending.map { AgentEvent.ToolFinished(it, durationMs = startedAtOf.remove(it)?.let { s -> at - s }, at = at) }
        pending.clear()
        return done
    }

    /**
     * The call's arguments as plain strings. The CLI stores each one JSON-encoded a second time —
     * a path arrives as `"\"/home/…\""` — so a value that parses as a JSON string is unwrapped.
     */
    private fun decodedArgs(raw: JsonObject?): Map<String, String> {
        if (raw == null) return emptyMap()
        return raw.mapNotNull { (key, value) ->
            val text = value.str() ?: return@mapNotNull null
            val decoded = runCatching { json.parseToJsonElement(text) }.getOrNull()
            key to ((decoded as? JsonPrimitive)?.takeIf { it.isString }?.content ?: text)
        }.toMap()
    }

    /**
     * The call in the vocabulary the handoff reads — `Bash` for a command, `Write`/`Edit` for a
     * file — so "files modified" and "commands run" come out of an agy session the same way they
     * come out of the other two.
     */
    private fun neutral(name: String, args: Map<String, String>): Pair<String, Map<String, String>> {
        fun pick(vararg pairs: Pair<String, String>) =
            pairs.mapNotNull { (from, to) -> args[from]?.let { to to it.take(ARG_LIMIT) } }.toMap()
        return when (name) {
            "run_command" -> "Bash" to pick("CommandLine" to "command", "Cwd" to "workdir")
            "view_file" -> "Read" to pick("AbsolutePath" to "file_path")
            "write_to_file" -> "Write" to pick("TargetFile" to "file_path")
            "replace_file_content", "multi_replace_file_content" -> "Edit" to pick("TargetFile" to "file_path")
            "search_web" -> "WebSearch" to pick("query" to "query")
            "read_url_content" -> "WebFetch" to pick("Url" to "url")
            else -> name to args.filterKeys { it != "toolAction" }
                .mapKeys { (key, _) -> if (key == "toolSummary") "description" else key }
                .mapValues { it.value.take(ARG_LIMIT) }
        }
    }

    /**
     * The conversation a prompt typed in this project during this run went to, when the process
     * cannot be asked. Only a line that names its conversation counts; the first prompt of a
     * conversation does not, and guessing it from another line is how a tab ends up in a sibling's.
     */
    private fun conversationFromHistory(run: RunContext): String? {
        val file = Antigravity.historyFile(home)
        val wanted = run.projectDir.toAbsolutePath().normalize().toString()
        return runCatching {
            Files.readAllLines(file).asReversed().firstNotNullOfOrNull { line ->
                val record = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    ?: return@firstNotNullOfOrNull null
                val at = record["timestamp"].long() ?: return@firstNotNullOfOrNull null
                if (at < run.startedAt - CLOCK_SLACK_MS || record["workspace"].str() != wanted) {
                    return@firstNotNullOfOrNull null
                }
                record["conversationId"].str()?.takeIf { !run.foreign(it) }
            }
        }.getOrNull()
    }

    private fun Path.modifiedAt(): Long = runCatching { Files.getLastModifiedTime(this).toMillis() }.getOrDefault(0L)

    private companion object {
        /**
         * The CLI stamps history lines from its own clock and writes them a moment after it starts,
         * so a line belonging to this run can read as a hair older than the spawn that made it.
         */
        const val CLOCK_SLACK_MS = 5_000L

        /** How often a run already following a transcript looks for a conversation it moved to. */
        const val HELD_CHECK_MS = 1_000L

        const val ARG_LIMIT = 500

        val USER_REQUEST = Regex("""<USER_REQUEST>\s*(.*?)\s*</USER_REQUEST>""", RegexOption.DOT_MATCHES_ALL)
        val EXIT_CODE = Regex("""The command exited with code (-?\d+)""")

        /** The header lines the CLI opens every tool result with, which say nothing about it. */
        val STAMP = Regex("""^(Created At|Completed At): .*""")
    }
}

/**
 * The conversations [pid] and the processes under it hold a presence lock for in [home], or null
 * when the process cannot be inspected (no `/proc`, or it has gone).
 */
internal fun heldConversations(home: Path, pid: Long): List<String>? {
    val root = Path.of("/proc", pid.toString(), "fd")
    if (!Files.isDirectory(root)) return null
    val presence = Antigravity.presenceDir(home).let { dir ->
        runCatching { dir.toRealPath() }.getOrElse { dir.toAbsolutePath().normalize() }
    }
    val pids = listOf(pid) + runCatching {
        ProcessHandle.of(pid).map { handle -> handle.descendants().map { it.pid() }.toList() }.orElse(emptyList())
    }.getOrDefault(emptyList())
    val ids = linkedSetOf<String>()
    for (p in pids) {
        val fds = runCatching { Files.list(Path.of("/proc", p.toString(), "fd")).use { it.toList() } }.getOrNull() ?: continue
        for (fd in fds) {
            val target = runCatching { Files.readSymbolicLink(fd) }.getOrNull() ?: continue
            val name = target.fileName?.toString() ?: continue
            if (target.parent == presence && name.endsWith(".lock")) ids += name.removeSuffix(".lock")
        }
    }
    return ids.toList()
}
