package iondrive.nop.ui

import iondrive.nop.git.ChangeKind
import iondrive.nop.git.CommitFile
import iondrive.nop.git.CommitFileChange
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
    fun `save then restore round-trips a CommitDiff tab opened from history`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val repo = tmp.resolve("repo").toFile().apply { mkdirs() }
        val sha = "b09a25a656445718a494da86beba0f623e78ce56"
        val original = Tab.CommitDiff(
            sha = sha,
            shortSha = sha.take(7),
            file = CommitFile("src/app/admin/api-key/api-key-edit-page.component.ts", CommitFileChange.MODIFIED),
            repoRoot = repo,
        )
        TabsPersistence.save(target, listOf(original), selectedId = original.id)

        val loaded = TabsPersistence.load(target)
        assertEquals(1, loaded.size)
        assertEquals("commitdiff", loaded[0].kind)
        assertEquals(true, loaded[0].selected)

        val state = TabsState()
        TabsPersistence.restore(state, loaded, repoRoot = repo)
        assertEquals(1, state.tabs.size)
        val restored = state.tabs[0] as Tab.CommitDiff
        assertEquals(original, restored)
        assertEquals(original.id, state.selectedId)
    }

    @Test
    fun `a CommitDiff restores even when the file is gone from the working tree`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val repo = tmp.resolve("repo").toFile().apply { mkdirs() }
        // The commit deleted the file, so nothing under repoRoot matches it — the diff is still
        // readable out of history, so the tab must survive.
        val tab = Tab.CommitDiff("abc1234def", "abc1234", CommitFile("gone.ts", CommitFileChange.DELETED), repo)
        TabsPersistence.save(target, listOf(tab), selectedId = null)

        val state = TabsState()
        TabsPersistence.restore(state, TabsPersistence.load(target), repoRoot = repo)
        assertEquals(1, state.tabs.size)
        assertEquals(CommitFileChange.DELETED, (state.tabs[0] as Tab.CommitDiff).file.changeType)
    }

    @Test
    fun `commit diffs need a repoRoot to be restored`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val repo = tmp.resolve("repo").toFile().apply { mkdirs() }
        val tab = Tab.CommitDiff("abc1234def", "abc1234", CommitFile("a.ts", CommitFileChange.ADDED), repo)
        TabsPersistence.save(target, listOf(tab), selectedId = null)

        val state = TabsState()
        TabsPersistence.restore(state, TabsPersistence.load(target), repoRoot = null)
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun `load drops commit-diff lines missing the sha or change type`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        Files.writeString(
            target,
            listOf(
                "commitdiff\ta.ts\t0",                  // no sha, no change type
                "commitdiff\tb.ts\t0\tabc1234def",      // no change type
                "commitdiff\tc.ts\t0\tabc1234def\tNOPE", // change type isn't a CommitFileChange
                "commitdiff\td.ts\t0\tabc1234def\tMODIFIED",
            ).joinToString("\n"),
        )

        // The first two are unparseable, so they never become SavedTabs at all…
        val loaded = TabsPersistence.load(target)
        assertEquals(listOf("c.ts", "d.ts"), loaded.map { it.path })

        // …and the bogus change type is dropped at restore, where the enum is resolved.
        val state = TabsState()
        TabsPersistence.restore(state, loaded, repoRoot = tmp.toFile())
        assertEquals(1, state.tabs.size)
        assertEquals("d.ts", (state.tabs[0] as Tab.CommitDiff).file.path)
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
