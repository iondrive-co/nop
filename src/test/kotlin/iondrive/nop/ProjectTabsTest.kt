package iondrive.nop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

class ProjectTabsTest {
    private fun p(s: String): Path = Paths.get(s)

    /** A row of tabs on [paths], numbered from 0 — which is what a restored window's bar looks like. */
    private fun tabs(vararg paths: String) = ProjectTabs.of(paths.map(::p))

    private val a = p("/p/a")
    private val b = p("/p/b")
    private val c = p("/p/c")

    private val abc = tabs("/p/a", "/p/b", "/p/c")

    @Test
    fun `of numbers the tabs from the id it is given`() {
        assertEquals(listOf(5L, 6L), ProjectTabs.of(listOf(a, b), firstId = 5).map { it.id })
    }

    @Test
    fun `of normalizes the paths it is handed`() {
        assertEquals(a, ProjectTabs.of(listOf(p("/p/x/../a"))).single().path)
    }

    @Test
    fun `two tabs on one project are two tabs`() {
        val both = ProjectTabs.of(listOf(a, a))
        assertNotEquals(both[0].id, both[1].id)
        assertEquals(listOf(a, a), both.map { it.path })
    }

    @Test
    fun `a tab goes by its project's directory name until it is given one`() {
        assertEquals("a", ProjectTab(0, a).label)
        assertEquals("the release one", ProjectTab(0, a, name = "the release one").label)
    }

    @Test
    fun `closing the active middle tab selects the tab that slides into its slot`() {
        assertEquals(2L, ProjectTabs.activeAfterClose(abc, closed = 1, active = 1))
    }

    @Test
    fun `closing the active last tab falls back to the new last tab`() {
        assertEquals(1L, ProjectTabs.activeAfterClose(abc, closed = 2, active = 2))
    }

    @Test
    fun `closing the only tab leaves nothing active`() {
        assertNull(ProjectTabs.activeAfterClose(tabs("/p/a"), closed = 0, active = 0))
    }

    @Test
    fun `closing a non-active tab keeps the current selection`() {
        assertEquals(0L, ProjectTabs.activeAfterClose(abc, closed = 2, active = 0))
    }

    @Test
    fun `closing a tab that is not present leaves the selection untouched`() {
        assertEquals(0L, ProjectTabs.activeAfterClose(abc, closed = 9, active = 0))
    }

    @Test
    fun `closing one of two tabs on the same project leaves the other one alone`() {
        val both = ProjectTabs.of(listOf(a, a))
        assertEquals(both[1].id, ProjectTabs.activeAfterClose(both, closed = both[0].id, active = both[0].id))
    }

    @Test
    fun `initialActive restores the tab at the saved position`() {
        assertEquals(1L, ProjectTabs.initialActive(abc, saved = 1))
    }

    @Test
    fun `initialActive falls back to the first tab when the saved position is gone`() {
        assertEquals(0L, ProjectTabs.initialActive(abc, saved = 7))
    }

    @Test
    fun `initialActive falls back to the first tab when nothing was saved`() {
        assertEquals(0L, ProjectTabs.initialActive(abc, saved = null))
    }

    @Test
    fun `initialActive is null when there are no tabs`() {
        assertNull(ProjectTabs.initialActive(emptyList(), saved = 0))
    }

    @Test
    fun `recentMenu drops projects this window already has a tab for`() {
        assertEquals(listOf(c), ProjectTabs.recentMenu(recent = listOf(a, b, c), open = listOf(a, b)))
    }

    @Test
    fun `recentMenu keeps newest-first order and de-duplicates`() {
        assertEquals(listOf(c, a, b), ProjectTabs.recentMenu(recent = listOf(c, a, b, c, a), open = emptyList()))
    }

    @Test
    fun `recentMenu is empty when every recent is open here`() {
        assertEquals(emptyList<Path>(), ProjectTabs.recentMenu(recent = listOf(a, b), open = listOf(b, a)))
    }
}
