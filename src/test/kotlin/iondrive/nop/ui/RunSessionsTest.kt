package iondrive.nop.ui

import iondrive.nop.Settings
import iondrive.nop.launchers.Launcher
import iondrive.nop.terminal.TerminalSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    @Test
    fun `moving a session reorders the sessions list and preserves selection`(@TempDir tmp: Path) {
        val runs = RunSessions()
        val a = runs.open(shell(tmp))
        val b = runs.open(shell(tmp))
        val c = runs.open(shell(tmp))
        runs.select(b.id)

        runs.move(2, 0)
        assertEquals(listOf(c, a, b), runs.sessions)
        assertEquals(b.id, runs.selectedId)

        runs.move(0, 2)
        assertEquals(listOf(a, b, c), runs.sessions)
        assertEquals(b.id, runs.selectedId)

        // Stale or out-of-bounds indices are no-ops
        runs.move(-1, 0)
        assertEquals(listOf(a, b, c), runs.sessions)

        runs.move(1, 10)
        assertEquals(listOf(a, b, c), runs.sessions)

        runs.move(2, 2)
        assertEquals(listOf(a, b, c), runs.sessions)
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

    // Tabs put back from the state file at the next start. A restored session is deferred — it has
    // a command and no process — so these stay as headless as the rest of the file.

    @Test
    fun `restored runs come back in order, named as they were left`(@TempDir tmp: Path) {
        val runs = RunSessions()
        runs.restore(
            listOf(
                Settings.OpenRun("release", "./release.sh", "release"),
                Settings.OpenRun("test", "./gradlew test", "the slow one"),
            ),
            tmp.toFile(),
        )
        assertEquals(listOf("release", "the slow one"), runs.sessions.map { it.title })
    }

    /** Restoring puts the strip back; it does not decide the user was looking at a script. */
    @Test
    fun `restoring selects nothing`(@TempDir tmp: Path) {
        val runs = RunSessions()
        runs.restore(listOf(Settings.OpenRun("dev", "npm run dev", "dev")), tmp.toFile())
        assertNull(runs.selected)
    }

    /** Starting nop is not consent to run a deploy script. The tab comes back; the command waits. */
    @Test
    fun `a restored run has not run anything`(@TempDir tmp: Path) {
        val runs = RunSessions()
        runs.restore(listOf(Settings.OpenRun("dev", "npm run dev", "dev")), tmp.toFile())
        val restored = runs.sessions.single().session
        assertTrue(restored.deferred)
        assertFalse(restored.running)
    }

    @Test
    fun `a row with a blank command is skipped rather than restored as a dead tab`(@TempDir tmp: Path) {
        val runs = RunSessions()
        runs.restore(
            listOf(
                Settings.OpenRun("", "npm run dev", "dev"),
                Settings.OpenRun("build", "  ", "build"),
                Settings.OpenRun("test", "./gradlew test", "test"),
            ),
            tmp.toFile(),
        )
        assertEquals(listOf("test"), runs.sessions.map { it.title })
    }

    /** What gets written back: the launcher behind the tab, plus whatever the tab is called now. */
    @Test
    fun `a launcher run describes itself for the state file`(@TempDir tmp: Path) {
        val runs = RunSessions()
        val launcher = Launcher("release", "./release.sh")
        val run = runs.open(TerminalSession.forLauncher(launcher, tmp.toFile()))
        assertEquals(Settings.OpenRun("release", "./release.sh", "release"), run.asOpenRun())

        runs.rename(run.id, "the one that ships")
        assertEquals(
            Settings.OpenRun("release", "./release.sh", "the one that ships"),
            run.asOpenRun(),
        )
    }

    /** A shell has no command to put back, so it contributes no row. */
    @Test
    fun `a plain terminal describes nothing`(@TempDir tmp: Path) {
        val runs = RunSessions()
        assertNull(runs.open(shell(tmp)).asOpenRun())
    }
}
