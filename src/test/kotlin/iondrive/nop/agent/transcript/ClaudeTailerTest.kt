package iondrive.nop.agent.transcript

import iondrive.nop.agent.AgentEvent
import iondrive.nop.agent.Block
import iondrive.nop.agent.EventLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.time.Duration

/**
 * The Claude transcript reader, against an anonymised slice of a real session.
 *
 * The fixture is a real transcript with its prose replaced: the record shapes, key names, block
 * types, usage fields and `toolUseResult` structure are exactly what the CLI wrote. Those are the
 * part that breaks when a version changes, and a hand-written fixture would only ever prove that
 * the parser agrees with whoever wrote the fixture.
 */
class ClaudeTailerTest {

    private fun fixture(): List<String> =
        checkNotNull(javaClass.getResourceAsStream("/agent/claude-session.jsonl"))
            .bufferedReader().readLines().filter { it.isNotBlank() }

    private fun parseAll(tailer: ClaudeTailer = ClaudeTailer(Path.of("/nowhere"))): List<AgentEvent> =
        fixture().flatMap { tailer.parse(it) }

    @Test
    fun `the slug is the working directory with its slashes and dots flattened`() {
        assertEquals("-home-dev-nop", ClaudeTailer.slug(Path.of("/home/dev/nop")))
        assertEquals(
            "-home-dev-nop--chad-worktrees-d680e65f",
            ClaudeTailer.slug(Path.of("/home/dev/nop/.chad-worktrees/d680e65f")),
        )
    }

    @Test
    fun `a typed prompt becomes a user message`() {
        val first = parseAll().filterIsInstance<AgentEvent.UserMessage>().first()

        assertEquals("Add AWS support to the provisioning script", first.text)
    }

    @Test
    fun `an assistant turn carries its blocks, usage, stop reason and model`() {
        val turn = parseAll().filterIsInstance<AgentEvent.AssistantMessage>().first()

        assertEquals("claude-opus-5", turn.model)
        assertNotNull(turn.stopReason, "stop_reason is how a finished turn is told from a cut-off one")
        val usage = turn.usage
        assertNotNull(usage, "the transcript carries a usage block on every assistant record")
        assertTrue(
            listOfNotNull(usage!!.inputTokens, usage.outputTokens, usage.cacheReadTokens).isNotEmpty(),
            "the transcript carries real token counts; none of them reached the event",
        )
        assertTrue(turn.blocks.isNotEmpty())
    }

    @Test
    fun `thinking is kept, because the provider it is handed to may want it`() {
        val thinking = parseAll()
            .filterIsInstance<AgentEvent.AssistantMessage>()
            .flatMap { it.blocks }
            .filterIsInstance<Block.Thinking>()

        assertTrue(thinking.isNotEmpty(), "no thinking block survived the parse")
        assertTrue(thinking.first().text.isNotBlank())
    }

    @Test
    fun `every tool call is started once, and a result only ever answers a call that was made`() {
        val events = parseAll()
        val started = events.filterIsInstance<AgentEvent.ToolStarted>()
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>()

        assertTrue(started.isNotEmpty(), "the fixture has tool_use blocks in it")
        assertEquals(started.size, started.map { it.callId }.distinct().size, "a call was started twice")
        val callIds = started.map { it.callId }.toSet()
        assertTrue(
            finished.all { it.callId in callIds },
            "a result arrived for a call that was never started: ${finished.map { it.callId } - callIds}",
        )
    }

    @Test
    fun `a tool result carries a duration measured against the call that started it`() {
        val finished = parseAll().filterIsInstance<AgentEvent.ToolFinished>()

        assertTrue(
            finished.any { (it.durationMs ?: -1) >= 0 },
            "no result was paired back to its call, so nothing knows how long anything took",
        )
    }

    @Test
    fun `a tool call reaching an event brings the arguments that say what it did`() {
        val started = parseAll().filterIsInstance<AgentEvent.ToolStarted>()

        assertTrue(
            started.any { Normalize.describe(it.tool, it.args).isNotBlank() },
            "every call came through with nothing to say about it: ${started.map { it.tool to it.args }}",
        )
    }

    @Test
    fun `a title the CLI gave the session becomes an event`() {
        val titles = parseAll().filterIsInstance<AgentEvent.SessionTitled>()

        assertTrue(titles.isNotEmpty())
        assertEquals("AWS support", titles.first().title)
    }

    @Test
    fun `the CLI's own bookkeeping records produce nothing`() {
        val noise = listOf(
            """{"type":"attachment","attachment":{"type":"file","path":"/project/x"}}""",
            """{"type":"queue-operation","operation":"add","content":"later"}""",
            """{"type":"last-prompt","lastPrompt":"Add AWS support"}""",
            """{"type":"file-history-snapshot","snapshot":{}}""",
            """{"type":"mode","mode":"default"}""",
        )
        val tailer = ClaudeTailer(Path.of("/nowhere"))

        assertTrue(
            noise.flatMap { tailer.parse(it) }.isEmpty(),
            "the next provider does not need to be told about its predecessor's input buffer",
        )
    }

    @Test
    fun `a line that isn't JSON is skipped rather than ending the session's record`() {
        val tailer = ClaudeTailer(Path.of("/nowhere"))

        assertTrue(tailer.parse("{half a line").isEmpty())
        assertTrue(tailer.parse("").isEmpty())
    }

    @Test
    fun `a tool result reports the exit code the CLI recorded for it`() {
        val tailer = ClaudeTailer(Path.of("/nowhere"))
        tailer.parse(
            """{"type":"assistant","timestamp":"2026-09-15T00:00:00.000Z","message":{"role":"assistant",""" +
                """"content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"make test"}}]}}""",
        )

        val finished = tailer.parse(
            """{"type":"user","timestamp":"2026-09-15T00:00:01.000Z","toolUseResult":{"stdout":"boom","exitCode":2},""" +
                """"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"boom"}]}}""",
        ).filterIsInstance<AgentEvent.ToolFinished>().single()

        assertEquals(2, finished.exitCode, "a failing command must not read like a passing one")
        assertEquals(1000, finished.durationMs)
        assertFalse(finished.summary.isBlank())
    }

    // ── Locating and following ──

    private fun slugDir(config: Path, project: Path): Path =
        config.resolve("projects").resolve(ClaudeTailer.slug(project))
            .also { Files.createDirectories(it) }

    @Test
    fun `the transcript is found by the id nop minted, not by guessing`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val config = tmp.resolve("config")
        val dir = slugDir(config, project)
        Files.writeString(dir.resolve("decoy.jsonl"), "{}\n")
        val wanted = dir.resolve("the-id.jsonl")
        Files.writeString(wanted, "{}\n")

        val run = RunContext(project, config, "the-id", System.currentTimeMillis())

        assertEquals(wanted, ClaudeTailer(config).locate(run))
    }

    @Test
    fun `nothing is located until the CLI has actually written the file`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val config = tmp.resolve("config")
        slugDir(config, project)

        val run = RunContext(project, config, "not-yet", System.currentTimeMillis())

        assertNull(ClaudeTailer(config).locate(run))
    }

    /**
     * `/clear` and `/resume` typed inside the TUI land in a different session id, so the file being
     * followed stops growing and a new one appears beside it. Without this the session simply stops
     * recording at the moment the user asked for a fresh start — and a handoff built afterwards
     * would carry only the half they had already abandoned.
     */
    @Test
    fun `a second transcript appearing mid-run is followed`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val config = tmp.resolve("config")
        val dir = slugDir(config, project)
        val startedAt = System.currentTimeMillis()
        val first = dir.resolve("first.jsonl")
        Files.writeString(first, "{}\n")
        Files.setLastModifiedTime(first, FileTime.fromMillis(startedAt))

        val tailer = ClaudeTailer(config)
        val run = RunContext(project, config, "first", startedAt)
        assertEquals(first, tailer.locate(run))
        assertNull(tailer.switched(run, first), "nothing new yet, so stay where we are")

        val second = dir.resolve("second.jsonl")
        Files.writeString(second, "{}\n")
        Files.setLastModifiedTime(second, FileTime.fromMillis(startedAt + 5_000))

        assertEquals(second, tailer.switched(run, first))
        assertEquals("second", tailer.nativeSessionId(), "the new id is what a native reopen resumes")
    }

    /**
     * The other thing that puts a newer transcript in this directory: a second agent tab on the same
     * project. It looks identical to a `/clear` on disk — a file that appeared after this run
     * started and is being written to — and following it made the older tab log the newer session's
     * turns and, because a tab is named from the transcript it follows, wear its title too. Several
     * tabs open meant every one of them ending up with the newest one's name.
     */
    @Test
    fun `a transcript another run is following is not mistaken for a fresh start`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val config = tmp.resolve("config")
        val dir = slugDir(config, project)
        val startedAt = System.currentTimeMillis()
        val mine = dir.resolve("mine.jsonl")
        Files.writeString(mine, "{}\n")
        Files.setLastModifiedTime(mine, FileTime.fromMillis(startedAt))

        // The tab next door, opened a moment later on the same project.
        val neighbour = dir.resolve("neighbour.jsonl")
        Files.writeString(neighbour, "{}\n")
        Files.setLastModifiedTime(neighbour, FileTime.fromMillis(startedAt + 5_000))

        val tailer = ClaudeTailer(config)
        val run = RunContext(project, config, "mine", startedAt, foreign = { it == "neighbour" })
        assertEquals(mine, tailer.locate(run))

        assertNull(tailer.switched(run, mine), "that file belongs to another tab, not to a /clear")
        assertEquals("mine", tailer.nativeSessionId(), "and this run is still its own session")

        // A session nop is *not* following is still a fresh start, which is the case above.
        val cleared = dir.resolve("cleared.jsonl")
        Files.writeString(cleared, "{}\n")
        Files.setLastModifiedTime(cleared, FileTime.fromMillis(startedAt + 9_000))
        assertEquals(cleared, tailer.switched(run, mine))
    }

    /**
     * The file that is neither a `/clear` nor another nop tab: a `claude` somebody started from a
     * shell in the same checkout. [RunContext.foreign] cannot see it, because it only knows the
     * sessions nop is running — so this run walked into a conversation that was already going,
     * logged its turns, took its name, and left the picker offering to resume a stranger's session
     * under this one's title.
     */
    @Test
    fun `a session already under way when this run started is not adopted`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val config = tmp.resolve("config")
        val dir = slugDir(config, project)
        val startedAt = System.currentTimeMillis()
        val mine = dir.resolve("mine.jsonl")
        Files.writeString(mine, "{}\n")
        Files.setLastModifiedTime(mine, FileTime.fromMillis(startedAt))
        // Theirs, open in a terminal since yesterday and nothing to do with this tab.
        val theirs = dir.resolve("theirs.jsonl")
        Files.writeString(theirs, "{}\n")
        Files.setLastModifiedTime(theirs, FileTime.fromMillis(startedAt - 86_400_000))

        val tailer = ClaudeTailer(config)
        val run = RunContext(project, config, "mine", startedAt)
        assertEquals(mine, tailer.locate(run))

        // They type something, and their transcript becomes the newest file in the project.
        Files.setLastModifiedTime(theirs, FileTime.fromMillis(startedAt + 5_000))

        assertNull(tailer.switched(run, mine), "that conversation was here before this run was")
        assertEquals("mine", tailer.nativeSessionId(), "so this run is still its own session")
    }

    @Test
    fun `a transcript left over from before this run is not adopted`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val config = tmp.resolve("config")
        val dir = slugDir(config, project)
        val old = dir.resolve("yesterday.jsonl")
        Files.writeString(old, "{}\n")
        Files.setLastModifiedTime(old, FileTime.fromMillis(1_000))

        // No id: this is the path a provider that names its own session would take.
        val run = RunContext(project, config, null, System.currentTimeMillis())

        assertNull(ClaudeTailer(config).locate(run))
    }

    /**
     * The follower reads bytes, not records, so a read can land mid-line — which happens constantly,
     * because a JSONL writer flushes whenever it likes. A partial line has to be held over, or every
     * fast turn silently drops a record and the handoff loses a tool call.
     */
    @Test
    fun `a record split across two reads is parsed once, whole`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val config = tmp.resolve("config")
        val dir = slugDir(config, project)
        val file = dir.resolve("split.jsonl")
        val record = """{"type":"user","timestamp":"2026-09-15T00:00:00.000Z","message":{"role":"user","content":"hello there"}}"""
        Files.writeString(file, record.substring(0, 40))

        withLog("split-test") { log ->
            val follower = TranscriptFollower(
                ClaudeTailer(config),
                RunContext(project, config, "split", System.currentTimeMillis()),
                log,
            )
            follower.start()
            Files.writeString(file, record.substring(40) + "\n", StandardOpenOption.APPEND)

            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                while (log.events().filterIsInstance<AgentEvent.UserMessage>().isEmpty()) Thread.sleep(50)
            }
            follower.stop()

            val messages = log.events().filterIsInstance<AgentEvent.UserMessage>()
            assertEquals(1, messages.size, "the record was parsed more than once")
            assertEquals("hello there", messages.single().text)
        }
    }

    @Test
    fun `stopping drains what the CLI wrote on its way out`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val config = tmp.resolve("config")
        val dir = slugDir(config, project)
        val file = dir.resolve("drain.jsonl")
        Files.writeString(file, "")

        withLog("drain-test") { log ->
            val follower = TranscriptFollower(
                ClaudeTailer(config),
                RunContext(project, config, "drain", System.currentTimeMillis()),
                log,
            )
            follower.start()
            // Written in the instant before the stop, the way a CLI writes its last answer as it
            // exits — precisely the part a handoff summary is built out of.
            Files.writeString(
                file,
                """{"type":"user","timestamp":"2026-09-15T00:00:00.000Z","message":{"role":"user","content":"last words"}}""" + "\n",
            )
            follower.stop()

            assertEquals(
                listOf("last words"),
                log.events().filterIsInstance<AgentEvent.UserMessage>().map { it.text },
            )
        }
    }

    /**
     * The bug that made a restart look like lost work.
     *
     * A tab put back from the state file resumes a conversation whose transcript is already hours
     * long, and every line of it is already in the session's own event log. Reading it again filed
     * a second copy of the lot — and Claude re-states its title on most records, so the last one
     * through renamed the tab to whatever the CLI had last called it. A session saved as "Plan 40
     * completion check" came back up called "Handoff from Claude Code", which is not a name its
     * owner was looking for.
     */
    @Test
    fun `a resumed run joins its transcript at the end instead of replaying it`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val config = tmp.resolve("config")
        val dir = slugDir(config, project)
        val file = dir.resolve("resumed.jsonl")
        Files.writeString(
            file,
            """{"type":"user","timestamp":"2026-09-15T00:00:00.000Z","message":{"role":"user","content":"an hour of history"}}""" + "\n" +
                """{"type":"ai-title","aiTitle":"Handoff from Claude Code"}""" + "\n",
        )

        withLog("resumed") { log ->
            // The offset is chosen when the transcript is found, so the line standing in for what
            // the user types after the restart is only written once it has been.
            val located = java.util.concurrent.CountDownLatch(1)
            val follower = TranscriptFollower(
                ClaudeTailer(config),
                RunContext(
                    project, config, "resumed", System.currentTimeMillis(),
                    resumingLoggedWork = true,
                ),
                log,
                onLocated = { _, _ -> located.countDown() },
            )
            follower.start()
            assertTrue(located.await(5, java.util.concurrent.TimeUnit.SECONDS), "transcript never found")
            Files.writeString(
                file,
                """{"type":"user","timestamp":"2026-09-15T01:00:00.000Z","message":{"role":"user","content":"what I typed after the restart"}}""" + "\n",
                StandardOpenOption.APPEND,
            )

            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                while (log.events().filterIsInstance<AgentEvent.UserMessage>().isEmpty()) Thread.sleep(50)
            }
            follower.stop()

            assertEquals(
                listOf("what I typed after the restart"),
                log.events().filterIsInstance<AgentEvent.UserMessage>().map { it.text },
                "the history was already logged once; reading it again files it twice",
            )
            assertTrue(
                log.events().filterIsInstance<AgentEvent.SessionTitled>().isEmpty(),
                "a title out of the history would rename the restored tab out from under the user",
            )
        }
    }

    /**
     * The other half of the same switch. A session nop has never followed — a vendor conversation
     * reopened from the picker — has an empty log, and there the history is the whole point.
     */
    @Test
    fun `a run nop has not logged before still reads the transcript it is given`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").also { Files.createDirectories(it) }
        val config = tmp.resolve("config")
        val dir = slugDir(config, project)
        Files.writeString(
            dir.resolve("fresh.jsonl"),
            """{"type":"user","timestamp":"2026-09-15T00:00:00.000Z","message":{"role":"user","content":"history worth having"}}""" + "\n",
        )

        withLog("fresh") { log ->
            val follower = TranscriptFollower(
                ClaudeTailer(config),
                RunContext(project, config, "fresh", System.currentTimeMillis()),
                log,
            )
            follower.start()

            assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                while (log.events().filterIsInstance<AgentEvent.UserMessage>().isEmpty()) Thread.sleep(50)
            }
            follower.stop()

            assertEquals(
                listOf("history worth having"),
                log.events().filterIsInstance<AgentEvent.UserMessage>().map { it.text },
            )
        }
    }

    /** Opens a log, runs [body], and takes the file away again. */
    private fun withLog(id: String, body: (EventLog) -> Unit) {
        val log = EventLog.open("test-$id-${System.nanoTime()}")
        try {
            body(log)
        } finally {
            log.close()
            Files.deleteIfExists(log.file)
        }
    }
}
