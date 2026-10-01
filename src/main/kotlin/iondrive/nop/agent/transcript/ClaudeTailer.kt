package iondrive.nop.agent.transcript

import iondrive.nop.agent.AgentEvent
import iondrive.nop.agent.Block
import iondrive.nop.agent.TokenUsage
import iondrive.nop.agent.arr
import iondrive.nop.agent.bool
import iondrive.nop.agent.int
import iondrive.nop.agent.long
import iondrive.nop.agent.obj
import iondrive.nop.agent.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads Claude Code's own session transcript.
 *
 * Locating it needs no guesswork: the CLI files a session at
 * `<configDir>/projects/<slug>/<sessionId>.jsonl`, and nop chose the session id when it built the
 * argv, so the path is known before the file exists. The slug is the working directory with every
 * `/` and `.` turned into `-`.
 *
 * The one thing that can move the file out from under this is the user, inside the TUI: `/clear`
 * and `/resume` both land in a different session id. [switched] watches the slug directory for a
 * newer transcript and follows it, which is what keeps the log honest through a fresh start typed
 * mid-run rather than simply stopping there.
 */
class ClaudeTailer(private val configDir: Path) : Tailer {
    /** When each `tool_use` was issued, so its result can carry a duration. */
    private val toolStartedAt = ConcurrentHashMap<String, Long>()

    @Volatile
    private var currentSessionId: String? = null

    /**
     * The sessions already filed in this project when the run started — the ones this run cannot
     * possibly have created, and so cannot possibly have moved into.
     *
     * Taken once, on the first look, and never refreshed. See [switched] for what it is for.
     */
    @Volatile
    private var alreadyThere: Set<String>? = null

    /** The sessions this run has switched away from. A `/clear` never leads back to one. */
    private val left: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun nativeSessionId(): String? = currentSessionId

    override fun locate(run: RunContext): Path? {
        val dir = projectDir(run)
        rememberWhatWasAlreadyThere(dir)
        val id = run.nativeSessionId
        if (id != null) {
            val exact = dir.resolve("$id.jsonl")
            if (Files.isRegularFile(exact)) {
                currentSessionId = id
                return exact
            }
            return null
        }
        // Only reached when nop did not mint an id, which it always does for Claude — kept honest
        // anyway, so this never adopts the transcript of a tab opened beside it.
        return newestSince(dir, run.startedAt, run.foreign)?.also { currentSessionId = it.sessionId() }
    }

    /**
     * A transcript that appeared after this one and is being written to — what a `/clear` inside
     * the TUI produces. Only a file newer than the one being followed counts, so the ordinary case
     * (the CLI appending to its own transcript) never trips it.
     *
     * A session another run is following is never it. Claude files every session in one project
     * under one directory, so a second agent tab on the same project writes a file that matches this
     * rule exactly as well as a `/clear` does — and the older tab would follow it, start logging
     * someone else's turns, and take the name the CLI gave that session. With several tabs open they
     * would all chase the newest and end up sharing its title. Only nop can tell the two apart, because
     * only nop knows which sessions it started: see [RunContext.foreign].
     *
     * And neither is a session that was already filed here before this run began. That guard only
     * covers the tabs nop is running, and a `claude` started from a shell in the same checkout is
     * invisible to it: a run of *this* tab could adopt a conversation somebody else is in the
     * middle of, log its turns, take its name, and — because the id it moved to is what the picker
     * writes down — offer to resume that stranger's session under this one's title. A `/clear` and
     * an in-TUI `/resume` both land in a file this run has just created, so "it was here before I
     * started" separates the case this exists for from that one.
     *
     * The cost is following a `/resume` typed into the TUI that picks an *older* session, which
     * appends to a file that was already here. The tab then keeps the name and the id of the
     * conversation nop opened it on, which is a stale answer rather than somebody else's.
     *
     * Nor is a session started *after* this run by something other than the TUI (see
     * [entrypoint]). A `claude -p` worker a manager agent delegates to, on the same account in the
     * same checkout, passes every rule above: the tab that started it would follow it as its own
     * `/clear`, go back to its own transcript whenever that was the newer file, and switch between
     * the two again and again, replaying one whole file or the other into its log each time.
     * nop only ever runs the TUI, so a candidate counts only once it says the TUI wrote it — which
     * a fresh `/clear` does at its first prompt rather than its first bookkeeping record.
     *
     * And a session this run has already left is never switched back to, because `/clear` only goes
     * forward. The cost is a `/resume` typed into the TUI that returns to one of them: the log stays
     * with the conversation it left for.
     */
    override fun switched(run: RunContext, current: Path): Path? {
        val dir = projectDir(run)
        rememberWhatWasAlreadyThere(dir)
        val already = alreadyThere.orEmpty()
        val currentStamp = modified(current)
        val candidate = runCatching {
            Files.list(dir).use { stream ->
                stream.toList().asSequence()
                    // Name first, and the name read once. Every rule that can be settled without
                    // touching the filesystem is settled before anything is stat'ed: on a project
                    // with any history behind it nearly every transcript in the directory was
                    // already there when this run started, and this runs on every pass of the
                    // tailer for every live session. Read the other way round — as it was — each
                    // pass stat'ed every file in the directory two or three times over and rebuilt
                    // its session id as many times again, which made this the single largest source
                    // of allocation events in the process while accounting for almost none of its
                    // bytes.
                    .mapNotNull { path ->
                        val name = path.fileName.toString()
                        if (!name.endsWith(".jsonl")) return@mapNotNull null
                        val id = name.removeSuffix(".jsonl")
                        val mine = path != current && id !in already && id !in left && !run.foreign(id)
                        if (mine) path else null
                    }
                    // One stat each, carried through to the comparison: `modified` was called twice
                    // in the filter and once more for every comparison `max` made.
                    .map { path -> path to modified(path) }
                    .filter { (_, stamp) -> stamp > currentStamp && stamp >= run.startedAt }
                    // Last, because it is the one rule that opens the file.
                    .filter { (path, _) -> entrypoint(path) == INTERACTIVE }
                    .maxByOrNull { (_, stamp) -> stamp }
                    ?.first
            }
        }.getOrNull() ?: return null
        left += current.sessionId()
        currentSessionId = candidate.sessionId()
        return candidate
    }

    /**
     * Takes the one snapshot [switched] compares against, on the first look at the directory.
     *
     * The first look is the tailer's first [locate], which the follower makes as soon as the CLI is
     * spawned — before a person could have typed anything into it, which is what makes the snapshot
     * mean "not this run's doing". A directory that is not there yet is an empty set and stays one:
     * the project has no sessions, so nothing in it can be somebody else's.
     */
    private fun rememberWhatWasAlreadyThere(dir: Path) {
        if (alreadyThere != null) return
        alreadyThere = runCatching {
            Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".jsonl") }
                    .map { it.sessionId() }
                    .toList()
                    .toSet()
            }
        }.getOrDefault(emptySet())
    }

    override fun parse(line: String): List<AgentEvent> {
        val record = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return emptyList()
        val at = record["timestamp"].str()?.let(::epochMillis)
            ?: System.currentTimeMillis()

        return when (record["type"].str()) {
            "user" -> user(record, at)
            "assistant" -> assistant(record, at)
            // Both spellings: newer builds write `ai-title`, older ones `custom-title`.
            "ai-title", "custom-title" -> listOfNotNull(
                (record["aiTitle"] ?: record["title"]).str()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { AgentEvent.SessionTitled(it, at) },
            )
            // Attachments are the CLI's own context injections, queue operations are its input
            // buffer, and last-prompt is a copy of something already logged. None of them is
            // anything the next provider needs to be told about.
            else -> emptyList()
        }
    }

    private fun user(record: JsonObject, at: Long): List<AgentEvent> {
        val content = record["message"].obj()?.get("content")

        // A plain string is the user typing. A list is the CLI handing tool results back to the
        // model, which is the only other thing that wears the `user` role.
        content.str()?.let { text ->
            return if (text.isBlank()) emptyList() else listOf(AgentEvent.UserMessage(text, at))
        }

        val blocks = content.arr() ?: return emptyList()
        val toolResult = record["toolUseResult"].obj()
        return blocks.mapNotNull { element ->
            val block = element.obj() ?: return@mapNotNull null
            if (block["type"].str() != "tool_result") return@mapNotNull null
            val callId = block["tool_use_id"].str() ?: return@mapNotNull null
            AgentEvent.ToolFinished(
                callId = callId,
                summary = Normalize.summarise(resultText(block, toolResult)),
                isError = block["is_error"].bool() || toolResult?.get("interrupted").bool(),
                // Only Bash records one. It is the difference between "ran the tests" and "ran the
                // tests and they failed", which is most of what a handoff needs from a command.
                exitCode = toolResult?.get("exitCode").int()
                    ?: toolResult?.get("exit_code").int(),
                durationMs = toolStartedAt.remove(callId)?.let { at - it },
                at = at,
            )
        }
    }

    private fun assistant(record: JsonObject, at: Long): List<AgentEvent> {
        val message = record["message"].obj() ?: return emptyList()
        val content = message["content"].arr() ?: return emptyList()

        val blocks = mutableListOf<Block>()
        val started = mutableListOf<AgentEvent>()
        for (element in content) {
            val block = element.obj() ?: continue
            when (block["type"].str()) {
                "text" -> block["text"].str()
                    ?.takeIf { it.isNotBlank() }?.let { blocks += Block.Text(it) }

                "thinking" -> block["thinking"].str()
                    ?.takeIf { it.isNotBlank() }?.let { blocks += Block.Thinking(it) }

                "tool_use" -> {
                    val callId = block["id"].str() ?: continue
                    val tool = block["name"].str() ?: continue
                    val args = Normalize.args(block["input"].obj())
                    blocks += Block.ToolCall(callId, tool, args)
                    started += AgentEvent.ToolStarted(callId, tool, args, at)
                    toolStartedAt[callId] = at
                }
            }
        }
        if (blocks.isEmpty()) return started

        val turn = AgentEvent.AssistantMessage(
            blocks = blocks,
            usage = usage(message["usage"].obj()),
            stopReason = message["stop_reason"].str(),
            model = message["model"].str(),
            at = at,
        )
        // The turn first, then the calls it made: a reader going forwards sees the reasoning that
        // led to a call before the call.
        return listOf(turn) + started
    }

    private fun usage(usage: JsonObject?): TokenUsage? {
        if (usage == null) return null
        fun field(name: String) = usage[name].long()
        return TokenUsage(
            inputTokens = field("input_tokens"),
            outputTokens = field("output_tokens"),
            cacheReadTokens = field("cache_read_input_tokens"),
            cacheCreationTokens = field("cache_creation_input_tokens"),
            thinkingTokens = usage["output_tokens_details"].obj()
                ?.get("thinking_tokens").long(),
        )
    }

    /**
     * What a tool actually returned. The `tool_result` block holds the model-facing content, which
     * is what a summary should quote; `toolUseResult` beside it holds the CLI's own structured
     * record, which is the fallback when the block's content is a list of parts rather than text.
     */
    private fun resultText(block: JsonObject, toolResult: JsonObject?): String {
        block["content"].str()?.let { return it }
        (block["content"].arr())?.let { parts ->
            val text = parts.mapNotNull {
                it.obj()?.get("text").str()
            }.joinToString("\n")
            if (text.isNotBlank()) return text
        }
        toResultString(toolResult)?.let { return it }
        return ""
    }

    private fun toResultString(toolResult: JsonObject?): String? {
        if (toolResult == null) return null
        listOf("stdout", "content", "output", "stderr").forEach { key ->
            toolResult[key].str()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun projectDir(run: RunContext): Path =
        configDir.resolve("projects").resolve(slug(run.projectDir))

    private fun newestSince(dir: Path, since: Long, foreign: (String) -> Boolean): Path? = runCatching {
        Files.list(dir).use { stream ->
            // Same shape as [switched]: the name settles what it can before anything is stat'ed,
            // and the one stat that survives is carried into the comparison rather than redone for
            // every pair `max` looks at.
            stream.toList().asSequence()
                .mapNotNull { path ->
                    val name = path.fileName.toString()
                    if (!name.endsWith(".jsonl")) return@mapNotNull null
                    if (foreign(name.removeSuffix(".jsonl"))) null else path
                }
                .map { path -> path to modified(path) }
                .filter { (_, stamp) -> stamp >= since }
                .maxByOrNull { (_, stamp) -> stamp }
                ?.first
        }
    }.getOrNull()

    private fun modified(path: Path): Long =
        runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)

    private fun Path.sessionId(): String = fileName.toString().removeSuffix(".jsonl")

    private fun epochMillis(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    companion object {
        /**
         * The CLI's directory name for a working directory: every `/` and `.` becomes `-`, so
         * `/home/dev/nop` files under `-home-dev-nop`.
         */
        fun slug(dir: Path): String =
            dir.toAbsolutePath().normalize().toString().replace('/', '-').replace('.', '-')

        /** The [entrypoint] of a session typed into the TUI — the only kind nop runs. */
        const val INTERACTIVE = "cli"

        /** How far into a transcript [entrypoint] looks. The field is on the third to fifth record. */
        private const val ENTRYPOINT_WITHIN = 50

        private val json = Json { ignoreUnknownKeys = true }

        /** Found entrypoints, by file. A transcript never changes what wrote it. */
        private val entrypoints = ConcurrentHashMap<Path, String>()

        /**
         * Which front end wrote the transcript at [file]: `cli` for the TUI, `sdk-cli` for
         * `claude -p`, and others for the IDE extensions. Null while no record carrying it has been
         * written yet — the CLI's own bookkeeping records at the top of the file do not.
         *
         * It is what tells this tab's `/clear` from a headless run on the same account and project,
         * which nop did not start and so cannot know about: a manager agent can delegate to
         * `claude -p` workers, and their transcripts land in the same directory as the tab's own.
         */
        fun entrypoint(file: Path): String? {
            entrypoints[file]?.let { return it }
            val found = runCatching {
                Files.newBufferedReader(file).use { reader ->
                    reader.lineSequence().take(ENTRYPOINT_WITHIN).firstNotNullOfOrNull { line ->
                        // A key inside a record's text is escaped, so this only matches a real one.
                        if ("\"entrypoint\"" !in line) return@firstNotNullOfOrNull null
                        runCatching { json.parseToJsonElement(line).jsonObject["entrypoint"].str() }.getOrNull()
                    }
                }
            }.getOrNull() ?: return null
            entrypoints[file] = found
            return found
        }

        /** Whether [file] is known to have been written by something other than the TUI. */
        fun writtenOutsideTui(file: Path): Boolean = entrypoint(file).let { it != null && it != INTERACTIVE }
    }
}
