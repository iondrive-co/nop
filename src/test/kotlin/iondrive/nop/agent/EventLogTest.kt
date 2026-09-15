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
import java.nio.file.StandardOpenOption

class EventLogTest {
    private val opened = mutableListOf<EventLog>()

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

    private fun session(id: String, project: String, title: String?, firstPrompt: String?): EventLog {
        val written = log(id)
        written.append(AgentEvent.SessionStarted(project, now()))
        written.append(
            AgentEvent.RunStarted(
                provider = "anthropic", account = "work", nativeSessionId = "n-$id", at = now(),
            ),
        )
        firstPrompt?.let { written.append(AgentEvent.UserMessage(it, now())) }
        title?.let { written.append(AgentEvent.SessionTitled(it, now())) }
        return written
    }

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
     * After a switch the run at the *top* of the log is the one that ran out. A row built from it
     * would offer to reopen the account that already failed — which is the one account it is known
     * not to be worth reopening.
     */
    @Test
    fun `the listing names the account that ran last, not the one that started`(@TempDir tmp: Path) {
        val written = session("handed-over", tmp.toString(), "Handed over", null)
        written.append(AgentEvent.ProviderSwitched("anthropic", "openai", EndReason.Quota, "/h.md", now()))
        written.append(
            AgentEvent.RunStarted(provider = "openai", account = "codex", nativeSessionId = "roll-2", at = now()),
        )

        val listed = EventLog.sessions(tmp).single { it.sessionId == "handed-over" }

        assertEquals("codex", listed.lastAccount)
        assertEquals("roll-2", listed.lastNativeSessionId)
    }

    /** And it must find that last run however far down the file it is. */
    @Test
    fun `a long session's last run is still found`(@TempDir tmp: Path) {
        val written = session("long", tmp.toString(), "Long session", null)
        repeat(500) { written.append(AgentEvent.UserMessage("turn $it", now())) }
        written.append(
            AgentEvent.RunStarted(provider = "openai", account = "codex", nativeSessionId = "roll-3", at = now()),
        )
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
