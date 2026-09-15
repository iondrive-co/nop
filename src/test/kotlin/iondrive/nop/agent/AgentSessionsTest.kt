package iondrive.nop.agent

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `a session records where it was started`(@TempDir tmp: Path) {
        val project: File = tmp.toFile()
        val session = sessions().open(project, account("claude-main"))

        val started = session.log.events().filterIsInstance<AgentEvent.SessionStarted>().single()
        assertEquals(project.absolutePath, started.projectPath)
    }
}
