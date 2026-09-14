package iondrive.nop.ui

import iondrive.nop.terminal.TerminalSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The live-terminal lists: the Run tool tab's launcher runs, and the shells behind the terminal
 * tabs at the head of the tool strip. Every test here builds real [TerminalSession]s, which is safe
 * headless: a session is lazy — it starts no PTY and creates no Swing widget until the panel asks
 * for one — so nothing below spawns a process.
 */
class RunSessionsTest {

    private fun shell(tmp: Path) = TerminalSession.shell(tmp.toFile())

    private companion object {
        /** What every terminal tab is called before the user renames it — the strip adds the ⌨. */
        const val TERM = "Term"
    }

    @Test
    fun `starts empty with nothing selected`() {
        val runs = RunSessions()
        assertEquals(emptyList<RunSession>(), runs.sessions)
        assertNull(runs.selected)
    }

    @Test
    fun `opening a run selects it`(@TempDir tmp: Path) {
        val runs = RunSessions()
        val first = runs.open(shell(tmp))
        assertEquals(listOf(first), runs.sessions)
        assertEquals(first.id, runs.selected?.id)

        val second = runs.open(shell(tmp))
        assertEquals(listOf(first, second), runs.sessions)
        assertEquals(second.id, runs.selected?.id, "the newest run is the one the user wants to watch")
    }

    /**
     * Re-running the same launcher must not collapse onto the run already going — the point of a
     * second run is to have both.
     */
    @Test
    fun `two runs of the same command are distinct`(@TempDir tmp: Path) {
        val runs = RunSessions()
        val first = runs.open(shell(tmp))
        val second = runs.open(shell(tmp))
        assertNotEquals(first.id, second.id)
        assertEquals(2, runs.sessions.size)
    }

    @Test
    fun `closing the selected run falls through to the one that slid into its place`(@TempDir tmp: Path) {
        val runs = RunSessions()
        val first = runs.open(shell(tmp))
        val second = runs.open(shell(tmp))
        val third = runs.open(shell(tmp))

        runs.select(second.id)
        runs.close(second.id)
        assertEquals(listOf(first, third), runs.sessions)
        assertEquals(third.id, runs.selected?.id)
    }

    /** Nothing to the right, so the selection falls back to the neighbour on the left. */
    @Test
    fun `closing the last run selects its left neighbour`(@TempDir tmp: Path) {
        val runs = RunSessions()
        val first = runs.open(shell(tmp))
        val second = runs.open(shell(tmp))

        runs.close(second.id)
        assertEquals(first.id, runs.selected?.id)
    }

    @Test
    fun `closing a run that isn't selected leaves the selection alone`(@TempDir tmp: Path) {
        val runs = RunSessions()
        val first = runs.open(shell(tmp))
        val second = runs.open(shell(tmp))

        runs.close(first.id)
        assertEquals(listOf(second), runs.sessions)
        assertEquals(second.id, runs.selected?.id)
    }

    @Test
    fun `closing the only run empties the panel`(@TempDir tmp: Path) {
        val runs = RunSessions()
        val only = runs.open(shell(tmp))
        runs.close(only.id)
        assertEquals(emptyList<RunSession>(), runs.sessions)
        assertNull(runs.selected)
    }

    @Test
    fun `closing and selecting ignore ids that aren't open`(@TempDir tmp: Path) {
        val runs = RunSessions()
        val only = runs.open(shell(tmp))

        runs.close("run:nope:1")
        runs.select("run:nope:1")
        assertEquals(listOf(only), runs.sessions)
        assertEquals(only.id, runs.selected?.id)
    }

    /**
     * What a project-tab switch does. Everything has to go: a run left behind would keep its PTY —
     * and whatever ports its children hold — with nothing on screen able to reach it.
     */
    @Test
    fun `disposeAll clears every run`(@TempDir tmp: Path) {
        val runs = RunSessions()
        runs.open(shell(tmp))
        runs.open(shell(tmp))

        runs.disposeAll()
        assertEquals(emptyList<RunSession>(), runs.sessions)
        assertNull(runs.selected)
    }

    /** Every terminal tab opens under the same name; the user renames the ones worth naming. */
    @Test
    fun `shells all open under the same name`(@TempDir tmp: Path) {
        val runs = RunSessions()
        val first = runs.openShell(tmp.toFile())
        val second = runs.openShell(tmp.toFile())

        assertEquals(TERM, first.title)
        assertEquals(TERM, second.title)
        assertNotEquals(first.id, second.id, "same name, still two terminals")
    }

    /**
     * The regression that killed the numbering this replaced: a count of everything ever opened
     * only goes up, so closing terminals used to leave a strip holding one tab labelled "Term 4".
     */
    @Test
    fun `a shell opened after others are closed is named like any other`(@TempDir tmp: Path) {
        val runs = RunSessions()
        runs.openShell(tmp.toFile())
        val second = runs.openShell(tmp.toFile())
        runs.close(second.id)

        assertEquals(TERM, runs.openShell(tmp.toFile()).title)
    }

    /** A shell opened from the strip's "+" is selected, like anything else opened here. */
    @Test
    fun `opening a shell shows it`(@TempDir tmp: Path) {
        val runs = RunSessions()
        runs.openShell(tmp.toFile())
        val second = runs.openShell(tmp.toFile())
        assertEquals(second.id, runs.selected?.id)
    }

    /** What the tab's right-click rename writes. */
    @Test
    fun `renaming a terminal retitles its tab`(@TempDir tmp: Path) {
        val runs = RunSessions()
        val first = runs.openShell(tmp.toFile())
        val second = runs.openShell(tmp.toFile())

        runs.rename(second.id, "server")
        assertEquals("server", second.title)
        assertEquals(TERM, first.title, "renaming one tab leaves its neighbours alone")
    }

    /** Surrounding space is the user's typing, not part of the name they meant. */
    @Test
    fun `a renamed terminal is trimmed`(@TempDir tmp: Path) {
        val runs = RunSessions()
        val run = runs.openShell(tmp.toFile())
        runs.rename(run.id, "  logs \t")
        assertEquals("logs", run.title)
    }

    /**
     * A tab with no label is one the user can barely click, so an empty name is read as "changed my
     * mind" rather than applied.
     */
    @Test
    fun `renaming to blank keeps the old name`(@TempDir tmp: Path) {
        val runs = RunSessions()
        val run = runs.openShell(tmp.toFile())
        runs.rename(run.id, "   ")
        assertEquals(TERM, run.title)
    }

    @Test
    fun `renaming an id that isn't open does nothing`(@TempDir tmp: Path) {
        val runs = RunSessions()
        val run = runs.openShell(tmp.toFile())
        runs.rename("run:nope:1", "server")
        assertEquals(TERM, run.title)
    }

    @Test
    fun `a run is titled after the session behind it`(@TempDir tmp: Path) {
        val runs = RunSessions()
        val run = runs.open(shell(tmp))
        assertEquals(run.session.title, run.title)
    }
}
