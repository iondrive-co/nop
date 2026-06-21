package iondrive.nopdiff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SideBySideTest {

    private fun rowsOf(diff: String): List<DiffRow> =
        SideBySide.build(UnifiedDiffParser.parse(diff).single())
            .filterIsInstance<RenderRow.Line>()
            .map { it.row }

    @Test
    fun pairsDeleteAndAddIntoChangeRow() {
        val diff = listOf(
            "--- a/f", "+++ b/f",
            "@@ -1,3 +1,3 @@",
            " a",
            "-b1",
            "+b2",
            " c",
        ).joinToString("\n")
        val rows = rowsOf(diff)
        // EQUAL a / CHANGE b1|b2 / EQUAL c
        assertEquals(listOf(RowKind.EQUAL, RowKind.CHANGE, RowKind.EQUAL), rows.map { it.kind })
        val change = rows[1]
        assertEquals("b1", change.oldLine)
        assertEquals("b2", change.newLine)
        assertEquals(2, change.oldLineNumber)
        assertEquals(2, change.newLineNumber)
        assertTrue(change.oldSpans.any { it.changed }, "expected inline highlight on changed row")
    }

    @Test
    fun unpairedDeletionsBecomeDeleteRows() {
        val diff = listOf(
            "--- a/f", "+++ b/f",
            "@@ -1,3 +1,1 @@",
            "-x",
            "-y",
            "+z",
        ).joinToString("\n")
        val rows = rowsOf(diff)
        // 2 deletes + 1 add: first pairs into CHANGE, leftover delete is DELETE
        assertEquals(listOf(RowKind.CHANGE, RowKind.DELETE), rows.map { it.kind })
        assertEquals("y", rows[1].oldLine)
        assertEquals(null, rows[1].newLine)
    }

    @Test
    fun unpairedAdditionsBecomeInsertRows() {
        val diff = listOf(
            "--- a/f", "+++ b/f",
            "@@ -1,1 +1,3 @@",
            "-x",
            "+a",
            "+b",
        ).joinToString("\n")
        val rows = rowsOf(diff)
        assertEquals(listOf(RowKind.CHANGE, RowKind.INSERT), rows.map { it.kind })
        assertEquals("b", rows[1].newLine)
        assertEquals(null, rows[1].oldLine)
    }

    @Test
    fun hunkSeparatorPrecedesEachHunk() {
        val diff = listOf(
            "--- a/f", "+++ b/f",
            "@@ -1,1 +1,1 @@",
            "-x",
            "+y",
            "@@ -10,1 +10,1 @@",
            "-p",
            "+q",
        ).joinToString("\n")
        val rendered = SideBySide.build(UnifiedDiffParser.parse(diff).single())
        assertEquals(2, rendered.count { it is RenderRow.HunkSeparator })
        assertTrue(rendered.first() is RenderRow.HunkSeparator)
    }
}
