package iondrive.nop.agent.transcript

import iondrive.nop.Log
import iondrive.nop.agent.AgentEvent
import iondrive.nop.agent.EventLog
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * What a run looks like to a tailer: where it is running and what it is running as.
 *
 * Deliberately not the [AgentRun][iondrive.nop.agent.AgentRun] itself. A tailer reads files; giving
 * it the PTY and the widget as well would make it possible to write one that reads the screen, and
 * keeping those two channels apart is what lets a provider with an unreadable transcript still run.
 */
data class RunContext(
    val projectDir: Path,
    /** The account's credential home — for Claude the config dir, for Codex the whole HOME. */
    val home: Path,
    /** The id nop minted, when it could. Null means the CLI names the session itself. */
    val nativeSessionId: String?,
    /** Epoch millis the CLI was spawned. Used to ignore transcripts written before this run. */
    val startedAt: Long,
    /**
     * Whether a session id belongs to a *different* run nop is following — another agent tab on the
     * same project, whose transcript sits in the same directory as this one's and is newer.
     *
     * A tailer asks before it adopts a file it did not open by name. Without it the two "find the
     * newest one" rules below (`ClaudeTailer.switched`, `CodexTailer.locate`) cannot tell a tab that
     * was opened a minute ago from a `/clear` typed inside this one. See [LiveTranscripts].
     *
     * Defaults to "nothing is foreign", which is what a test with one run wants.
     */
    val foreign: (String) -> Boolean = { false },
)

/**
 * Turns one vendor's own transcript into [AgentEvent]s.
 *
 * Each CLI already writes a full record of its session to disk — Claude's carries thinking, tool
 * calls, token usage and stop reasons — so nop reads that rather than trying to reconstruct it from
 * the screen. The interface exists so the two providers' very different formats meet in one place,
 * and so a format that changes (Codex is already migrating to sqlite) can be replaced without
 * touching anything that consumes the events.
 */
interface Tailer {
    /** The file to follow, or null until the CLI has created it. Called repeatedly until it isn't. */
    fun locate(run: RunContext): Path?

    /** The events in one transcript line. Most lines produce none; some produce several. */
    fun parse(line: String): List<AgentEvent>

    /**
     * A newer transcript for the same run, when the user did something inside the TUI that started
     * one — `/clear` or `/resume` in Claude Code lands in a different session id. Null means stay
     * where we are.
     */
    fun switched(run: RunContext, current: Path): Path? = null

    /** The native session id, once the transcript has revealed one. Null when [locate] already knew. */
    fun nativeSessionId(): String? = null
}

/**
 * Follows one run's transcript on a daemon thread, appending what it finds to the session log.
 *
 * Polling, at [POLL_MS]. inotify would be tighter but is Linux-only, and a quarter of a second is
 * invisible beside a model's turn — the log is read when a run *ends*, not while it is going.
 *
 * Only whole lines are parsed. A JSONL writer flushes a record at a time but nothing guarantees the
 * read lands on a boundary, so a trailing partial line is left in the buffer for the next pass;
 * without that, every fast turn would drop a record and the handoff would quietly lose a tool call.
 */
class TranscriptFollower(
    private val tailer: Tailer,
    private val run: RunContext,
    private val log: EventLog,
    /** Called with the path and offset the moment a transcript is first found, for `RunStarted`. */
    private val onLocated: (Path, Long) -> Unit = { _, _ -> },
    /**
     * What to do with each event. Defaults to writing it to the log and nothing more; a session
     * overrides it to also watch for the title the CLI gives itself, which is what the tab ends up
     * called. Runs on the tailer thread, so it does as little as the log write does.
     */
    private val onEvent: (AgentEvent) -> Unit = log::append,
) {
    @Volatile
    private var stopped = false
    private var thread: Thread? = null

    private var file: Path? = null
    private var offset: Long = 0
    private var partial: String = ""

    fun start() {
        thread = thread(isDaemon = true, name = "agent-tailer") {
            while (!stopped) {
                runCatching { pass() }.onFailure { Log.warn("agent tailer: $it") }
                // The interrupt is how [stop] wakes this thread, so it is the normal way out and
                // not something to report. Letting it escape logged an uncaught exception every
                // time a run ended.
                try {
                    Thread.sleep(POLL_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    /**
     * Stops following, after one last read.
     *
     * The final drain is the whole reason this isn't just an interrupt: a CLI writes its last
     * records as it exits, and those are exactly the ones a handoff summary needs — the final
     * answer, and the stop reason that says whether it got there.
     */
    fun stop() {
        stopped = true
        runCatching { pass() }
        thread?.interrupt()
        thread = null
    }

    /**
     * Synchronised because [stop]'s final drain runs on whichever thread ended the run while the
     * polling thread may be part-way through a pass of its own. Two passes at once share `offset`,
     * so without this the last records are read twice and the log gains a duplicate of exactly the
     * lines a handoff cares most about.
     */
    @Synchronized
    private fun pass() {
        val current = file ?: tailer.locate(run)?.also { found ->
            file = found
            offset = 0
            onLocated(found, 0)
        } ?: return

        // A `/clear` or `/resume` typed inside the TUI lands in a different transcript. Watching for
        // that is what keeps the log honest through it; without it the session simply stops
        // recording at the moment the user asked for a fresh start.
        tailer.switched(run, current)?.let { next ->
            drain(current)
            file = next
            offset = 0
            partial = ""
            onLocated(next, 0)
            drain(next)
            return
        }
        drain(current)
    }

    private fun drain(path: Path) {
        val size = runCatching { Files.size(path) }.getOrNull() ?: return
        if (size < offset) {
            // The file shrank: it was rewritten rather than appended to, so start over.
            offset = 0
            partial = ""
        }
        if (size == offset) return

        val bytes = runCatching {
            Files.newByteChannel(path).use { channel ->
                channel.position(offset)
                val buffer = java.nio.ByteBuffer.allocate((size - offset).coerceAtMost(MAX_READ).toInt())
                while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
                buffer.flip()
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            }
        }.getOrNull() ?: return
        if (bytes.isEmpty()) return
        offset += bytes.size

        val text = partial + String(bytes, StandardCharsets.UTF_8)
        val complete = text.substringBeforeLast('\n', missingDelimiterValue = "")
        partial = if ('\n' in text) text.substringAfterLast('\n') else text

        complete.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            runCatching { tailer.parse(line) }
                .onFailure { Log.warn("agent tailer could not read a transcript line: $it") }
                .getOrDefault(emptyList())
                .forEach(onEvent)
        }
    }

    private companion object {
        const val POLL_MS = 250L

        /** Cap per pass, so catching up on a long transcript can't allocate it all at once. */
        const val MAX_READ = 4L * 1024 * 1024
    }
}
