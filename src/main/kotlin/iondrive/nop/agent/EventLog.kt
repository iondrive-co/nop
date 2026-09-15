package iondrive.nop.agent

import iondrive.nop.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** One piece of an assistant turn, in the vocabulary both providers are mapped into. */
@Serializable
sealed interface Block {
    @Serializable @SerialName("text")
    data class Text(val text: String) : Block

    @Serializable @SerialName("thinking")
    data class Thinking(val text: String) : Block

    @Serializable @SerialName("tool_call")
    data class ToolCall(val callId: String, val tool: String, val args: Map<String, String>) : Block
}

/** What one assistant turn cost. Every field is optional: providers report different subsets. */
@Serializable
data class TokenUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheCreationTokens: Long? = null,
    val thinkingTokens: Long? = null,
)

/**
 * What happened in a session, in a vocabulary neither vendor uses.
 *
 * The point of a neutral log is the handoff: the summary Codex is given has to be built out of what
 * Claude did, and the only way that is possible without a special case per pair of providers is for
 * both to be read into one shape first.
 *
 * It is a fresh format rather than an inherited one, designed around what the tailers can actually
 * produce: a `ToolFinished` carries an exit code because both CLIs record one, and an
 * `AssistantMessage` carries `stopReason` because that is how you tell a turn that finished from a
 * turn that was cut off.
 */
@Serializable
sealed interface AgentEvent {
    /** Epoch millis. Ordering inside a file is the log's own, but times let two runs be compared. */
    val at: Long

    @Serializable @SerialName("session_started")
    data class SessionStarted(val projectPath: String, override val at: Long) : AgentEvent

    /**
     * A vendor CLI starting. Together with [RunEnded] this is the session's manifest: reopening a
     * session natively reads the last run's [nativeSessionId] and account straight off it.
     *
     * [transcriptOffset] is where the tailer had already read to, so a run picked up mid-flight
     * never replays what is already in the log.
     */
    @Serializable @SerialName("run_started")
    data class RunStarted(
        val provider: String,
        val account: String,
        val model: String? = null,
        val reasoning: String? = null,
        val nativeSessionId: String? = null,
        val transcriptPath: String? = null,
        val transcriptOffset: Long = 0,
        val argv: List<String> = emptyList(),
        val seededFromHandoff: Boolean = false,
        override val at: Long,
    ) : AgentEvent

    @Serializable @SerialName("user_message")
    data class UserMessage(val text: String, override val at: Long) : AgentEvent

    @Serializable @SerialName("assistant_message")
    data class AssistantMessage(
        val blocks: List<Block>,
        val usage: TokenUsage? = null,
        val stopReason: String? = null,
        val model: String? = null,
        override val at: Long,
    ) : AgentEvent

    @Serializable @SerialName("tool_started")
    data class ToolStarted(
        val callId: String,
        val tool: String,
        val args: Map<String, String> = emptyMap(),
        override val at: Long,
    ) : AgentEvent

    @Serializable @SerialName("tool_finished")
    data class ToolFinished(
        val callId: String,
        val summary: String = "",
        val isError: Boolean = false,
        val exitCode: Int? = null,
        val durationMs: Long? = null,
        override val at: Long,
    ) : AgentEvent

    /**
     * ANSI-stripped terminal output, written **only while a run has produced no transcript event
     * yet**.
     *
     * This is the degraded path, and the switch is one-way: the first event a tailer produces stops
     * it for good. A provider whose transcript format turns out to be unreadable still runs, still
     * gets quota detection, and still hands off — from a poorer summary built out of screen text.
     * A provider whose tailer works never bloats its log with redraws of its own output.
     */
    @Serializable @SerialName("screen_tail")
    data class ScreenTail(val text: String, override val at: Long) : AgentEvent

    @Serializable @SerialName("run_ended")
    data class RunEnded(val exitCode: Int?, val reason: EndReason, override val at: Long) : AgentEvent

    @Serializable @SerialName("provider_switched")
    data class ProviderSwitched(
        val from: String,
        val to: String,
        val reason: EndReason,
        val handoffPath: String? = null,
        override val at: Long,
    ) : AgentEvent

    /** A title the CLI gave the session itself — what the picker calls it next time. */
    @Serializable @SerialName("session_titled")
    data class SessionTitled(val title: String, override val at: Long) : AgentEvent
}

/** One earlier session, as the picker lists it. */
data class PastSession(
    val sessionId: String,
    val projectPath: String,
    val startedAt: Long,
    val title: String,
    val lastProvider: String?,
    val lastAccount: String?,
    val lastNativeSessionId: String?,
)

/**
 * One session's log: a JSONL file under `~/.local/share/nop/agent/sessions/<id>.jsonl`.
 *
 * Append-only and flushed per line, for the same reason nop's own log is: the interesting moment is
 * usually the one right before something stopped, and a buffered line is the one you lose.
 * Everything here is synchronised — the tailer thread, the quota tap and the UI all write to it.
 */
class EventLog private constructor(val file: Path) : AutoCloseable {

    private var writer: BufferedWriter? = runCatching {
        // Created owner-only before the writer opens it: what goes in here is everything the user
        // typed and everything the model said back, and the default umask would make that readable
        // by every other account on the machine.
        OwnerOnly.file(file)
        Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }.onFailure { Log.warn("could not open the agent session log $file: $it") }.getOrNull()

    /**
     * Whether a tailer has produced anything for the current run. While false, screen output is
     * logged as a fallback; the first real event turns it off for the rest of the run.
     */
    @Volatile
    var hasTranscriptEvents: Boolean = false
        private set

    @Synchronized
    fun append(event: AgentEvent) {
        if (event !is AgentEvent.ScreenTail && event !is AgentEvent.RunStarted &&
            event !is AgentEvent.SessionStarted && event !is AgentEvent.RunEnded
        ) {
            hasTranscriptEvents = true
        }
        val w = writer ?: return
        runCatching {
            w.write(JSON.encodeToString(AgentEvent.serializer(), event))
            w.newLine()
            w.flush()
        }.onFailure { Log.warn("could not write to the agent session log $file: $it") }
    }

    /** Logs screen output, but only while nothing better has arrived. See [AgentEvent.ScreenTail]. */
    fun appendScreenTail(text: String) {
        if (hasTranscriptEvents || text.isBlank()) return
        append(AgentEvent.ScreenTail(text, System.currentTimeMillis()))
    }

    /** Starts the fallback again for a fresh run, whose tailer has produced nothing yet. */
    @Synchronized
    fun beginRun() {
        hasTranscriptEvents = false
    }

    /** Everything written so far, in order. Unreadable lines are skipped rather than fatal. */
    fun events(): List<AgentEvent> = read(file)

    @Synchronized
    override fun close() {
        runCatching { writer?.close() }
        writer = null
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** How far into a log [sessions] looks for the pieces that label it. */
        private const val HEAD_LINES = 200

        fun sessionsDir(): Path = Accounts.dataRoot().resolve("sessions")

        fun open(sessionId: String): EventLog =
            EventLog(sessionsDir().resolve("$sessionId.jsonl"))

        /** Reads a log written earlier, without opening it for writing. */
        fun read(file: Path): List<AgentEvent> = runCatching {
            Files.readAllLines(file).mapNotNull { line ->
                line.takeIf { it.isNotBlank() }?.let {
                    runCatching { JSON.decodeFromString(AgentEvent.serializer(), it) }.getOrNull()
                }
            }
        }.getOrDefault(emptyList())

        /**
         * The sessions this project has had, newest first.
         *
         * Each log is read once, streaming, keeping only what a row needs: the label comes from the
         * head — a title, or failing that the first thing the user typed, both written early — and
         * the account to resume from comes from the last run anywhere in the file. That second part
         * cannot be taken from the head: after a provider switch the run at the top of the log is
         * the one that ran out, so a row built from it would offer to reopen the account that
         * already failed.
         */
        fun sessions(projectPath: Path): List<PastSession> {
            val dir = sessionsDir()
            val files = runCatching {
                Files.list(dir).use { it.filter { p -> p.fileName.toString().endsWith(".jsonl") }.toList() }
            }.getOrDefault(emptyList())
            val wanted = projectPath.toAbsolutePath().normalize().toString()

            return files.mapNotNull { file -> summarise(file, wanted) }
                .sortedByDescending { it.startedAt }
        }

        /**
         * The last run in a session, for reopening it natively. Reads the whole file — which is
         * fine, because it happens once, when the user clicks the session, rather than once per
         * session every time the picker is drawn.
         */
        fun lastRun(sessionId: String): AgentEvent.RunStarted? =
            read(sessionsDir().resolve("$sessionId.jsonl"))
                .filterIsInstance<AgentEvent.RunStarted>()
                .lastOrNull()

        private fun summarise(file: Path, projectPath: String): PastSession? {
            var started: AgentEvent.SessionStarted? = null
            var lastRun: AgentEvent.RunStarted? = null
            var titled: String? = null
            var firstPrompt: String? = null

            val read = runCatching {
                Files.newBufferedReader(file).use { reader ->
                    var index = 0
                    for (line in reader.lineSequence()) {
                        index += 1
                        if (line.isBlank()) continue
                        // Cheap pre-filter: past the head only run records still matter, and
                        // decoding a whole session's turns to find them would make drawing the
                        // picker cost reading every log in full.
                        val inHead = index <= HEAD_LINES
                        if (!inHead && "\"run_started\"" !in line) continue
                        val event = runCatching {
                            JSON.decodeFromString(AgentEvent.serializer(), line)
                        }.getOrNull() ?: continue
                        when (event) {
                            is AgentEvent.SessionStarted -> started = started ?: event
                            is AgentEvent.RunStarted -> lastRun = event
                            is AgentEvent.SessionTitled -> titled = event.title
                            is AgentEvent.UserMessage -> firstPrompt = firstPrompt ?: event.text
                            else -> Unit
                        }
                    }
                }
            }
            if (read.isFailure) return null

            val session = started ?: return null
            if (session.projectPath != projectPath) return null

            return PastSession(
                sessionId = file.fileName.toString().removeSuffix(".jsonl"),
                projectPath = session.projectPath,
                startedAt = session.at,
                title = titled ?: firstPrompt?.firstLine() ?: "Untitled session",
                lastProvider = lastRun?.provider,
                lastAccount = lastRun?.account,
                lastNativeSessionId = lastRun?.nativeSessionId,
            )
        }

        private fun String.firstLine(): String =
            lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120) ?: "Untitled session"
    }
}
