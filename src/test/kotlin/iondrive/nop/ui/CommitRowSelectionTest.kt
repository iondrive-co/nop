package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommitRowSelectionTest {
    private val paths = listOf("src/Alpha.kt", "src/Bravo.kt", "tests/AlphaTest.kt", "docs/guide.md")

    private fun CommitRowSelection.select(index: Int, ctrl: Boolean = false, shift: Boolean = false) =
        click(this@CommitRowSelectionTest.paths[index], this@CommitRowSelectionTest.paths, toggle = ctrl, range = shift)

    @Test
    fun `a plain click replaces the selection`() {
        val selection = CommitRowSelection().select(0).select(2, ctrl = true).select(1)
        assertEquals(setOf(paths[1]), selection.paths)
    }

    @Test
    fun `ctrl adds and removes individual files`() {
        val selection = CommitRowSelection().select(0).select(2, ctrl = true)
        assertEquals(setOf(paths[0], paths[2]), selection.paths)
        assertEquals(setOf(paths[2]), selection.select(0, ctrl = true).paths)
        assertEquals(emptySet<String>(), selection.select(0, ctrl = true).select(2, ctrl = true).paths)
    }

    @Test
    fun `shift selects a range across groups in either direction`() {
        assertEquals(paths.toSet(), CommitRowSelection().select(0).select(3, shift = true).paths)
        assertEquals(paths.drop(1).toSet(), CommitRowSelection().select(3).select(1, shift = true).paths)
    }

    @Test
    fun `shift replaces previous disjoint selections`() {
        val selection = CommitRowSelection().select(0).select(2, ctrl = true).select(3, shift = true)
        assertEquals(paths.drop(2).toSet(), selection.paths)
    }

    @Test
    fun `shift can shrink a range without moving its anchor`() {
        val selection = CommitRowSelection().select(0).select(3, shift = true).select(1, shift = true)
        assertEquals(paths.take(2).toSet(), selection.paths)
    }

    @Test
    fun `ctrl shift adds a range to the existing selection`() {
        val selection = CommitRowSelection().select(0).select(2, ctrl = true).select(3, ctrl = true, shift = true)
        assertEquals(setOf(paths[0], paths[2], paths[3]), selection.paths)
    }

    @Test
    fun `shift with no anchor selects just the clicked row`() {
        assertEquals(setOf(paths[2]), CommitRowSelection().select(2, shift = true).paths)
    }

    @Test
    fun `refresh preserves selection and the anchor by path`() {
        val selection = CommitRowSelection().select(1).select(3, ctrl = true).reconcile(paths.toSet())
        assertEquals(setOf(paths[1], paths[3]), selection.paths)
        val order = listOf(paths[1], paths[3], paths[0], paths[2])
        assertEquals(setOf(paths[3], paths[0]), selection.click(paths[0], order, toggle = false, range = true).paths)
    }

    @Test
    fun `refresh drops missing files and clears a missing anchor`() {
        val selection = CommitRowSelection().select(0).select(3, ctrl = true).reconcile(paths.dropLast(1).toSet())
        assertEquals(setOf(paths[0]), selection.paths)
        assertEquals(setOf(paths[2]), selection.select(2, shift = true).paths)
        assertEquals(emptySet<String>(), selection.reconcile(emptySet()).paths)
    }
}
