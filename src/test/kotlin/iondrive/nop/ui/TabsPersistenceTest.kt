package iondrive.nop.ui

import iondrive.nop.git.ChangeKind
import iondrive.nop.git.FileChange
import iondrive.nop.terminal.TerminalSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TabsPersistenceTest {

    @Test
    fun `save then load round-trips a FileView and a History tab`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val repo = tmp.resolve("repo").toFile().apply { mkdirs() }
        val viewed = tmp.resolve("a.kt").toFile().apply { writeText("") }
        val historyTarget = tmp.resolve("b.kt").toFile().apply { writeText("") }

        val tabs = listOf<Tab>(
            Tab.FileView(viewed),
            Tab.History(historyTarget, repo),
        )
        TabsPersistence.save(target, tabs, selectedId = tabs[1].id)

        val loaded = TabsPersistence.load(target)
        assertEquals(2, loaded.size)
        assertEquals("file", loaded[0].kind)
        assertEquals(viewed.absolutePath, loaded[0].path)
        assertEquals(false, loaded[0].selected)
        assertEquals("history", loaded[1].kind)
        assertEquals(true, loaded[1].selected)
    }

    @Test
    fun `save drops Diff and Terminal tabs`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val repo = tmp.resolve("repo").toFile().apply { mkdirs() }
        val keep = tmp.resolve("keep.kt").toFile().apply { writeText("") }

        // TerminalSession is lazy — constructing one starts no PTY, so this is safe headless.
        val tabs = listOf<Tab>(
            Tab.FileView(keep),
            Tab.Diff(FileChange("foo.kt", ChangeKind.MODIFIED), repo),
            Tab.Terminal(TerminalSession.shell(repo)),
        )
        TabsPersistence.save(target, tabs, selectedId = null)

        val loaded = TabsPersistence.load(target)
        assertEquals(1, loaded.size)
        assertEquals("file", loaded[0].kind)
        assertEquals(keep.absolutePath, loaded[0].path)
    }

    @Test
    fun `load skips entries whose file is gone`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val alive = tmp.resolve("alive.kt").toFile().apply { writeText("") }
        val gone = tmp.resolve("gone.kt").toFile().apply { writeText("") }

        TabsPersistence.save(
            target,
            tabs = listOf(Tab.FileView(alive), Tab.FileView(gone)),
            selectedId = null,
        )
        gone.delete()

        val state = TabsState()
        val saved = TabsPersistence.load(target)
        TabsPersistence.restore(state, saved, repoRoot = null)
        assertEquals(1, state.tabs.size)
        assertEquals(alive.absolutePath, (state.tabs[0] as Tab.FileView).file.absolutePath)
    }

    @Test
    fun `restore re-selects the previously-selected tab`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val a = tmp.resolve("a.kt").toFile().apply { writeText("") }
        val b = tmp.resolve("b.kt").toFile().apply { writeText("") }
        TabsPersistence.save(
            target,
            tabs = listOf(Tab.FileView(a), Tab.FileView(b)),
            selectedId = Tab.FileView(b).id,
        )

        val state = TabsState()
        TabsPersistence.restore(state, TabsPersistence.load(target), repoRoot = null)
        assertEquals(Tab.FileView(b).id, state.selectedId)
    }

    @Test
    fun `load returns empty when the file is missing`(@TempDir tmp: Path) {
        assertTrue(TabsPersistence.load(tmp.resolve("does-not-exist")).isEmpty())
    }

    @Test
    fun `save with an empty list writes an empty file that loads cleanly`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        TabsPersistence.save(target, emptyList(), selectedId = null)
        assertTrue(Files.isRegularFile(target))
        assertTrue(TabsPersistence.load(target).isEmpty())
    }

    @Test
    fun `history tabs need a repoRoot to be restored`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val file = tmp.resolve("x.kt").toFile().apply { writeText("") }
        val repo = tmp.resolve("repo").toFile().apply { mkdirs() }
        TabsPersistence.save(
            target,
            tabs = listOf(Tab.History(file, repo)),
            selectedId = null,
        )

        // Without a repoRoot, the History tab can't be reconstructed; it should be silently dropped.
        val stateNoRoot = TabsState()
        TabsPersistence.restore(stateNoRoot, TabsPersistence.load(target), repoRoot = null)
        assertTrue(stateNoRoot.tabs.isEmpty())

        val stateWithRoot = TabsState()
        TabsPersistence.restore(stateWithRoot, TabsPersistence.load(target), repoRoot = repo)
        assertEquals(1, stateWithRoot.tabs.size)
    }
}
