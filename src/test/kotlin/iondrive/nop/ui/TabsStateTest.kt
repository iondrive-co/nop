package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

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
}
