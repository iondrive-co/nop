package iondrive.nop.agent.transcript

import iondrive.nop.agent.AgentEvent
import iondrive.nop.agent.Block
import iondrive.nop.agent.TokenUsage
import iondrive.nop.agent.Usage
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
 * Reads Codex's own session rollout.
 *
 * Unlike Claude there is no id to hand the CLI up front, so the file has to be found rather than
 * named: the newest `rollout-*.jsonl` under `<home>/.codex/sessions/YYYY/MM/DD/` whose `session_meta`
 * says it was opened on this project, written since the run started. Matching on the working
 * directory as well as the time is what keeps two sessions started seconds apart in two projects
 * from reading each other's transcripts.
 *
 * Codex's own token accounting arrives in `token_count` events rather than on the message, so the
 * usage of a turn is carried forward onto the next assistant message. The same events carry
 * `rate_limits`, which is the only place a Codex usage figure exists anywhere — see
 * [Usage][iondrive.nop.agent.Usage].
 */
class CodexTailer(private val home: Path) : Tailer {
    private val json = Json { ignoreUnknownKeys = true }

    /** Exec calls in flight, so an output can be paired with what started it. */
    private val callStartedAt = ConcurrentHashMap<String, Long>()

    /** The most recent `token_count`, attached to the next assistant message that arrives. */
    @Volatile
    private var pendingUsage: TokenUsage? = null

    @Volatile
    private var model: String? = null

    @Volatile
    private var sessionId: String? = null

    override fun nativeSessionId(): String? = sessionId

    override fun locate(run: RunContext): Path? {
        val root = home.resolve(".codex").resolve("sessions")
        if (!Files.isDirectory(root)) return null
        val wanted = run.projectDir.toAbsolutePath().normalize().toString()

        val candidates = runCatching {
            Files.walk(root).use { stream ->
                stream.filter {
                    Files.isRegularFile(it) &&
                        it.fileName.toString().startsWith("rollout-") &&
                        it.fileName.toString().endsWith(".jsonl") &&
                        modified(it) >= run.startedAt - CLOCK_SLACK_MS
                }.toList()
            }
        }.getOrDefault(emptyList()).sortedByDescending { modified(it) }

        for (file in candidates) {
            val meta = sessionMeta(file) ?: continue
            if (meta["cwd"].str() != wanted) continue
            val id = meta["id"].str()
            // A rollout another run is already following is not this run's, however new it is: two
            // Codex tabs on one project both match "newest rollout opened here", and the one that
            // lost would log the other's turns and take its name. See [RunContext.foreign].
            if (id != null && run.foreign(id)) continue
            sessionId = id
            return file
        }
        return null
    }

    override fun parse(line: String): List<AgentEvent> {
        val record = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return emptyList()
        val at = record["timestamp"].str()?.let(::epochMillis)
            ?: System.currentTimeMillis()
        val payload = record["payload"].obj() ?: return emptyList()

        return when (record["type"].str()) {
            "session_meta" -> {
                sessionId = payload["id"].str() ?: sessionId
                emptyList()
            }
            "turn_context" -> {
                model = payload["model"].str() ?: model
                emptyList()
            }
            "response_item" -> responseItem(payload, at)
            "event_msg" -> eventMsg(payload, at)
            else -> emptyList()
        }
    }

    private fun responseItem(payload: JsonObject, at: Long): List<AgentEvent> =
        when (payload["type"].str()) {
            // Only what the user actually typed. The `developer` role carries the CLI's own
            // permission and environment preamble, and the `assistant` role is already covered by
            // the agent_message event, which is the one that carries the final text.
            "message" -> if (payload["role"].str() == "user") {
                messageText(payload).takeIf { it.isNotBlank() }
                    ?.let { listOf(AgentEvent.UserMessage(it, at)) } ?: emptyList()
            } else {
                emptyList()
            }

            "function_call", "custom_tool_call", "local_shell_call" -> {
                val callId = payload["call_id"].str()
                    ?: payload["id"].str()
                val rawName = payload["name"].str()
                if (callId == null || rawName == null) {
                    emptyList()
                } else {
                    val tool = Normalize.tool("openai", rawName)
                    val args = Normalize.args(arguments(payload))
                    callStartedAt[callId] = at
                    listOf(AgentEvent.ToolStarted(callId, tool, args, at))
                }
            }

            "function_call_output", "custom_tool_call_output" -> {
                val callId = payload["call_id"].str()
                if (callId == null) {
                    emptyList()
                } else {
                    val output = outputText(payload)
                    listOf(
                        AgentEvent.ToolFinished(
                            callId = callId,
                            summary = Normalize.summarise(output),
                            isError = exitCodeOf(output)?.let { it != 0 } ?: false,
                            exitCode = exitCodeOf(output),
                            durationMs = callStartedAt.remove(callId)?.let { at - it },
                            at = at,
                        ),
                    )
                }
            }

            "reasoning" -> summaryText(payload).takeIf { it.isNotBlank() }
                ?.let { listOf(AgentEvent.AssistantMessage(listOf(Block.Thinking(it)), model = model, at = at)) }
                ?: emptyList()

            else -> emptyList()
        }

    private fun eventMsg(payload: JsonObject, at: Long): List<AgentEvent> =
        when (payload["type"].str()) {
            "user_message" -> payload["message"].str()
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(AgentEvent.UserMessage(it, at)) } ?: emptyList()

            "agent_message" -> payload["message"].str()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    val usage = pendingUsage
                    pendingUsage = null
                    listOf(
                        AgentEvent.AssistantMessage(
                            blocks = listOf(Block.Text(it)),
                            usage = usage,
                            model = model,
                            at = at,
                        ),
                    )
                } ?: emptyList()

            "agent_reasoning" -> payload["text"].str()
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(AgentEvent.AssistantMessage(listOf(Block.Thinking(it)), model = model, at = at)) }
                ?: emptyList()

            // The real exit code and wall time, where the CLI records them separately rather than
            // folding them into the output text.
            "exec_command_end" -> {
                val callId = payload["call_id"].str()
                if (callId == null) {
                    emptyList()
                } else {
                    val exit = payload["exit_code"].int()
                    listOf(
                        AgentEvent.ToolFinished(
                            callId = callId,
                            summary = Normalize.summarise(
                                payload["stdout"].str()
                                    ?: payload["aggregated_output"].str() ?: "",
                            ),
                            isError = exit != null && exit != 0,
                            exitCode = exit,
                            durationMs = payload["duration"]?.let(::durationMillis)
                                ?: callStartedAt.remove(callId)?.let { at - it },
                            at = at,
                        ),
                    )
                }
            }

            "token_count" -> {
                pendingUsage = tokenUsage(payload)
                payload["rate_limits"].obj()?.let { Usage.recordCodexLimits(home, it) }
                emptyList()
            }

            // A turn the user interrupted. Worth recording as a stop reason: a handoff built from a
            // session that was cut off should not read as though the work finished.
            "turn_aborted" -> listOf(
                AgentEvent.AssistantMessage(
                    blocks = emptyList(),
                    stopReason = "aborted:" + (payload["reason"].str() ?: "unknown"),
                    model = model,
                    at = at,
                ),
            )

            else -> emptyList()
        }

    private fun tokenUsage(payload: JsonObject): TokenUsage? {
        val last = payload["info"].obj()?.get("last_token_usage").obj() ?: return null
        fun field(name: String) = last[name].long()
        return TokenUsage(
            inputTokens = field("input_tokens"),
            outputTokens = field("output_tokens"),
            cacheReadTokens = field("cached_input_tokens"),
            thinkingTokens = field("reasoning_output_tokens"),
        )
    }

    /**
     * The exit code an exec output reports in its own text.
     *
     * Codex writes shell results as a short preamble followed by the output, and the code is only
     * ever in that preamble — so the search is bounded rather than run over a megabyte of build log
     * that might happen to contain the phrase.
     */
    private fun exitCodeOf(output: String): Int? =
        EXIT_CODE.find(output.take(EXIT_CODE_SEARCH_CHARS))?.groupValues?.get(1)?.toIntOrNull()

    private fun arguments(payload: JsonObject): JsonObject? {
        payload["arguments"].obj()?.let { return it }
        // Usually a JSON string rather than an object, since it is what the model emitted.
        val raw = payload["arguments"].str()
            ?: payload["input"].str()
            ?: return payload["input"].obj()
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private fun messageText(payload: JsonObject): String =
        (payload["content"].arr())?.mapNotNull {
            it.obj()?.get("text").str()
        }?.joinToString("\n").orEmpty()

    private fun summaryText(payload: JsonObject): String =
        (payload["summary"].arr())?.mapNotNull {
            it.obj()?.get("text").str()
        }?.joinToString("\n").orEmpty()

    private fun outputText(payload: JsonObject): String {
        payload["output"].str()?.let { return it }
        payload["output"].obj()?.get("content").str()?.let { return it }
        return ""
    }

    private fun durationMillis(element: kotlinx.serialization.json.JsonElement): Long? {
        val obj = element.obj() ?: return null
        val secs = obj["secs"].long() ?: return null
        val nanos = obj["nanos"].long() ?: 0
        return secs * 1000 + nanos / 1_000_000
    }

    private fun sessionMeta(file: Path): JsonObject? = runCatching {
        Files.newBufferedReader(file).use { reader ->
            val first = reader.readLine() ?: return null
            val record = json.parseToJsonElement(first).jsonObject
            if (record["type"].str() != "session_meta") return null
            record["payload"].obj()
        }
    }.getOrNull()

    private fun modified(path: Path): Long =
        runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)

    private fun epochMillis(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private companion object {
        /**
         * Codex stamps a rollout's name from its own clock and files it under a dated directory, so
         * the file can read as a second or two older than the spawn that made it.
         */
        const val CLOCK_SLACK_MS = 5_000L

        val EXIT_CODE = Regex("""Process exited with code (-?\d+)""")
        const val EXIT_CODE_SEARCH_CHARS = 400
    }
}
