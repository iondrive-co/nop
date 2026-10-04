package iondrive.nop.agent

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * Messages between agent tabs: which tab a name means, what the recipient reads, and that nothing is
 * typed into a tab that cannot take it. No CLI is started — a session spawns nothing until a panel
 * asks it for a widget — so every tab here is asleep, which is itself one of the cases.
 */
class AgentMessagesTest {
    private val opened = mutableListOf<AgentSessions>()

    @AfterEach
    fun cleanUp() {
        AgentMessages.sessions = { AgentSessionStore.all() }
        opened.forEach { state ->
            state.sessions.forEach { runCatching { Files.deleteIfExists(it.log.file) } }
            state.disposeAll()
        }
    }

    private fun tabs(dir: Path, vararg names: Pair<String, Provider>): List<AgentSession> {
        val state = AgentSessions().also { opened += it }
        val made = names.map { (title, provider) ->
            state.open(dir.toFile(), Account("acct-$title", provider, "/homes/acct-$title")).also { it.rename(title) }
        }
        AgentMessages.sessions = { state.sessions }
        return made
    }

    private fun <T> onEdt(block: () -> T): T {
        var out: Result<T>? = null
        SwingUtilities.invokeAndWait { out = runCatching(block) }
        return out!!.getOrThrow()
    }

    @Test
    fun `a tab is found by the start of its id or by its exact title`(@TempDir dir: Path) {
        val (a, b) = tabs(dir, "Diff Tool Feedback" to Provider.Antigravity, "Kafka shadow" to Provider.Anthropic)
        val all = listOf(a, b)
        assertEquals(AgentMessages.Found.One(a), AgentMessages.resolve(a.shortId, all))
        assertEquals(AgentMessages.Found.One(a), AgentMessages.resolve(a.sessionId.take(5), all))
        assertEquals(AgentMessages.Found.One(b), AgentMessages.resolve("kafka SHADOW", all))
        assertTrue(AgentMessages.resolve("nobody", all) is AgentMessages.Found.Error)
    }

    @Test
    fun `two tabs with one title are not guessed between`(@TempDir dir: Path) {
        val all = tabs(dir, "Agent" to Provider.Anthropic, "Agent" to Provider.OpenAI)
        val found = AgentMessages.resolve("agent", all)
        assertTrue(found is AgentMessages.Found.Error)
        all.forEach { assertTrue(it.shortId in (found as AgentMessages.Found.Error).why, "names ${it.shortId} to pick from") }
    }

    @Test
    fun `the recipient is told who wrote, that it is not the user, and how to answer`(@TempDir dir: Path) {
        val (from) = tabs(dir, "Pipeline review" to Provider.Anthropic)
        val text = AgentMessages.envelope(from, "Stop running nop --help.")
        assertTrue("\"Pipeline review\"" in text)
        assertTrue("nop-msg send ${from.shortId}" in text, "the reply address is the sender's id")
        assertTrue("not from the user" in text)
        assertTrue(text.endsWith("\n\nStop running nop --help."))
    }

    @Test
    fun `a message is held for the user, and nothing is typed until they deliver it`(@TempDir dir: Path) {
        val (from, to) = tabs(dir, "sender" to Provider.Anthropic, "recipient" to Provider.Antigravity)
        val outcome = onEdt { AgentMessages.send(from, to.shortId, "hello") }
        assertFalse(outcome.isError, outcome.text)
        assertTrue(outcome.text.startsWith("Held for the user"), outcome.text)
        val held = to.inbox.single()
        assertEquals("hello", held.body)
        assertFalse(held.approved)

        // Delivered, it still waits while there is nothing running to type into.
        onEdt { AgentMessages.deliver(to, held) }
        assertTrue(held.approved)
        assertTrue(held in to.inbox)
        assertTrue("opened" in AgentMessages.waitReason(to)!!)

        onEdt { AgentMessages.discard(to, held) }
        assertTrue(to.inbox.isEmpty())
    }

    private fun <T> withConfigRoot(root: Path, block: () -> T): T {
        val original = iondrive.nop.Settings.configRoot
        iondrive.nop.Settings.configRoot = root
        AgentMessages.forgetAutoDeliver()
        try {
            return block()
        } finally {
            iondrive.nop.Settings.configRoot = original
            AgentMessages.forgetAutoDeliver()
        }
    }

    @Test
    fun `a project the user opened up delivers between its own tabs without asking`(
        @TempDir project: Path,
        @TempDir elsewhere: Path,
        @TempDir config: Path,
    ) = withConfigRoot(config) {
        val (from, to) = tabs(project, "sender" to Provider.Anthropic, "recipient" to Provider.OpenAI)
        val (stranger) = tabs(elsewhere, "stranger" to Provider.Antigravity)
        AgentMessages.sessions = { listOf(from, to, stranger) }

        val before = onEdt { AgentMessages.send(from, to.shortId, "before") }
        assertTrue(before.text.startsWith("Held for the user"), before.text)

        // Turned on, what the project's tabs already hold from each other goes in too.
        onEdt { AgentMessages.setAutoDeliver(project.toFile(), true) }
        assertTrue(to.inbox.single().approved)
        assertTrue(iondrive.nop.Settings.loadAgentAutoDeliver(project), "the choice is kept")

        val after = onEdt { AgentMessages.send(from, to.shortId, "after") }
        assertTrue(after.text.startsWith("Delivered"), after.text)
        val held = to.inbox.last()
        assertTrue(held.approved)
        assertTrue("without reading each message first" in held.typed)

        // Another project's tab is held all the same.
        val foreign = onEdt { AgentMessages.send(stranger, to.shortId, "from outside") }
        assertTrue(foreign.text.startsWith("Held for the user"), foreign.text)
        assertFalse(to.inbox.last().approved)

        onEdt { AgentMessages.setAutoDeliver(project.toFile(), false) }
        assertFalse(iondrive.nop.Settings.loadAgentAutoDeliver(project))
        assertTrue(onEdt { AgentMessages.send(from, to.shortId, "off again") }.text.startsWith("Held for the user"))
    }

    @Test
    fun `no control byte of a message survives to be typed`() {
        val sneaky = "fine\u001b[201~\rrm -rf ~\u0003\r\n\u202Eevil\u0007 end\tok"
        val cleaned = AgentMessages.clean(sneaky)
        assertFalse(cleaned.any { it.isISOControl() && it != '\n' && it != '\t' }, cleaned)
        assertFalse('\u202E' in cleaned)
        assertFalse("\u001b[201~" in cleaned)
        assertEquals("fine[201~\nrm -rf ~\nevil end\tok", cleaned)
    }

    @Test
    fun `a tab cannot message itself, and an empty message is refused`(@TempDir dir: Path) {
        val (me) = tabs(dir, "me" to Provider.OpenAI)
        assertTrue(onEdt { AgentMessages.send(me, me.shortId, "hi") }.isError)
        assertTrue(onEdt { AgentMessages.send(me, "me", "   ") }.isError)
    }

    @Test
    fun `every run is told how to reach the other tabs`() {
        val text = SharedMemory.instructions()
        assertTrue("nop-msg list" in text && "nop-msg send" in text)
        assertTrue(AgentSocket.helperPath().toString() in text)
    }

    @Test
    fun `a message waits while the recipient has an unsent draft in its prompt`(
        @TempDir project: Path,
        @TempDir config: Path,
    ) = withConfigRoot(config) {
        val (from, to) = tabs(project, "sender" to Provider.Anthropic, "recipient" to Provider.OpenAI)
        AgentMessages.sessions = { listOf(from, to) }
        onEdt { AgentMessages.setAutoDeliver(project.toFile(), true) }

        val term = to.run.session
        term.testStarted = true
        term.running = true
        term.deferred = false

        to.run.startedAt = java.time.Instant.now().minusSeconds(10)

        // User is typing in the recipient tab
        term.draftPending = true
        val now = System.currentTimeMillis()
        assertEquals("the draft in its prompt is sent or cleared", AgentMessages.waitReason(to, now))

        val sent = onEdt { AgentMessages.send(from, to.shortId, "auto message") }
        assertTrue(sent.text.startsWith("Delivered"), sent.text)
        assertTrue("It goes in once the draft in its prompt is sent or cleared." in sent.text)
        assertEquals(1, to.inbox.size)

        // Pump does not submit while draft is pending
        onEdt { AgentMessages.pump(now) }
        assertEquals(1, to.inbox.size, "message remains held in inbox while user is typing")

        // User finishes typing and submits; now within BETWEEN_SUBMITS_MS it waits for prompt to finish sending
        term.draftPending = false
        term.lastSubmitAt = now
        assertEquals("the prompt has finished sending", AgentMessages.waitReason(to, now + 500))
        onEdt { AgentMessages.pump(now + 500) }
        assertEquals(1, to.inbox.size, "message still held while prompt is landing")

        // After BETWEEN_SUBMITS_MS passes, waitReason is clear and pump delivers it
        val later = now + AgentMessages.BETWEEN_SUBMITS_MS + 100
        assertEquals(null, AgentMessages.waitReason(to, later))
        onEdt { AgentMessages.pump(later) }
        assertEquals(0, to.inbox.size, "message delivered once user draft and submit have cleared")
    }
}
