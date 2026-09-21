package iondrive.nop.agent

import iondrive.nop.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** One piece of an assistant turn, in the vocabulary every provider is mapped into. */
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
 * produce: a `ToolFinished` carries an exit code because the CLIs that report tool calls at all
 * report one, and an
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
        /**
         * The credential directory this run's CLI was pointed at.
         *
         * Recorded beside the account's *name* because the two are not interchangeable. A session
         * resumed out of a store rather than an account runs under a label no configured account
         * answers to — "outside nop" is the one nop coins itself — so a row rebuilt from the name
         * alone resolves to nothing, and the click asking for it is silently dropped. The directory
         * is what a resume actually needs; see [PastSession.home].
         *
         * Null in a log written before this was recorded.
         */
        val home: String? = null,
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
    /**
     * The credential directory the session's transcript lives in, for a row that came from a
     * vendor's own store rather than from one of nop's event logs — see [NativeSessions].
     *
     * Null means "look the account up by name", which is every session nop ran itself. It is set
     * for the sessions nop did *not* run, because those name a store rather than an account: work
     * done in a plain `claude` at a shell lands in the default config directory, which is usually
     * not one of the accounts the user has configured, and resuming it means launching the CLI
     * against that directory rather than picking a configured account to spend.
     */
    val home: String? = null,
    /**
     * When the session last did anything, as far as its files say: what the picker sorts by and
     * shows. Not [startedAt] — a conversation opened at breakfast and worked in all day is the one
     * the user was just in, and dating it by its first message sank it below everything begun since.
     */
    val lastActiveAt: Long = startedAt,
) {
    /**
     * The account reopening this session would run, or null when nop has no way back into it.
     *
     * One rule, in one place, because two of them disagreeing is what a dead row looks like: the
     * picker decides from this whether the row is clickable at all, and the click itself resolves
     * the same way. They used to differ — the row asked only whether the session named an account,
     * the click asked whether that name was one of [configured] — so a session run under a store
     * label offered itself, accepted the press and did nothing with it.
     *
     * A configured account wins over the recorded directory even when both are there: they are the
     * same credentials either way, and only the configured one carries the model and reasoning the
     * user chose for it.
     */
    fun accountIn(configured: List<Account>): Account? {
        if (lastNativeSessionId == null) return null
        val name = lastAccount ?: return null
        configured.firstOrNull { it.name == name }?.let { return it }
        // No account by that name, so the row names a store. That is resumable only if nop wrote
        // down which directory it was — a row from a log older than that field cannot be placed,
        // and guessing at a credential directory is not something to do on the user's behalf.
        val dir = home ?: return null
        return Account(
            name = name,
            // Every row that got this far names one. Claude is the fallback because it is the only
            // provider whose store nop reads directly, so an id from a version that knew about
            // more of them is a Claude session or nothing.
            provider = lastProvider?.let(Provider::byId) ?: Provider.Anthropic,
            home = dir,
        )
    }
}

/**
 * One session's log: a JSONL file under `~/.local/share/nop/agent/sessions/<id>.jsonl`.
 *
 * Append-only and flushed per line, for the same reason nop's own log is: the interesting moment is
 * usually the one right before something stopped, and a buffered line is the one you lose.
 * Everything here is synchronised — the tailer thread, the quota tap and the UI all write to it.
 */
class EventLog private constructor(val file: Path) : AutoCloseable {

    /**
     * Whether this log already held events before the session opened it — a tab put back from the
     * state file, or one reopened from the picker, as against a session that has never run.
     *
     * Read before the writer below creates the file, because afterwards there is no telling the two
     * apart. What it decides is whether the run about to start replays its vendor transcript from
     * the beginning: everything in that file is already in *this* one, so replaying it would file a
     * second copy of every message and, because the last title in the history wins, rename the tab
     * out from under the user. See [iondrive.nop.agent.transcript.RunContext.resumingLoggedWork].
     */
    val hadHistory: Boolean = runCatching { Files.size(file) > 0 }.getOrDefault(false)

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
         * The sessions this project has had, most recently active first.
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
                .sortedByDescending { it.lastActiveAt }
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
            // The spawn record of the last run, which is the one with no transcript yet: it is
            // when the CLI was started, where [lastRun] is when its transcript was found. See
            // [resumeId], which needs the difference.
            var lastSpawn: AgentEvent.RunStarted? = null
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
                            is AgentEvent.RunStarted -> {
                                lastRun = event
                                if (event.transcriptPath == null) lastSpawn = event
                            }
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

            val resolvedTitle = titled
                ?: (if (lastRun?.provider == Provider.Antigravity.id && lastRun.nativeSessionId != null && (lastRun.home ?: storeOf(lastRun)) != null) {
                    Antigravity.conversationTitle(Path.of(lastRun.home ?: storeOf(lastRun)), lastRun.nativeSessionId)
                } else null)
                ?: firstPrompt?.firstLine()
                ?: "Untitled session"

            return PastSession(
                sessionId = file.fileName.toString().removeSuffix(".jsonl"),
                projectPath = session.projectPath,
                startedAt = session.at,
                title = resolvedTitle,
                lastProvider = lastRun?.provider,
                lastAccount = lastRun?.account,
                lastNativeSessionId = resumeId(lastRun, lastSpawn?.at ?: session.at),
                home = lastRun?.home ?: storeOf(lastRun),
                // The log's own time rather than the transcript's: the tailer appends to it for
                // everything the conversation does, whichever provider, and one Antigravity keeps
                // its prompts in a single file for every conversation, whose time says nothing.
                lastActiveAt = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull()
                    ?: session.at,
            )
        }

        /**
         * The credential directory a run's transcript is sitting inside, for a log written before
         * that directory was recorded beside it.
         *
         * Claude files a session at `<configDir>/projects/<slug>/<id>.jsonl`, so the store is three
         * directories up — and the transcript path is the one piece of it every located run has
         * written down since long before [AgentEvent.RunStarted.home] existed. Without this, every
         * session in an existing log whose account is a store label rather than a configured
         * account stays unreachable for good: the row cannot be placed, the picker greys it out,
         * and recording the store from now on only helps the sessions that have not happened yet.
         *
         * Claude only, and only for a path shaped the way Claude shapes one. Codex nests its
         * rollouts by date under `<home>/.codex/sessions/`, where the same arithmetic lands on a
         * directory that is not a home at all — and a CLI pointed at the wrong credentials is a
         * worse answer than a row that admits it does not know.
         */
        private fun storeOf(run: AgentEvent.RunStarted?): String? {
            if (run?.provider != Provider.Anthropic.id) return null
            val path = run.transcriptPath ?: return null
            return runCatching {
                val projects = Path.of(path).parent?.parent ?: return null
                if (projects.fileName?.toString() != "projects") return null
                projects.parent?.toString()
            }.getOrNull()
        }

        /**
         * The vendor session a row resumes, or null when there is nothing on disk to resume.
         *
         * nop picks Claude's session id before the CLI starts and writes it down at the spawn, so
         * the id in the log exists from the first moment — while the conversation it names does
         * not. Open a session, close it without typing anything, and no transcript is ever filed
         * under that id; the picker still listed it as resumable, and the CLI answered the click
         * with "No conversation found with session ID".
         *
         * So the id only counts once a tailer has found the transcript it belongs to and that file
         * is still there. The located run is the one carrying a [AgentEvent.RunStarted.transcriptPath]
         * — logged a second time, on top of the spawn's own record, exactly when the file turns up.
         *
         * Nulling it also puts the session back in reach the other way round: the picker suppresses
         * a vendor's own row when one of these claims the same id, and a row that no longer claims
         * it stops hiding a transcript that is genuinely there. See [NativeSessions].
         */
        private fun resumeId(run: AgentEvent.RunStarted?, spawnedAt: Long): String? {
            val path = run?.transcriptPath ?: return null
            val followed = runCatching { Path.of(path) }.getOrNull() ?: return null
            val id = run.nativeSessionId
            val asked = askedFor(run)
            // The run ended up somewhere other than where nop pointed it. Typing `/clear` does
            // that, and so did a tailer adopting a conversation somebody else was already in — see
            // ClaudeTailer.switched, which no longer makes that mistake but cannot unwrite the logs
            // that recorded it. A conversation this run started began after this run did; one it
            // merely walked into was already going.
            if (asked != null && id != null && id != asked && !beganAfter(followed, spawnedAt)) {
                val its = followed.resolveSibling("$asked.jsonl")
                return asked.takeIf { Files.isRegularFile(its) }
            }
            return id?.takeIf { Files.isRegularFile(followed) }
        }

        /**
         * The session a run was pointed at when it was spawned, read off its own argv.
         *
         * The one id in a run's record that is not a guess: nop wrote that command line itself.
         * Everything else about which conversation a run was in is something a tailer worked out by
         * looking at files, which is exactly the part that can be wrong.
         */
        private fun askedFor(run: AgentEvent.RunStarted): String? {
            // Each CLI names the same thing differently: Codex takes a subcommand, Claude a flag,
            // agy a flag of its own. Matched per provider rather than by looking for any of them,
            // because a bare word is also what a seed prompt is.
            val flags = when (run.provider) {
                Provider.OpenAI.id -> setOf("resume")
                Provider.Antigravity.id -> setOf("--conversation")
                else -> setOf("--resume", "--session-id")
            }
            val at = run.argv.indexOfFirst { it in flags }
            return if (at >= 0) run.argv.getOrNull(at + 1)?.takeIf { it.isNotBlank() } else null
        }

        /**
         * Whether the conversation in [file] began at or after [at] — how a `/clear` is told from a
         * transcript that was already being written when this run started.
         *
         * Reads to the first record carrying a time and stops, which is the second or third line of
         * the file. Only reached when a run's id moved off the one nop asked for, which is three
         * sessions in a hundred, so the whole cost is a few lines of a file the picker would
         * otherwise not open at all.
         */
        private fun beganAfter(file: Path, at: Long): Boolean {
            val began = runCatching {
                Files.newBufferedReader(file).use { reader ->
                    reader.lineSequence().take(HEAD_LINES).firstNotNullOfOrNull { line ->
                        val stamp = runCatching {
                            JSON.parseToJsonElement(line).obj()?.get("timestamp").str()
                        }.getOrNull() ?: return@firstNotNullOfOrNull null
                        runCatching { java.time.Instant.parse(stamp).toEpochMilli() }.getOrNull()
                    }
                }
            }.getOrNull() ?: return false
            return began >= at
        }

        private fun String.firstLine(): String =
            lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120) ?: "Untitled session"
    }
}
