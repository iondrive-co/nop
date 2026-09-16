package iondrive.nop.agent

import iondrive.nop.Settings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The collection behind the agent tabs, and the naming that makes a strip of them readable.
 *
 * No process is started here: a session spawns nothing until the panel asks it for a widget, so
 * constructing one is cheap and touches only its event log.
 */
class AgentSessionsTest {
    private val opened = mutableListOf<AgentSessions>()

    @AfterEach
    fun cleanUp() {
        opened.forEach { state ->
            state.sessions.forEach { runCatching { Files.deleteIfExists(it.log.file) } }
            state.disposeAll()
        }
    }

    private fun sessions(): AgentSessions = AgentSessions().also { opened += it }

    private fun account(name: String, provider: Provider = Provider.Anthropic) =
        Account(name, provider, "/homes/$name")

    @Test
    fun `opening a session shows it, and the picker is how you get back`(@TempDir tmp: Path) {
        val state = sessions()

        val session = state.open(tmp.toFile(), account("claude-main"))

        assertEquals(session.sessionId, state.selectedId)
        state.showPicker()
        assertNull(state.selected, "nothing selected is the picker, not an empty panel")
        assertTrue(session in state.sessions, "showing the picker must not close what is running")
    }

    /**
     * The same reason every terminal is called "Term": which account is running answers a question
     * the settings dialog and the picker already answer, and it is not the one a tab label is for.
     */
    @Test
    fun `every tab starts under the same name, the way the terminals do`(@TempDir tmp: Path) {
        val claude = sessions().open(tmp.toFile(), account("claude-main"))
        val codex = sessions().open(tmp.toFile(), account("codex", Provider.OpenAI))

        assertEquals("Agent", claude.title)
        assertEquals("Agent", codex.title)
    }

    @Test
    fun `switching provider does not overwrite a name already earned`(@TempDir tmp: Path) {
        val session = sessions().open(tmp.toFile(), account("claude-main"))
        session.titleFromTranscript("AWS support")

        session.switchTo(account("codex", Provider.OpenAI))

        assertEquals("AWS support", session.title, "a handoff continues the work, so it keeps its name")
    }

    @Test
    fun `renaming a tab sticks`(@TempDir tmp: Path) {
        val state = sessions()
        val session = state.open(tmp.toFile(), account("claude-main"))

        state.rename(session.sessionId, "  parser rewrite  ")

        assertEquals("parser rewrite", session.title, "the name should be trimmed, not padded")
    }

    @Test
    fun `a blank rename is a cancel, not an unlabelled tab`(@TempDir tmp: Path) {
        val state = sessions()
        val session = state.open(tmp.toFile(), account("claude-main"))

        state.rename(session.sessionId, "   ")

        assertEquals("Agent", session.title)
    }

    @Test
    fun `the title the CLI gives the session replaces the default`(@TempDir tmp: Path) {
        val session = sessions().open(tmp.toFile(), account("claude-main"))

        session.titleFromTranscript("AWS support")

        assertEquals("AWS support", session.title, "the work is a better label than a placeholder")
    }

    /**
     * A name the user typed being quietly replaced a minute later, when the CLI decides what the
     * session is about, is worse than no rename at all.
     */
    @Test
    fun `a name the user chose outlives the CLI's own`(@TempDir tmp: Path) {
        val state = sessions()
        val session = state.open(tmp.toFile(), account("claude-main"))

        state.rename(session.sessionId, "parser rewrite")
        session.titleFromTranscript("AWS support")

        assertEquals("parser rewrite", session.title)
    }

    @Test
    fun `renaming a session that isn't there is not an error`(@TempDir tmp: Path) {
        sessions().rename("no-such-session", "whatever")
    }

    @Test
    fun `closing a session drops it and falls back to the picker`(@TempDir tmp: Path) {
        val state = sessions()
        val first = state.open(tmp.toFile(), account("claude-main"))
        val second = state.open(tmp.toFile(), account("codex", Provider.OpenAI))

        state.close(second.sessionId)

        assertEquals(listOf(first.sessionId), state.sessions.map { it.sessionId })
        assertNull(
            state.selectedId,
            "after closing a session the useful next thing is starting another, which is the picker",
        )
    }

    @Test
    fun `each session gets its own id and its own log`(@TempDir tmp: Path) {
        val state = sessions()

        val first = state.open(tmp.toFile(), account("claude-main"))
        val second = state.open(tmp.toFile(), account("claude-main"))

        assertTrue(first.sessionId != second.sessionId)
        assertTrue(first.log.file != second.log.file)
    }

    /**
     * The picker's tab closes the way a terminal's does: it goes out of the strip. There is no
     * process behind it to kill — closing the last terminal leaves the strip with only its "+", and
     * this is the same gesture with the same result.
     */
    @Test
    fun `closing the picker tab takes it out of the strip, and opening one puts it back`(@TempDir tmp: Path) {
        val state = sessions()
        assertTrue(state.pickerTabVisible)

        state.hidePickerTab()
        assertTrue(!state.pickerTabVisible)

        state.showPicker()
        assertTrue(state.pickerTabVisible, "the + has to be able to bring it back")
    }

    @Test
    fun `starting a session brings the picker tab back for next time`(@TempDir tmp: Path) {
        val state = sessions()
        state.hidePickerTab()

        state.open(tmp.toFile(), account("claude-main"))

        assertTrue(
            state.pickerTabVisible,
            "closing it once should not hide it for the rest of the project's life",
        )
    }

    /**
     * nop cannot put a session into the store a bare `claude` reads — the vendor keeps a transcript
     * beside the credentials that wrote it — so it says where it put it instead. One environment
     * variable in front of the ordinary command is the whole difference.
     */
    @Test
    fun `a session can say how to resume it from a shell`(@TempDir tmp: Path) {
        val claude = sessions().open(tmp.toFile(), account("claude-main"))
        val native = claude.run.nativeSessionId
        assertEquals(
            "CLAUDE_CONFIG_DIR=/homes/claude-main claude --resume $native",
            claude.resumeCommand(),
        )

        val codex = sessions().open(tmp.toFile(), account("codex", Provider.OpenAI))
        assertNull(
            codex.resumeCommand(),
            "Codex names its own session, so there is nothing to resume until it has",
        )
    }

    @Test
    fun `a session records where it was started`(@TempDir tmp: Path) {
        val project: File = tmp.toFile()
        val session = sessions().open(project, account("claude-main"))

        val started = session.log.events().filterIsInstance<AgentEvent.SessionStarted>().single()
        assertEquals(project.absolutePath, started.projectPath)
    }

    // Tabs put back from the state file at the next start. Nothing is spawned here either: a restored
    // session is a session, and a session starts no PTY until a panel asks it for a widget.

    @Test
    fun `restored agents come back in order, named as they were left`(@TempDir tmp: Path) {
        val state = sessions()

        state.restore(
            listOf(row(title = "parser rewrite"), row(sessionId = "nop-2", title = "AWS support")),
            tmp.toFile(),
            listOf(account("claude-main")),
        )

        assertEquals(listOf("parser rewrite", "AWS support"), state.sessions.map { it.title })
    }

    /** The log the session already had is the one it carries on writing — see AgentSession.sessionId. */
    @Test
    fun `a restored agent keeps nop's own id, so its history stays one session`(@TempDir tmp: Path) {
        val state = sessions()

        state.restore(listOf(row(sessionId = "nop-1")), tmp.toFile(), listOf(account("claude-main")))

        assertEquals("nop-1", state.sessions.single().sessionId)
    }

    /** Putting the strip back is one claim; deciding what the user wants to look at is another. */
    @Test
    fun `restoring selects nothing`(@TempDir tmp: Path) {
        val state = sessions()
        state.restore(listOf(row()), tmp.toFile(), listOf(account("claude-main")))
        assertNull(state.selected)
    }

    @Test
    fun `a restored agent resumes the conversation it was in`(@TempDir tmp: Path) {
        val state = sessions()

        state.restore(
            listOf(row(nativeSessionId = "11111111-2222-3333-4444-555555555555")),
            tmp.toFile(),
            listOf(account("claude-main")),
        )

        val argv = state.sessions.single().run.command.argv
        assertTrue(
            argv.windowed(2).any { it == listOf("--resume", "11111111-2222-3333-4444-555555555555") },
            "expected a --resume in $argv",
        )
    }

    /**
     * This collection outlives the composition that draws it, so the restore has to be once per nop
     * run rather than once per look at the project.
     */
    @Test
    fun `restoring twice does not double the strip`(@TempDir tmp: Path) {
        val state = sessions()
        val rows = listOf(row())

        state.restore(rows, tmp.toFile(), listOf(account("claude-main")))
        state.restore(rows, tmp.toFile(), listOf(account("claude-main")))

        assertEquals(1, state.sessions.size)
    }

    /** The row names which quota to spend. An account that has since been deleted is not nop's to pick. */
    @Test
    fun `a row whose account is gone is skipped rather than run on another`(@TempDir tmp: Path) {
        val state = sessions()

        state.restore(
            listOf(row(account = "deleted"), row(sessionId = "nop-2", title = "kept")),
            tmp.toFile(),
            listOf(account("claude-main")),
        )

        assertEquals(listOf("kept"), state.sessions.map { it.title })
    }

    /** An account name is not enough on its own: the row has to name the provider it belongs to. */
    @Test
    fun `a row is not restored onto an account of the other provider`(@TempDir tmp: Path) {
        val state = sessions()

        state.restore(
            listOf(row(provider = "openai")),
            tmp.toFile(),
            listOf(account("claude-main")),
        )

        assertTrue(state.sessions.isEmpty())
    }

    // What gets written down. A session describes itself, or declines to.

    @Test
    fun `a session describes itself for the state file`(@TempDir tmp: Path) {
        val state = sessions()
        val session = state.open(tmp.toFile(), account("claude-main"))
        state.rename(session.sessionId, "parser rewrite")

        assertEquals(
            Settings.OpenAgent(
                sessionId = session.sessionId,
                provider = "anthropic",
                account = "claude-main",
                nativeSessionId = session.run.nativeSessionId!!,
                title = "parser rewrite",
                titleIsUsers = true,
            ),
            session.asOpenAgent(),
        )
    }

    /** A name the CLI gave the session may be replaced by a better one after the resume. */
    @Test
    fun `a title the CLI chose is written down as the CLI's`(@TempDir tmp: Path) {
        val session = sessions().open(tmp.toFile(), account("claude-main"))
        session.titleFromTranscript("AWS support")

        val row = session.asOpenAgent()!!
        assertEquals("AWS support", row.title)
        assertFalse(row.titleIsUsers)
    }

    /**
     * The tab stays in the strip after the run ends so its last frame can be read, but starting nop
     * again is not a reason to start that CLI again — the picker is where a deliberate return lives.
     */
    @Test
    fun `a session the user quit is not written down`(@TempDir tmp: Path) {
        val session = sessions().open(tmp.toFile(), account("claude-main"))

        session.endRun(EndReason.Exited)

        assertNull(session.asOpenAgent())
    }

    /** Nothing to resume means nothing to restore: the tab would come back with a blank CLI in it. */
    @Test
    fun `a session with no conversation behind it is not written down`(@TempDir tmp: Path) {
        val session = sessions().open(tmp.toFile(), account("codex", Provider.OpenAI))

        assertNull(session.run.nativeSessionId, "codex names its own session, a moment after it starts")
        assertNull(session.asOpenAgent())
    }

    private fun row(
        sessionId: String = "nop-1",
        provider: String = "anthropic",
        account: String = "claude-main",
        nativeSessionId: String = "vendor-1",
        title: String = "parser rewrite",
        titleIsUsers: Boolean = false,
    ) = Settings.OpenAgent(sessionId, provider, account, nativeSessionId, title, titleIsUsers)
}
