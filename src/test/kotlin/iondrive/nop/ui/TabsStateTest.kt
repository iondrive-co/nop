package iondrive.nop.ui

import iondrive.nop.git.ChangeKind
import iondrive.nop.git.CommitFile
import iondrive.nop.git.CommitFileChange
import iondrive.nop.git.FileChange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class TabsStateTest {
    private fun fileTab(path: String) = Tab.FileView(File(path))

    @Test
    fun `open adds tab and selects it`() {
        val s = TabsState()
        val t = fileTab("/x/a.txt")

        s.open(t)

        assertEquals(listOf(t), s.tabs)
        assertEquals(t.id, s.selectedId)
        assertEquals(t, s.selectedTab)
    }

    @Test
    fun `opening same id twice doesn't duplicate`() {
        val s = TabsState()
        s.open(fileTab("/x/a.txt"))
        s.open(fileTab("/x/a.txt"))

        assertEquals(1, s.tabs.size)
    }

    @Test
    fun `close removes tab and picks neighbour`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        val b = fileTab("/x/b.txt")
        val c = fileTab("/x/c.txt")
        s.open(a); s.open(b); s.open(c)
        // c is selected; close b -> c still selected
        s.close(b.id)
        assertEquals(c.id, s.selectedId)
        assertEquals(listOf(a, c), s.tabs)

        // close selected -> picks the one that took its place, or previous
        s.close(c.id)
        assertEquals(a.id, s.selectedId)
    }

    @Test
    fun `close last tab leaves nothing selected`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        s.open(a)
        s.close(a.id)
        assertEquals(emptyList<Tab>(), s.tabs)
        assertNull(s.selectedId)
    }

    @Test
    fun `closeOthers keeps only the given tab and selects it`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        val b = fileTab("/x/b.txt")
        val c = fileTab("/x/c.txt")
        s.open(a); s.open(b); s.open(c)

        val removed = s.closeOthers(b.id)

        assertEquals(listOf(b), s.tabs)
        assertEquals(b.id, s.selectedId)
        assertEquals(listOf(a, c), removed)
    }

    @Test
    fun `closeOthers on a single tab is a no-op`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        s.open(a)

        val removed = s.closeOthers(a.id)

        assertEquals(listOf(a), s.tabs)
        assertEquals(a.id, s.selectedId)
        assertEquals(emptyList<Tab>(), removed)
    }

    @Test
    fun `closeOthers with an unknown id leaves tabs untouched`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        val b = fileTab("/x/b.txt")
        s.open(a); s.open(b)

        val removed = s.closeOthers("nope")

        assertEquals(listOf(a, b), s.tabs)
        assertEquals(b.id, s.selectedId)
        assertEquals(emptyList<Tab>(), removed)
    }

    @Test
    fun `onFileOpened fires for file tabs opened as a user action`() {
        val s = TabsState()
        val opened = mutableListOf<File>()
        s.onFileOpened = { opened += it }

        s.open(fileTab("/x/a.txt"))
        s.openAt(fileTab("/x/b.txt"), 5)

        assertEquals(listOf(File("/x/a.txt"), File("/x/b.txt")), opened)
    }

    @Test
    fun `onFileOpened does not fire for restore opens or non-file tabs`() {
        val s = TabsState()
        val opened = mutableListOf<File>()
        s.onFileOpened = { opened += it }

        s.open(fileTab("/x/a.txt"), record = false)
        s.open(Tab.History(File("/x"), File("/x")))

        assertEquals(emptyList<File>(), opened)
    }

    @Test
    fun `openAt with a query queues it until consumed`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")

        s.openAt(a, 12, searchQuery = "needle")

        assertEquals(12, s.pendingJumpLine(a.id))
        assertEquals("needle", s.pendingSearchQuery(a.id))

        s.clearSearchQuery(a.id)
        assertNull(s.pendingSearchQuery(a.id))
    }

    @Test
    fun `openAt without a query leaves no pending search`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")

        s.openAt(a, 3)

        assertNull(s.pendingSearchQuery(a.id))
    }

    @Test
    fun `openAt with an empty query clears any previous pending search`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")

        s.openAt(a, 1, searchQuery = "old")
        s.openAt(a, 2, searchQuery = "")

        assertNull(s.pendingSearchQuery(a.id))
    }

    @Test
    fun `close discards a pending search`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        s.openAt(a, 1, searchQuery = "gone")

        s.close(a.id)

        assertNull(s.pendingSearchQuery(a.id))
    }

    @Test
    fun `closeOthers discards pending searches on the closed tabs`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        val b = fileTab("/x/b.txt")
        s.openAt(a, 1, searchQuery = "a-hit")
        s.openAt(b, 2, searchQuery = "b-hit")

        s.closeOthers(b.id)

        assertNull(s.pendingSearchQuery(a.id))
        assertEquals("b-hit", s.pendingSearchQuery(b.id))
    }

    @Test
    fun `reopening an open tab asks it to reload`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")

        s.open(a)
        assertEquals(0, s.reloadKey(a.id))

        s.open(a)
        assertEquals(1, s.reloadKey(a.id))

        s.open(a)
        assertEquals(2, s.reloadKey(a.id))
    }

    @Test
    fun `opening a different tab doesn't reload the others`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        val b = fileTab("/x/b.txt")

        s.open(a)
        s.open(b)

        assertEquals(0, s.reloadKey(a.id))
        assertEquals(0, s.reloadKey(b.id))
    }

    @Test
    fun `requestReload only counts for tabs that are open`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")

        s.requestReload(a.id)
        assertEquals(0, s.reloadKey(a.id))

        s.open(a)
        s.requestReload(a.id)
        assertEquals(1, s.reloadKey(a.id))
    }

    @Test
    fun `closing a tab forgets its reload count`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        s.open(a)
        s.open(a)
        assertEquals(1, s.reloadKey(a.id))

        s.close(a.id)
        s.open(a)

        assertEquals(0, s.reloadKey(a.id))
    }

    @Test
    fun `closeOthers forgets the closed tabs' reload counts`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        val b = fileTab("/x/b.txt")
        s.open(a); s.open(a)
        s.open(b); s.open(b)

        s.closeOthers(b.id)
        s.open(a)

        assertEquals(0, s.reloadKey(a.id))
        assertEquals(1, s.reloadKey(b.id))
    }

    @Test
    fun `select changes selection only if id exists`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        val b = fileTab("/x/b.txt")
        s.open(a); s.open(b)
        s.select(a.id)
        assertEquals(a.id, s.selectedId)
        s.select("nope")
        assertEquals(a.id, s.selectedId)
    }

    @Test
    fun `a fresh state has one group that everything opens into`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        s.open(a)

        assertEquals(listOf(TabGroups.DEFAULT_NAME), s.groups.map { it.name })
        assertEquals(s.groups[0].id, s.activeGroupId)
        assertEquals(s.groups[0].id, s.groupOf(a.id))
    }

    @Test
    fun `a new group is armed, so the next file opens into it`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        s.open(a)
        val second = s.addGroup()
        val b = fileTab("/x/b.txt")
        s.open(b)

        assertEquals(listOf("MR1", "MR2"), s.groups.map { it.name })
        assertEquals(second.id, s.activeGroupId)
        assertEquals(listOf(a), s.tabsIn(s.groups[0].id))
        assertEquals(listOf(b), s.tabsIn(second.id))
    }

    @Test
    fun `tabs stay grouped in strip order however they were opened`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        val b = fileTab("/x/b.txt")
        val c = fileTab("/x/c.txt")
        s.open(a)
        val second = s.addGroup()
        s.open(b)
        // Back to the first group for one more file: it joins MR1's run, not the end of the strip.
        s.selectGroup(s.groups[0].id)
        s.open(c)

        assertEquals(listOf(a, c, b), s.tabs)
        assertEquals(listOf(a, c), s.tabsIn(s.groups[0].id))
        assertEquals(listOf(b), s.tabsIn(second.id))
    }

    @Test
    fun `opening into a collapsed group unfolds it`() {
        val s = TabsState()
        s.open(fileTab("/x/a.txt"))
        s.toggleCollapse(s.groups[0].id)
        assertEquals(true, s.groups[0].collapsed)

        s.open(fileTab("/x/b.txt"))

        assertEquals(false, s.groups[0].collapsed)
    }

    @Test
    fun `collapsing a group moves the selection onto a tab still on show`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        s.open(a)
        val second = s.addGroup()
        val b = fileTab("/x/b.txt")
        s.open(b)
        assertEquals(b.id, s.selectedId)

        s.setCollapsed(second.id, collapsed = true)

        assertEquals(a.id, s.selectedId)
    }

    @Test
    fun `collapsing the only group leaves the selection alone`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        s.open(a)

        s.setCollapsed(s.groups[0].id, collapsed = true)

        // Nothing is drawn to move to, so the editor keeps showing what it was showing.
        assertEquals(a.id, s.selectedId)
    }

    @Test
    fun `closing a tab skips over one folded away in a collapsed group`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        s.open(a)
        val second = s.addGroup()
        val b = fileTab("/x/b.txt")
        s.open(b)
        s.setCollapsed(second.id, collapsed = true)
        s.select(a.id)

        s.close(a.id)

        // b is the only tab left but it's hidden, so it's the fallback rather than the first choice.
        assertEquals(b.id, s.selectedId)
        assertEquals(listOf(b), s.tabs)
    }

    @Test
    fun `removeGroup closes its tabs and hands back what it removed`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        s.open(a)
        val second = s.addGroup()
        val b = fileTab("/x/b.txt")
        val c = fileTab("/x/c.txt")
        s.open(b)
        s.open(c)

        val removed = s.removeGroup(second.id)

        assertEquals(listOf(b, c), removed)
        assertEquals(listOf(a), s.tabs)
        assertEquals(listOf("MR1"), s.groups.map { it.name })
        assertEquals(s.groups[0].id, s.activeGroupId)
        assertEquals(a.id, s.selectedId)
    }

    @Test
    fun `removing the last group leaves a fresh default behind`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        s.open(a)

        val removed = s.removeGroup(s.groups[0].id)

        assertEquals(listOf(a), removed)
        assertEquals(listOf(TabGroups.DEFAULT_NAME), s.groups.map { it.name })
        assertEquals(s.groups[0].id, s.activeGroupId)
        assertNull(s.selectedId)

        // …and it still works as a bucket.
        val b = fileTab("/x/b.txt")
        s.open(b)
        assertEquals(s.groups[0].id, s.groupOf(b.id))
    }

    @Test
    fun `renameGroup ignores a blank name`() {
        val s = TabsState()
        s.renameGroup(s.groups[0].id, "Review")
        assertEquals("Review", s.groups[0].name)

        s.renameGroup(s.groups[0].id, "   ")
        assertEquals("Review", s.groups[0].name)
    }

    @Test
    fun `moveTabToGroup re-homes a tab and unfolds where it lands`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        val b = fileTab("/x/b.txt")
        s.open(a)
        s.open(b)
        val second = s.addGroup()
        s.setCollapsed(second.id, collapsed = true)

        s.moveTabToGroup(a.id, second.id)

        assertEquals(second.id, s.groupOf(a.id))
        assertEquals(false, s.groups[1].collapsed)
        // The strip stays grouped: MR1's remaining tab first, then MR2's.
        assertEquals(listOf(b, a), s.tabs)
    }

    @Test
    fun `applyStrip adopts a dragged order, membership and all`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        s.open(a)
        val second = s.addGroup()
        val b = fileTab("/x/b.txt")
        s.open(b)

        // What a drag of "a" one slot to the right produces: it crosses into MR2.
        val dragged = listOf(
            StripItem.Header(s.groups[0]),
            StripItem.Header(s.groups[1]),
            StripItem.Slot(a.id),
            StripItem.Slot(b.id),
        )
        s.applyStrip(dragged)

        assertEquals(second.id, s.groupOf(a.id))
        assertEquals(listOf(a, b), s.tabsIn(second.id))
        assertEquals(emptyList<Tab>(), s.tabsIn(s.groups[0].id))
    }

    @Test
    fun `applyStrip ignores a list that isn't the strip it has`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        s.open(a)
        val before = s.strip

        s.applyStrip(listOf(StripItem.Header(s.groups[0]))) // a tab went missing — a stale drag
        s.applyStrip(emptyList())

        assertEquals(before, s.strip)
    }

    @Test
    fun `the strip reads as headers followed by their tabs`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        s.open(a)
        val second = s.addGroup()
        val b = fileTab("/x/b.txt")
        s.open(b)

        assertEquals(
            listOf(
                StripItem.Header(s.groups[0]),
                StripItem.Slot(a.id),
                StripItem.Header(s.groups[1]),
                StripItem.Slot(b.id),
            ),
            s.strip,
        )
        assertEquals(second.id, s.groups[1].id)
    }

    @Test
    fun `closeOthers keeps the groups it emptied`() {
        val s = TabsState()
        val a = fileTab("/x/a.txt")
        s.open(a)
        val second = s.addGroup()
        val b = fileTab("/x/b.txt")
        s.open(b)

        s.closeOthers(b.id)

        assertEquals(listOf(b), s.tabs)
        assertEquals(2, s.groups.size)
        assertEquals(second.id, s.groupOf(b.id))
        assertNull(s.groupOf(a.id))
    }

    @Test
    fun `jump to source resolves the working file behind both diff kinds`(@TempDir tmp: Path) {
        val repo = tmp.toFile()
        val tracked = tmp.resolve("app.component.ts").toFile().apply { writeText("x") }

        val workingDiff = Tab.Diff(FileChange("app.component.ts", ChangeKind.MODIFIED), repo)
        assertEquals(tracked, jumpToSourceTarget(workingDiff))

        val commitDiff = Tab.CommitDiff(
            sha = "abc1234def",
            shortSha = "abc1234",
            file = CommitFile("app.component.ts", CommitFileChange.MODIFIED),
            repoRoot = repo,
        )
        assertEquals(tracked, jumpToSourceTarget(commitDiff))
    }

    @Test
    fun `jump to source has no target for a non-diff tab or a vanished file`(@TempDir tmp: Path) {
        val repo = tmp.toFile()

        assertNull(jumpToSourceTarget(null))
        assertNull(jumpToSourceTarget(fileTab("/x/a.txt")))
        assertNull(jumpToSourceTarget(Tab.History(repo, repo)))
        // Deleted by the commit being read, so there's no working file to jump to.
        assertNull(
            jumpToSourceTarget(
                Tab.CommitDiff("abc1234def", "abc1234", CommitFile("gone.ts", CommitFileChange.DELETED), repo),
            ),
        )
        // A directory is never a jump target either, even if a path collides with one.
        tmp.resolve("dir.ts").toFile().mkdirs()
        assertNull(jumpToSourceTarget(Tab.Diff(FileChange("dir.ts", ChangeKind.MODIFIED), repo)))
    }
}
