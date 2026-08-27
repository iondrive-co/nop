package iondrive.nop.ui

import iondrive.nop.git.ChangeKind
import iondrive.nop.git.FileChange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The "Revert all" confirmation must describe exactly what it is about to destroy — this is the
 * only warning the user gets before uncommitted work is gone for good.
 */
class ConfirmRevertAllTest {
    private fun change(path: String, kind: ChangeKind) = FileChange(path, kind)

    @Test
    fun `title counts the changes and gets the plural right`() {
        assertEquals("Revert all 1 change?", revertAllTitle(1))
        assertEquals("Revert all 2 changes?", revertAllTitle(2))
        assertEquals("Revert all 17 changes?", revertAllTitle(17))
    }

    @Test
    fun `summary splits restored files from deleted new ones`() {
        val lines = revertAllSummary(
            listOf(
                change("a.kt", ChangeKind.MODIFIED),
                change("b.kt", ChangeKind.MISSING),
                change("c.kt", ChangeKind.REMOVED),
                change("new.kt", ChangeKind.ADDED),
                change("junk.txt", ChangeKind.UNTRACKED),
            )
        )
        assertEquals(2, lines.size, "one line per outcome: $lines")
        assertTrue(lines[0].startsWith("3 files will be restored"), "3 tracked files: ${lines[0]}")
        assertTrue(lines[1].startsWith("2 new files will be deleted"), "2 new files: ${lines[1]}")
    }

    @Test
    fun `summary omits the outcome that does not apply`() {
        val onlyTracked = revertAllSummary(listOf(change("a.kt", ChangeKind.MODIFIED)))
        assertEquals(1, onlyTracked.size, "no new files, so no deletion warning: $onlyTracked")
        assertTrue(onlyTracked.single().startsWith("1 file will be restored"), onlyTracked.single())

        val onlyNew = revertAllSummary(listOf(change("junk.txt", ChangeKind.UNTRACKED)))
        assertEquals(1, onlyNew.size, "nothing tracked, so no restore line: $onlyNew")
        assertTrue(onlyNew.single().startsWith("1 new file will be deleted"), onlyNew.single())
        assertTrue(onlyNew.single().contains("can't be undone"), "unrecoverable loss is spelled out")
    }

    @Test
    fun `a conflicted file counts as restored, not deleted`() {
        val lines = revertAllSummary(listOf(change("merge.kt", ChangeKind.CONFLICT)))
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("1 file will be restored"), lines.single())
    }

    @Test
    fun `an empty change set has nothing to warn about`() {
        assertEquals(emptyList<String>(), revertAllSummary(emptyList()))
    }
}
