package iondrive.nop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

class ProjectTabsTest {
    private fun p(s: String): Path = Paths.get(s)

    private val a = p("/p/a")
    private val b = p("/p/b")
    private val c = p("/p/c")

    @Test
    fun `closing the active middle tab selects the tab that slides into its slot`() {
        assertEquals(c, ProjectTabs.activeAfterClose(listOf(a, b, c), closed = b, active = b))
    }

    @Test
    fun `closing the active last tab falls back to the new last tab`() {
        assertEquals(b, ProjectTabs.activeAfterClose(listOf(a, b, c), closed = c, active = c))
    }

    @Test
    fun `closing the only tab leaves nothing active`() {
        assertNull(ProjectTabs.activeAfterClose(listOf(a), closed = a, active = a))
    }

    @Test
    fun `closing a non-active tab keeps the current selection`() {
        assertEquals(a, ProjectTabs.activeAfterClose(listOf(a, b, c), closed = c, active = a))
    }

    @Test
    fun `closing a tab that is not present leaves the selection untouched`() {
        assertEquals(a, ProjectTabs.activeAfterClose(listOf(a, b), closed = c, active = a))
    }

    @Test
    fun `initialActive restores the saved tab when it is still open`() {
        assertEquals(b, ProjectTabs.initialActive(listOf(a, b, c), saved = b))
    }

    @Test
    fun `initialActive falls back to the first tab when the saved one is gone`() {
        assertEquals(a, ProjectTabs.initialActive(listOf(a, b, c), saved = p("/p/x")))
    }

    @Test
    fun `initialActive falls back to the first tab when nothing was saved`() {
        assertEquals(a, ProjectTabs.initialActive(listOf(a, b, c), saved = null))
    }

    @Test
    fun `initialActive is null when there are no tabs`() {
        assertNull(ProjectTabs.initialActive(emptyList(), saved = a))
    }

    @Test
    fun `recentMenu drops projects that are already open`() {
        assertEquals(listOf(c), ProjectTabs.recentMenu(recent = listOf(a, b, c), open = listOf(a, b)))
    }

    @Test
    fun `recentMenu keeps newest-first order and de-duplicates`() {
        assertEquals(listOf(c, a, b), ProjectTabs.recentMenu(recent = listOf(c, a, b, c, a), open = emptyList()))
    }

    @Test
    fun `recentMenu is empty when every recent is open`() {
        assertEquals(emptyList<Path>(), ProjectTabs.recentMenu(recent = listOf(a, b), open = listOf(b, a)))
    }
}
