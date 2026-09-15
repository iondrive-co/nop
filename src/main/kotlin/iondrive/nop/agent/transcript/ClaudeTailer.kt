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
    private val json = Json { ignoreUnknownKeys = true }

    /** When each `tool_use` was issued, so its result can carry a duration. */
    private val toolStartedAt = ConcurrentHashMap<String, Long>()

    @Volatile
    private var currentSessionId: String? = null

    override fun nativeSessionId(): String? = currentSessionId

    override fun locate(run: RunContext): Path? {
        val dir = projectDir(run)
        val id = run.nativeSessionId
        if (id != null) {
            val exact = dir.resolve("$id.jsonl")
            if (Files.isRegularFile(exact)) {
                currentSessionId = id
                return exact
            }
            return null
        }
        return newestSince(dir, run.startedAt)?.also { currentSessionId = it.sessionId() }
    }

    /**
     * A transcript that appeared after this one and is being written to — what a `/clear` inside
     * the TUI produces. Only a file newer than the one being followed counts, so the ordinary case
     * (the CLI appending to its own transcript) never trips it.
     */
    override fun switched(run: RunContext, current: Path): Path? {
        val dir = projectDir(run)
        val currentStamp = modified(current)
        val candidate = runCatching {
            Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".jsonl") && it != current }
                    .filter { modified(it) > currentStamp && modified(it) >= run.startedAt }
                    .max(compareBy { modified(it) })
                    .orElse(null)
            }
        }.getOrNull() ?: return null
        currentSessionId = candidate.sessionId()
        return candidate
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

    private fun newestSince(dir: Path, since: Long): Path? = runCatching {
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".jsonl") && modified(it) >= since }
                .max(compareBy { modified(it) })
                .orElse(null)
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
    }
}
