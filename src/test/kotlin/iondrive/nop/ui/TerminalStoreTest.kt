package iondrive.nop.ui

import iondrive.nop.terminal.TerminalSession
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * What a project's terminals outlive, and what they don't.
 *
 * Headless-safe for the reason [RunSessionsTest] gives: a session starts no PTY and makes no widget
 * until a panel asks it for one.
 */
class TerminalStoreTest {

    @AfterEach
    fun cleanUp() {
        TerminalStore.disposeAll()
    }

    private fun terminals(dir: Path) = TerminalStore.Terminals(
        shells = RunSessions().apply { openShell(dir.toFile()) },
        runs = RunSessions(),
    )

    private fun of(root: Path, project: Path = root) = TerminalStore.of(root, project) { terminals(root) }

    /**
     * The bug this exists for. Switching to another project and back builds the project's
     * composition again, and it has to find the same shells, not a fresh Term.
     */
    @Test
    fun `asking twice for one project gives the same terminals back`(@TempDir tmp: Path) {
        val project = tmp.resolve("repo")
        val first = of(project)
        val shell = first.shells.sessions.single()

        val second = TerminalStore.of(project, project) { error("must not build a second set") }

        assertSame(first, second)
        assertSame(shell, second.shells.sessions.single())
    }

    @Test
    fun `two projects keep their own terminals`(@TempDir tmp: Path) {
        assertNotSame(of(tmp.resolve("one")), of(tmp.resolve("two")))
    }

    @Test
    fun `a path is normalised before it names anything`(@TempDir tmp: Path) {
        val direct = of(tmp.resolve("repo"))

        assertSame(direct, of(tmp.resolve("repo/module/..")))
    }

    /** A parked window keeps its tabs, so its terminals keep running. */
    @Test
    fun `a project that still has a tab somewhere keeps its terminals`(@TempDir tmp: Path) {
        val project = tmp.resolve("repo")
        val terminals = of(project)

        TerminalStore.retain(listOf(project, tmp.resolve("elsewhere")))

        assertSame(terminals, of(project))
        assertEquals(1, terminals.shells.sessions.size)
    }

    /** Closing the last tab on a project is the user saying they are done with it. */
    @Test
    fun `a project no window has a tab on loses its terminals`(@TempDir tmp: Path) {
        val project = tmp.resolve("repo")
        val terminals = of(project)
        terminals.runs.open(TerminalSession.shell(project.toFile()))

        TerminalStore.retain(listOf(tmp.resolve("elsewhere")))

        assertTrue(terminals.shells.sessions.isEmpty(), "the shells should have been killed")
        assertTrue(terminals.runs.sessions.isEmpty(), "the runs should have been killed")
        assertNotSame(terminals, of(project))
    }

    /**
     * A project opened at a subdirectory is filed under its repo root, while the window list talks
     * about the tab's own path. The two have to be matched up, or the first tick of the window list
     * would kill the terminals of a project that is plainly open.
     */
    @Test
    fun `a project opened below its repo root is kept by its own path`(@TempDir tmp: Path) {
        val root = tmp.resolve("repo")
        val module = tmp.resolve("repo/module")
        val terminals = of(root, module)

        TerminalStore.retain(listOf(module))

        assertSame(terminals, of(root, module))
    }

    @Test
    fun `disposeAll kills every project's terminals`(@TempDir tmp: Path) {
        val one = of(tmp.resolve("one"))
        val two = of(tmp.resolve("two"))

        TerminalStore.disposeAll()

        assertTrue(one.shells.sessions.isEmpty())
        assertTrue(two.shells.sessions.isEmpty())
        assertNotSame(one, of(tmp.resolve("one")))
    }
}
