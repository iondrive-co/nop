package iondrive.nop.agent

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.nio.file.StandardOpenOption

class EventLogTest {
    private val opened = mutableListOf<EventLog>()

    /** Where [transcript] puts the files a resumable row has to point at. */
    @TempDir
    lateinit var transcripts: Path

    @BeforeEach
    fun start() {
        opened.clear()
    }

    @AfterEach
    fun cleanUp() {
        opened.forEach { log ->
            log.close()
            runCatching { Files.deleteIfExists(log.file) }
        }
    }

    private fun log(id: String = "test-${System.nanoTime()}"): EventLog =
        EventLog.open(id).also { opened += it }

    private fun now() = System.currentTimeMillis()

    @Test
    fun `every event kind survives a round trip through the file`() {
        val events = listOf(
            AgentEvent.SessionStarted("/project", now()),
            AgentEvent.RunStarted(
                provider = "anthropic", account = "work", model = "claude-opus-5", reasoning = "high",
                nativeSessionId = "sid", transcriptPath = "/t.jsonl", transcriptOffset = 12,
                argv = listOf("claude", "--resume", "sid"), seededFromHandoff = true, at = now(),
            ),
            AgentEvent.UserMessage("do the thing", now()),
            AgentEvent.AssistantMessage(
                blocks = listOf(
                    Block.Text("on it"),
                    Block.Thinking("first check the config"),
                    Block.ToolCall("t1", "Read", mapOf("file_path" to "/a.kt")),
                ),
                usage = TokenUsage(inputTokens = 1, outputTokens = 2, thinkingTokens = 3),
                stopReason = "tool_use", model = "claude-opus-5", at = now(),
            ),
            AgentEvent.ToolStarted("t1", "Read", mapOf("file_path" to "/a.kt"), now()),
            AgentEvent.ToolFinished("t1", "contents", isError = false, exitCode = 0, durationMs = 5, at = now()),
            AgentEvent.ScreenTail("drawing a frame", now()),
            AgentEvent.SessionTitled("Doing the thing", now()),
            AgentEvent.RunEnded(0, EndReason.Quota, now()),
            AgentEvent.ProviderSwitched("anthropic", "openai", EndReason.Quota, "/handoff.md", now()),
        )
        val written = log()

        events.forEach(written::append)

        assertEquals(events, written.events())
    }

    @Test
    fun `a line that can't be read costs that line, not the session's whole record`() {
        val written = log()
        written.append(AgentEvent.UserMessage("first", now()))
        written.close()
        Files.writeString(
            written.file,
            Files.readString(written.file) + """{"type":"from-a-future-version"}""" + "\n",
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        val read = EventLog.read(written.file)

        assertEquals(1, read.size)
        assertEquals("first", (read.single() as AgentEvent.UserMessage).text)
    }

    /**
     * Screen output is the degraded path: something to build a handoff out of when a provider's
     * transcript can't be read. The switch is one-way, because a tailer that is working produces a
     * far better record and a log full of terminal redraws beside it is only noise.
     */
    @Test
    fun `screen output is logged until the tailer produces something, then never again`() {
        val written = log()
        written.append(AgentEvent.RunStarted(provider = "openai", account = "a", at = now()))

        written.appendScreenTail("drawing a frame")
        written.append(AgentEvent.UserMessage("a real event", now()))
        written.appendScreenTail("drawing another frame")

        val tails = written.events().filterIsInstance<AgentEvent.ScreenTail>()
        assertEquals(listOf("drawing a frame"), tails.map { it.text })
    }

    @Test
    fun `a fresh run starts the fallback again`() {
        val written = log()
        written.append(AgentEvent.UserMessage("from the first run", now()))
        written.appendScreenTail("ignored")

        written.beginRun()
        written.appendScreenTail("the new run has no transcript yet")

        assertEquals(
            listOf("the new run has no transcript yet"),
            written.events().filterIsInstance<AgentEvent.ScreenTail>().map { it.text },
        )
    }

    @Test
    fun `blank screen output is not logged`() {
        val written = log()
        written.appendScreenTail("   \n  ")

        assertTrue(written.events().isEmpty())
    }

    // -- Listing a project's history --

    /**
     * A session as a real log records one: the spawn, and then the second [AgentEvent.RunStarted]
     * a tailer writes once it has found the transcript on disk.
     *
     * [located] is what tells the two apart. A session the CLI never filed a transcript for — an
     * agent tab opened and closed without a word typed into it — only ever gets the first record,
     * and the id in it names a conversation that does not exist.
     */
    private fun session(
        id: String,
        project: String,
        title: String?,
        firstPrompt: String?,
        located: Boolean = true,
    ): EventLog {
        val written = log(id)
        written.append(AgentEvent.SessionStarted(project, now()))
        written.append(
            AgentEvent.RunStarted(
                provider = "anthropic", account = "work", home = "/homes/work",
                nativeSessionId = "n-$id", at = now(),
            ),
        )
        if (located) written.append(run("n-$id"))
        firstPrompt?.let { written.append(AgentEvent.UserMessage(it, now())) }
        title?.let { written.append(AgentEvent.SessionTitled(it, now())) }
        return written
    }

    /** The record a tailer writes when it finds a run's transcript, with the file there to find. */
    private fun run(
        nativeSessionId: String,
        provider: String = "anthropic",
        account: String = "work",
        home: String? = "/homes/work",
        transcript: Path = transcript(nativeSessionId),
    ): AgentEvent.RunStarted = AgentEvent.RunStarted(
        provider = provider, account = account, home = home,
        nativeSessionId = nativeSessionId, transcriptPath = transcript.toString(), at = now(),
    )

    /**
     * The same, filed where Claude files one: `<configDir>/projects/<slug>/<id>.jsonl`.
     *
     * [began] is when the conversation in it started. The record carrying it is the second line,
     * because the real thing opens with a bookkeeping record that has no time on it at all.
     */
    private fun claudeTranscript(store: Path, id: String, began: Long? = null): Path {
        val dir = store.resolve("projects").resolve("-home-someone-a-project")
        Files.createDirectories(dir)
        val body = began?.let {
            """{"type":"last-prompt","sessionId":"$id"}""" + "\n" +
                """{"type":"user","timestamp":"${Instant.ofEpochMilli(it)}"}""" + "\n"
        }.orEmpty()
        return dir.resolve("$id.jsonl").also { Files.writeString(it, body) }
    }

    /** Stands in for the vendor's own transcript — the file a resume needs to still be there. */
    private fun transcript(id: String): Path =
        transcripts.resolve("$id.jsonl").also { Files.writeString(it, "") }

    @Test
    fun `a project's sessions are listed newest first, and another project's are not`(@TempDir tmp: Path) {
        session("ours-old", tmp.toString(), "Older", null)
        Thread.sleep(5)
        session("ours-new", tmp.toString(), "Newer", null)
        session("theirs", "/somewhere/else", "Not ours", null)

        val listed = EventLog.sessions(tmp).filter { it.sessionId.startsWith("ours") }

        assertEquals(listOf("Newer", "Older"), listed.map { it.title })
        assertTrue(EventLog.sessions(tmp).none { it.title == "Not ours" })
    }

    @Test
    fun `a session with no title of its own is labelled by the first thing the user typed`(@TempDir tmp: Path) {
        session("by-prompt", tmp.toString(), null, "Make the provisioning script work for AWS\nand test it")

        val listed = EventLog.sessions(tmp).single { it.sessionId == "by-prompt" }

        assertEquals("Make the provisioning script work for AWS", listed.title)
    }

    @Test
    fun `a session that produced nothing at all still has a label`(@TempDir tmp: Path) {
        session("silent", tmp.toString(), null, null)

        assertEquals("Untitled session", EventLog.sessions(tmp).single { it.sessionId == "silent" }.title)
    }

    @Test
    fun `the listing carries what reopening a session natively needs`(@TempDir tmp: Path) {
        session("resumable", tmp.toString(), "Some work", null)

        val listed = EventLog.sessions(tmp).single { it.sessionId == "resumable" }

        assertEquals("anthropic", listed.lastProvider)
        assertEquals("work", listed.lastAccount)
        assertEquals("n-resumable", listed.lastNativeSessionId)
    }

    /**
     * nop chooses Claude's session id before the CLI starts, so the id is in the log from the spawn
     * while the conversation it names is not. Offer one of these and the CLI answers the click with
     * "No conversation found with session ID" — a row that promised a way back into work nobody did.
     */
    @Test
    fun `a session whose CLI never filed a transcript has nothing to resume`(@TempDir tmp: Path) {
        session("never-started", tmp.toString(), null, null, located = false)

        val listed = EventLog.sessions(tmp).single { it.sessionId == "never-started" }

        assertNull(listed.lastNativeSessionId, "the id names no conversation on disk")
        assertNull(listed.accountIn(listOf(configured)), "and so the row cannot be reopened")
        assertEquals("work", listed.lastAccount, "it is still listed, and still says what ran it")
    }

    /** The same the other way round: the id was good, and the store it pointed into is gone. */
    @Test
    fun `a session whose transcript has since been deleted has nothing to resume either`(@TempDir tmp: Path) {
        session("deleted", tmp.toString(), "Work that was thrown away", null)
        Files.delete(transcripts.resolve("n-deleted.jsonl"))

        assertNull(EventLog.sessions(tmp).single { it.sessionId == "deleted" }.lastNativeSessionId)
    }

    /**
     * The store a run was launched against, which is the thing a resume actually needs: a session
     * run under a label no configured account answers to — "outside nop", the one nop coins for
     * the directory a plain `claude` uses — has nothing to look up and everything to launch.
     */
    @Test
    fun `a session run under a store rather than an account is resumed against that store`(@TempDir tmp: Path) {
        val written = session("from-a-shell", tmp.toString(), "Done outside nop", null)
        written.append(run("n-from-a-shell", account = "outside nop", home = "/home/someone/.claude"))

        val listed = EventLog.sessions(tmp).single { it.sessionId == "from-a-shell" }
        val account = listed.accountIn(listOf(configured))

        assertEquals("/home/someone/.claude", listed.home)
        assertEquals("/home/someone/.claude", account?.home, "the directory is what the CLI is pointed at")
        assertEquals("outside nop", account?.name)
        assertEquals(Provider.Anthropic, account?.provider)
    }

    /**
     * A run's id can move off the one nop asked for two ways, and they are not the same thing. A
     * `/clear` starts a conversation inside the run; a tailer adopting a `claude` somebody had
     * running in the same checkout walks into one that was already going. The second is what put a
     * stranger's session behind this row's title — see ClaudeTailer.switched, which no longer makes
     * that mistake but cannot unwrite the logs that recorded it.
     */
    @Test
    fun `a run that walked into a conversation already going resumes the one nop asked for`(@TempDir tmp: Path) {
        val store = transcripts.resolve("walked-in")
        val spawnedAt = now()
        claudeTranscript(store, "ours", began = spawnedAt - 3 * 86_400_000L)
        val theirs = claudeTranscript(store, "theirs", began = spawnedAt - 86_400_000L)
        val written = log("adopted")
        written.append(AgentEvent.SessionStarted(tmp.toString(), spawnedAt))
        written.append(
            AgentEvent.RunStarted(
                provider = "anthropic", account = "outside nop", nativeSessionId = "ours",
                argv = listOf("claude", "--resume", "ours"), at = spawnedAt,
            ),
        )
        written.append(
            AgentEvent.RunStarted(
                provider = "anthropic", account = "outside nop", nativeSessionId = "theirs",
                transcriptPath = theirs.toString(), argv = listOf("claude", "--resume", "ours"),
                at = spawnedAt + 30_000,
            ),
        )
        written.append(AgentEvent.SessionTitled("The work we asked for", now()))

        val listed = EventLog.sessions(tmp).single { it.sessionId == "adopted" }

        assertEquals("The work we asked for", listed.title)
        assertEquals("ours", listed.lastNativeSessionId, "what nop pointed the run at, not what it wandered into")
        assertEquals(store.toString(), listed.home, "and still the store both of them are filed in")
    }

    /** The other way round: a conversation that began inside the run is this run's, so follow it. */
    @Test
    fun `a clear typed mid-run is followed, because that conversation began inside the run`(@TempDir tmp: Path) {
        val store = transcripts.resolve("cleared")
        val spawnedAt = now()
        val fresh = claudeTranscript(store, "after-the-clear", began = spawnedAt + 20_000)
        val written = log("clear")
        written.append(AgentEvent.SessionStarted(tmp.toString(), spawnedAt))
        written.append(
            AgentEvent.RunStarted(
                provider = "anthropic", account = "work", nativeSessionId = "before-the-clear",
                argv = listOf("claude", "--session-id", "before-the-clear"), at = spawnedAt,
            ),
        )
        written.append(
            AgentEvent.RunStarted(
                provider = "anthropic", account = "work", nativeSessionId = "after-the-clear",
                transcriptPath = fresh.toString(),
                argv = listOf("claude", "--session-id", "before-the-clear"), at = spawnedAt + 25_000,
            ),
        )
        written.append(AgentEvent.SessionTitled("Started over", now()))

        val listed = EventLog.sessions(tmp).single { it.sessionId == "clear" }

        assertEquals("after-the-clear", listed.lastNativeSessionId)
    }

    /**
     * Recording the store from now on only helps sessions that have not happened yet. Every log
     * already on disk names its account and nothing else, so the store label ones — which is every
     * session resumed out of the default directory — would stay unreachable for good. The path the
     * tailer wrote down is where that directory still is.
     */
    @Test
    fun `a log written before the store was recorded finds it under the transcript`(@TempDir tmp: Path) {
        val store = transcripts.resolve("dot-claude")
        val written = session("old-log", tmp.toString(), "Done outside nop", null)
        written.append(
            AgentEvent.RunStarted(
                provider = "anthropic", account = "outside nop", home = null,
                nativeSessionId = "n-old-log", transcriptPath = claudeTranscript(store, "n-old-log").toString(),
                at = now(),
            ),
        )

        val listed = EventLog.sessions(tmp).single { it.sessionId == "old-log" }

        assertEquals(store.toString(), listed.home, "three directories up from the transcript")
        assertEquals(store.toString(), listed.accountIn(listOf(configured))?.home)
        assertEquals("n-old-log", listed.lastNativeSessionId)
    }

    /**
     * And only for a transcript filed the way Claude files one. Codex nests its rollouts by date,
     * so the same arithmetic lands on a directory that is not a home — and pointing a CLI at the
     * wrong credentials is a worse answer than a row that admits it does not know.
     */
    @Test
    fun `a rollout filed some other way is not read as a store`(@TempDir tmp: Path) {
        val rollout = transcripts.resolve("codex").resolve("sessions").resolve("2026-09-17")
        Files.createDirectories(rollout)
        val file = rollout.resolve("roll-x.jsonl").also { Files.writeString(it, "") }
        val written = session("codex-old", tmp.toString(), "Codex work", null)
        written.append(
            AgentEvent.RunStarted(
                provider = "openai", account = "an account since deleted", home = null,
                nativeSessionId = "roll-x", transcriptPath = file.toString(), at = now(),
            ),
        )

        val listed = EventLog.sessions(tmp).single { it.sessionId == "codex-old" }

        assertEquals("roll-x", listed.lastNativeSessionId, "the rollout is there")
        assertNull(listed.home, "but nothing about it says which home it belongs to")
        assertNull(listed.accountIn(listOf(configured)))
    }

    /** With no directory written down — a log older than the field — there is nothing to place it. */
    @Test
    fun `a session under an unknown account and no store cannot be reopened`(@TempDir tmp: Path) {
        val written = session("orphan", tmp.toString(), "Whose was this", null)
        written.append(
            AgentEvent.RunStarted(
                provider = "anthropic", account = "deleted-account", home = null,
                nativeSessionId = "n-orphan", transcriptPath = transcripts.resolve("n-orphan.jsonl").toString(),
                at = now(),
            ),
        )

        val listed = EventLog.sessions(tmp).single { it.sessionId == "orphan" }

        assertEquals("n-orphan", listed.lastNativeSessionId, "the conversation is there")
        assertNull(listed.accountIn(listOf(configured)), "but nothing nop may spend to reach it")
    }

    /**
     * Both are the same credentials, and only the configured account carries the model and the
     * reasoning level the user chose for it.
     */
    @Test
    fun `a configured account wins over the directory the run recorded`(@TempDir tmp: Path) {
        session("ours", tmp.toString(), "Our own work", null)
        val listed = EventLog.sessions(tmp).single { it.sessionId == "ours" }

        val account = listed.accountIn(listOf(configured))

        assertEquals(configured, account, "including the model and reasoning the row never knew about")
    }

    /** An account as the settings hold one, for the rows above to resolve against. */
    private val configured = Account(
        name = "work", provider = Provider.Anthropic, home = "/homes/work",
        model = "claude-opus-5", reasoning = "high",
    )

    /**
     * After a switch the run at the *top* of the log is the one that ran out. A row built from it
     * would offer to reopen the account that already failed — which is the one account it is known
     * not to be worth reopening.
     */
    @Test
    fun `the listing names the account that ran last, not the one that started`(@TempDir tmp: Path) {
        val written = session("handed-over", tmp.toString(), "Handed over", null)
        written.append(AgentEvent.ProviderSwitched("anthropic", "openai", EndReason.Quota, "/h.md", now()))
        written.append(run("roll-2", provider = "openai", account = "codex", home = "/homes/codex"))

        val listed = EventLog.sessions(tmp).single { it.sessionId == "handed-over" }

        assertEquals("codex", listed.lastAccount)
        assertEquals("roll-2", listed.lastNativeSessionId)
    }

    /** And it must find that last run however far down the file it is. */
    @Test
    fun `a long session's last run is still found`(@TempDir tmp: Path) {
        val written = session("long", tmp.toString(), "Long session", null)
        repeat(500) { written.append(AgentEvent.UserMessage("turn $it", now())) }
        written.append(run("roll-3", provider = "openai", account = "codex", home = "/homes/codex"))
        repeat(500) { written.append(AgentEvent.UserMessage("later turn $it", now())) }

        val listed = EventLog.sessions(tmp).single { it.sessionId == "long" }

        assertEquals("roll-3", listed.lastNativeSessionId)
        assertEquals("Long session", listed.title, "the label still comes from the head")
    }

    /**
     * Reopening resumes the *last* run, which after a provider switch is not the one at the head of
     * the file. The listing reads only the head, so this reads the whole log — once, when the user
     * actually clicks, rather than once per session every time the picker is drawn.
     */
    @Test
    fun `the last run is what a reopen resumes, even after a switch`(@TempDir tmp: Path) {
        val written = session("switched", tmp.toString(), "Switched work", null)
        written.append(AgentEvent.ProviderSwitched("anthropic", "openai", EndReason.Quota, "/h.md", now()))
        written.append(
            AgentEvent.RunStarted(provider = "openai", account = "codex", nativeSessionId = "roll-9", at = now()),
        )

        val last = EventLog.lastRun("switched")

        assertEquals("openai", last?.provider)
        assertEquals("roll-9", last?.nativeSessionId)
    }

    @Test
    fun `a log that is not a session of any project is not listed`(@TempDir tmp: Path) {
        val stray = log("stray")
        stray.append(AgentEvent.UserMessage("no session_started before me", now()))

        assertTrue(EventLog.sessions(tmp).none { it.sessionId == "stray" })
    }

    @Test
    fun `asking for the last run of a session that never existed is not an error`() {
        assertNull(EventLog.lastRun("no-such-session"))
    }

    @Test
    fun `a closed log stops writing rather than throwing`() {
        val written = log()
        written.append(AgentEvent.UserMessage("before", now()))
        written.close()

        written.append(AgentEvent.UserMessage("after", now()))

        assertEquals(1, EventLog.read(written.file).size)
        assertFalse("after" in Files.readString(written.file))
    }
}
