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
