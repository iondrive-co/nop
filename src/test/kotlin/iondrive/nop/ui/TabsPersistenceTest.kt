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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class TabsPersistenceTest {

    /** The snapshot a session that never touched groups produces: everything in the default one. */
    private fun snapshotOf(tabs: List<Tab>, selectedId: String? = null): TabsSnapshot {
        val group = TabGroup(id = 0L, name = TabGroups.DEFAULT_NAME)
        return TabsSnapshot(
            groups = listOf(group),
            tabs = tabs,
            groupOf = tabs.associate { it.id to group.id },
            selectedId = selectedId,
            activeGroupId = group.id,
        )
    }

    /** Loaded rows with the group headers dropped, for the assertions that only care about tabs. */
    private fun tabRows(rows: List<SavedTab>) = rows.filter { it.kind != "group" }

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
        TabsPersistence.save(target, snapshotOf(tabs, selectedId = tabs[1].id))

        val loaded = tabRows(TabsPersistence.load(target))
        assertEquals(2, loaded.size)
        assertEquals("file", loaded[0].kind)
        assertEquals(viewed.absolutePath, loaded[0].path)
        assertEquals(false, loaded[0].selected)
        assertEquals("history", loaded[1].kind)
        assertEquals(true, loaded[1].selected)
    }

    @Test
    fun `save drops Terminal tabs`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val repo = tmp.resolve("repo").toFile().apply { mkdirs() }
        val keep = tmp.resolve("keep.kt").toFile().apply { writeText("") }

        // TerminalSession is lazy — constructing one starts no PTY, so this is safe headless.
        val tabs = listOf<Tab>(
            Tab.FileView(keep),
            Tab.Terminal(TerminalSession.shell(repo)),
        )
        TabsPersistence.save(target, snapshotOf(tabs))

        val loaded = tabRows(TabsPersistence.load(target))
        assertEquals(1, loaded.size)
        assertEquals("file", loaded[0].kind)
        assertEquals(keep.absolutePath, loaded[0].path)
    }

    @Test
    fun `save then restore round-trips a working-tree Diff tab`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val repo = tmp.resolve("repo").toFile().apply { mkdirs() }
        File(repo, "docs").mkdirs()
        File(repo, "docs/guide.md").writeText("hello")
        // The tab a click in the commit panel opens — the kind of tab a docs project is mostly made
        // of, and the one that used to vanish on every project switch.
        val original = Tab.Diff(FileChange("docs/guide.md", ChangeKind.MODIFIED), repo)
        TabsPersistence.save(target, snapshotOf(listOf(original), selectedId = original.id))

        val loaded = tabRows(TabsPersistence.load(target))
        assertEquals(listOf("diff"), loaded.map { it.kind })
        assertEquals(true, loaded[0].selected)

        val state = TabsState()
        TabsPersistence.restore(state, TabsPersistence.load(target), repoRoot = repo)
        assertEquals(listOf<Tab>(original), state.tabs)
        assertEquals(original.id, state.selectedId)
    }

    @Test
    fun `a working-tree diff of a deleted file restores, one of a vanished file does not`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val repo = tmp.resolve("repo").toFile().apply { mkdirs() }
        // REMOVED/MISSING diffs are about the file *not* being there, so absence is no reason to
        // drop them; a MODIFIED diff of a path that has since disappeared has nothing to show.
        val deleted = Tab.Diff(FileChange("gone.md", ChangeKind.MISSING), repo)
        val stale = Tab.Diff(FileChange("also-gone.md", ChangeKind.MODIFIED), repo)
        TabsPersistence.save(target, snapshotOf(listOf(deleted, stale)))

        val state = TabsState()
        TabsPersistence.restore(state, TabsPersistence.load(target), repoRoot = repo)
        assertEquals(listOf<Tab>(deleted), state.tabs)
    }

    @Test
    fun `working-tree diffs need a repoRoot and a change kind`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val repo = tmp.resolve("repo").toFile().apply { mkdirs() }
        File(repo, "a.md").writeText("")
        File(repo, "b.md").writeText("")
        Files.writeString(
            target,
            listOf(
                "diff\ta.md\t0",           // no change kind
                "diff\tb.md\t0\tNOPE",     // not a ChangeKind
                "diff\tb.md\t0\tMODIFIED",
            ).joinToString("\n"),
        )

        // The kindless row never becomes a SavedTab; the bogus one is dropped where the enum resolves.
        assertEquals(listOf("b.md", "b.md"), TabsPersistence.load(target).map { it.path })
        val state = TabsState()
        TabsPersistence.restore(state, TabsPersistence.load(target), repoRoot = repo)
        assertEquals(1, state.tabs.size)

        // Without a repo there is no HEAD side to diff against.
        val noRepo = TabsState()
        TabsPersistence.restore(noRepo, TabsPersistence.load(target), repoRoot = null)
        assertTrue(noRepo.tabs.isEmpty())
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
        TabsPersistence.save(target, snapshotOf(listOf(original), selectedId = original.id))

        val loaded = tabRows(TabsPersistence.load(target))
        assertEquals(1, loaded.size)
        assertEquals("commitdiff", loaded[0].kind)
        assertEquals(true, loaded[0].selected)

        val state = TabsState()
        TabsPersistence.restore(state, TabsPersistence.load(target), repoRoot = repo)
        assertEquals(1, state.tabs.size)
        val restored = state.tabs[0] as Tab.CommitDiff
        assertEquals(original, restored)
        assertEquals(original.id, state.selectedId)
    }

    @Test
    fun `save then restore round-trips local history and local diff tabs`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val file = tmp.resolve("a.kt").toFile().apply { writeText("") }
        val tabs = listOf<Tab>(Tab.LocalHistory(file), Tab.LocalDiff(file, 1_700_000_000_123L))
        TabsPersistence.save(target, snapshotOf(tabs, selectedId = tabs[1].id))

        val loaded = tabRows(TabsPersistence.load(target))
        assertEquals(listOf("localhistory", "localdiff"), loaded.map { it.kind })

        val state = TabsState()
        // No repo: local history is nop's own record, so it restores in a project without git too.
        TabsPersistence.restore(state, TabsPersistence.load(target), repoRoot = null)
        assertEquals(tabs, state.tabs)
        assertEquals(tabs[1].id, state.selectedId)
    }

    @Test
    fun `save then restore round-trips a RevisionDiff tab`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val repo = tmp.resolve("repo").toFile().apply { mkdirs() }
        val file = File(repo, "src/App.kt").apply { parentFile.mkdirs(); writeText("fun main() {}") }
        val sha = "b09a25a656445718a494da86beba0f623e78ce56"
        val original = Tab.RevisionDiff(file, sha, sha.take(7), repo)
        TabsPersistence.save(target, snapshotOf(listOf(original), selectedId = original.id))

        val loaded = tabRows(TabsPersistence.load(target))
        assertEquals(listOf("revisiondiff"), loaded.map { it.kind })
        assertEquals(sha, loaded[0].sha)

        val state = TabsState()
        TabsPersistence.restore(state, TabsPersistence.load(target), repoRoot = repo)
        assertEquals(listOf<Tab>(original), state.tabs)
        assertEquals(original.id, state.selectedId)
    }

    @Test
    fun `a revision diff needs its sha, its repoRoot and a working file`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val repo = tmp.resolve("repo").toFile().apply { mkdirs() }
        val file = File(repo, "App.kt").apply { writeText("") }
        val gone = File(repo, "Gone.kt")
        Files.writeString(
            target,
            listOf(
                // No sha: nothing to read the left-hand side out of.
                "revisiondiff	${file.absolutePath}	0",
                // The right-hand side is the working file, so a vanished one names no diff.
                "revisiondiff	${gone.absolutePath}	0	abc1234def",
                "revisiondiff	${file.absolutePath}	0	abc1234def",
            ).joinToString("\n"),
        )

        // The sha-less row is dropped at load; the other two both parse.
        assertEquals(2, tabRows(TabsPersistence.load(target)).size)

        // Without a repo there's no revision to read, so neither survives restore.
        val noRepo = TabsState()
        TabsPersistence.restore(noRepo, TabsPersistence.load(target), repoRoot = null)
        assertTrue(noRepo.tabs.isEmpty())

        val state = TabsState()
        TabsPersistence.restore(state, TabsPersistence.load(target), repoRoot = repo)
        assertEquals(1, state.tabs.size)
        assertEquals(file, (state.tabs[0] as Tab.RevisionDiff).file)
    }

    @Test
    fun `a local diff row without a timestamp is dropped`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val file = tmp.resolve("a.kt").toFile().apply { writeText("") }
        Files.writeString(target, "localdiff\t${file.absolutePath}\t1\nfile\t${file.absolutePath}\t0")

        // The timestamp is the revision's whole identity; without one the row names nothing.
        val loaded = tabRows(TabsPersistence.load(target))
        assertEquals(listOf("file"), loaded.map { it.kind })
    }

    @Test
    fun `a CommitDiff restores even when the file is gone from the working tree`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val repo = tmp.resolve("repo").toFile().apply { mkdirs() }
        // The commit deleted the file, so nothing under repoRoot matches it — the diff is still
        // readable out of history, so the tab must survive.
        val tab = Tab.CommitDiff("abc1234def", "abc1234", CommitFile("gone.ts", CommitFileChange.DELETED), repo)
        TabsPersistence.save(target, snapshotOf(listOf(tab)))

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
        TabsPersistence.save(target, snapshotOf(listOf(tab)))

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

        TabsPersistence.save(target, snapshotOf(listOf(Tab.FileView(alive), Tab.FileView(gone))))
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
            snapshotOf(listOf(Tab.FileView(a), Tab.FileView(b)), selectedId = Tab.FileView(b).id),
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
    fun `save with no tabs still records the groups, so empty ones survive a restart`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        TabsPersistence.save(target, snapshotOf(emptyList()))
        assertTrue(Files.isRegularFile(target))

        val loaded = TabsPersistence.load(target)
        assertEquals(listOf("group"), loaded.map { it.kind })
        assertTrue(tabRows(loaded).isEmpty())

        val state = TabsState()
        TabsPersistence.restore(state, loaded, repoRoot = null)
        assertTrue(state.tabs.isEmpty())
        assertEquals(listOf(TabGroups.DEFAULT_NAME), state.groups.map { it.name })
    }

    @Test
    fun `history tabs need a repoRoot to be restored`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val file = tmp.resolve("x.kt").toFile().apply { writeText("") }
        val repo = tmp.resolve("repo").toFile().apply { mkdirs() }
        TabsPersistence.save(target, snapshotOf(listOf(Tab.History(file, repo))))

        // Without a repoRoot, the History tab can't be reconstructed; it should be silently dropped.
        val stateNoRoot = TabsState()
        TabsPersistence.restore(stateNoRoot, TabsPersistence.load(target), repoRoot = null)
        assertTrue(stateNoRoot.tabs.isEmpty())

        val stateWithRoot = TabsState()
        TabsPersistence.restore(stateWithRoot, TabsPersistence.load(target), repoRoot = repo)
        assertEquals(1, stateWithRoot.tabs.size)
    }

    @Test
    fun `save then restore round-trips the whole grouped strip`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val a = tmp.resolve("a.kt").toFile().apply { writeText("") }
        val b = tmp.resolve("b.kt").toFile().apply { writeText("") }
        val c = tmp.resolve("c.kt").toFile().apply { writeText("") }

        val saved = TabsState()
        saved.open(Tab.FileView(a))
        val second = saved.addGroup()
        saved.renameGroup(saved.groups[0].id, "Review")
        saved.open(Tab.FileView(b))
        saved.open(Tab.FileView(c))
        saved.select(Tab.FileView(b).id)
        saved.setCollapsed(saved.groups[0].id, collapsed = true)
        TabsPersistence.save(target, saved.snapshot())

        val restored = TabsState()
        TabsPersistence.restore(restored, TabsPersistence.load(target), repoRoot = null)

        assertEquals(listOf("Review", "MR2"), restored.groups.map { it.name })
        assertEquals(listOf(true, false), restored.groups.map { it.collapsed })
        assertEquals(listOf(a.absolutePath), restored.tabsIn(restored.groups[0].id).map { (it as Tab.FileView).file.absolutePath })
        assertEquals(
            listOf(b.absolutePath, c.absolutePath),
            restored.tabsIn(restored.groups[1].id).map { (it as Tab.FileView).file.absolutePath },
        )
        // The armed group and the selected tab both come back.
        assertEquals(restored.groups[1].id, restored.activeGroupId)
        assertEquals(Tab.FileView(b).id, restored.selectedId)
        assertEquals(second.name, restored.groups[1].name)
    }

    @Test
    fun `a file written before groups existed restores into the default group`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val a = tmp.resolve("a.kt").toFile().apply { writeText("") }
        val b = tmp.resolve("b.kt").toFile().apply { writeText("") }
        Files.writeString(
            target,
            listOf("file\t${a.absolutePath}\t0", "file\t${b.absolutePath}\t1").joinToString("\n"),
        )

        val state = TabsState()
        TabsPersistence.restore(state, TabsPersistence.load(target), repoRoot = null)

        assertEquals(listOf(TabGroups.DEFAULT_NAME), state.groups.map { it.name })
        assertEquals(2, state.tabsIn(state.groups[0].id).size)
        assertEquals(Tab.FileView(b).id, state.selectedId)
    }

    @Test
    fun `a group name carrying tabs or newlines is flattened so the row survives`(@TempDir tmp: Path) {
        val target = tmp.resolve("tabs.tsv")
        val state = TabsState()
        state.renameGroup(state.groups[0].id, "one\ttwo\nthree")
        TabsPersistence.save(target, state.snapshot())

        val restored = TabsState()
        TabsPersistence.restore(restored, TabsPersistence.load(target), repoRoot = null)
        assertEquals(listOf("one two three"), restored.groups.map { it.name })
    }
}
