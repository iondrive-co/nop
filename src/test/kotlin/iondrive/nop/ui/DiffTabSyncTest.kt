package iondrive.nop.ui

import iondrive.nop.git.ChangeKind
import iondrive.nop.git.CommitFile
import iondrive.nop.git.CommitFileChange
import iondrive.nop.git.FileChange
import iondrive.nop.git.GitStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DiffTabSyncTest {
    private val root = File("/repo")

    private fun diffTab(path: String, kind: ChangeKind = ChangeKind.MODIFIED) =
        Tab.Diff(FileChange(path, kind), root)

    private fun status(vararg changes: Pair<String, ChangeKind>) =
        GitStatus(branch = "main", changes = changes.map { FileChange(it.first, it.second) })

    @Test
    fun `a change git no longer has closes its diff`() {
        val gone = diffTab("a.txt")
        val kept = diffTab("b.txt")

        val sync = syncDiffTabs(listOf(gone, kept), status("b.txt" to ChangeKind.MODIFIED), headMoved = false)

        assertEquals(listOf(gone), sync.close, "a.txt was committed away — nothing left to diff")
        assertTrue(sync.reload.isEmpty(), "b.txt is unchanged against the same HEAD: no re-read needed")
    }

    @Test
    fun `every diff survives a status that still lists it`() {
        val tabs = listOf(diffTab("a.txt"), diffTab("b.txt", ChangeKind.UNTRACKED))

        val sync = syncDiffTabs(
            tabs,
            status("a.txt" to ChangeKind.MODIFIED, "b.txt" to ChangeKind.UNTRACKED),
            headMoved = false,
        )

        assertTrue(sync.close.isEmpty())
        assertTrue(sync.reload.isEmpty())
    }

    @Test
    fun `a moved HEAD re-reads the diffs that are still open`() {
        val tabs = listOf(diffTab("a.txt"), diffTab("b.txt"))

        val sync = syncDiffTabs(
            tabs,
            status("a.txt" to ChangeKind.MODIFIED, "b.txt" to ChangeKind.MODIFIED),
            headMoved = true,
        )

        assertEquals(tabs, sync.reload, "a merge moved the left-hand side of both diffs")
        assertTrue(sync.close.isEmpty())
    }

    @Test
    fun `a change that switched kind is re-read with the kind git reports now`() {
        val tab = diffTab("a.txt", ChangeKind.UNTRACKED)

        val sync = syncDiffTabs(listOf(tab), status("a.txt" to ChangeKind.ADDED), headMoved = false)

        assertEquals(listOf(Tab.Diff(FileChange("a.txt", ChangeKind.ADDED), root)), sync.reload)
        assertEquals(ChangeKind.ADDED, (sync.reload.single()).change.kind)
        assertTrue(sync.close.isEmpty())
    }

    @Test
    fun `unsaved work keeps a vanished diff open`() {
        val dirty = diffTab("a.txt")
        val clean = diffTab("b.txt")

        val sync = syncDiffTabs(listOf(dirty, clean), GitStatus.EMPTY, headMoved = false) { it == dirty }

        assertEquals(listOf(clean), sync.close, "only the tab with nothing unsaved goes")
    }

    @Test
    fun `tabs that aren't working-tree diffs are left alone`() {
        val tabs = listOf(
            Tab.FileView(File("/repo/a.txt")),
            Tab.CommitDiff("abc123", "abc123", CommitFile("a.txt", CommitFileChange.MODIFIED), root),
            Tab.RevisionDiff(File("/repo/a.txt"), "abc123", "abc123", root),
            Tab.LocalDiff(File("/repo/a.txt"), 1_000L),
            Tab.LocalHistory(File("/repo/a.txt")),
        )

        val sync = syncDiffTabs(tabs, GitStatus.EMPTY, headMoved = true)

        assertTrue(sync.close.isEmpty(), "a commit's diff is of fixed revisions — status says nothing about it")
        assertTrue(sync.reload.isEmpty())
    }
}
