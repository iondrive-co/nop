package iondrive.nop.ui

import iondrive.nop.git.CommitFile
import iondrive.nop.git.CommitFileChange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The revert-commit confirmation has to say what will change and where it lands, without borrowing
 * the alarm of the other revert dialogs — nothing here is unrecoverable.
 */
class ConfirmRevertCommitTest {
    private fun file(path: String) = CommitFile(path, CommitFileChange.MODIFIED)

    @Test
    fun `summary counts the files and says where the reversal lands`() {
        val lines = revertCommitSummary(listOf(file("a.kt"), file("b.kt")))
        assertEquals(2, lines.size, "one line per point: $lines")
        assertTrue(lines[0].startsWith("The changes this commit made to 2 files"), lines[0])
        assertTrue(lines[1].startsWith("Nothing is committed"), lines[1])
    }

    @Test
    fun `a one-file commit gets the singular`() {
        val line = revertCommitSummary(listOf(file("a.kt"))).first()
        assertTrue(line.contains("to 1 file will be undone"), "singular reads right: $line")
    }

    @Test
    fun `a commit whose file list could not be read still explains the outcome`() {
        // The reversal is defined by the commit, not by the list the panel managed to load, so an
        // empty list must not turn into a dialog that claims nothing will happen.
        val lines = revertCommitSummary(emptyList())
        assertEquals(1, lines.size, "no file count to give, but the outcome still stands: $lines")
        assertTrue(lines.single().startsWith("Nothing is committed"), lines.single())
    }
}
