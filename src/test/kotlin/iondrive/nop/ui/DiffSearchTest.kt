package iondrive.nop.ui

import iondrive.nop.diff.DiffComputer
import iondrive.nop.diff.DiffRow
import iondrive.nop.diff.RowKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiffSearchTest {
    private fun row(
        kind: RowKind,
        old: String?,
        new: String?,
        oldNo: Int? = null,
        newNo: Int? = null,
    ) = DiffRow(kind, old, new, emptyList(), emptyList(), oldNo, newNo)

    @Test
    fun `empty query finds nothing`() {
        val rows = listOf(row(RowKind.EQUAL, "needle", "needle"))
        assertEquals(emptyList<DiffMatch>(), findDiffMatches(rows, ""))
    }

    @Test
    fun `match on the old side only is reported for that side`() {
        val rows = listOf(row(RowKind.DELETE, "gone needle", null, oldNo = 1))

        val matches = findDiffMatches(rows, "needle")

        assertEquals(1, matches.size)
        assertEquals(DiffSide.OLD, matches[0].side)
        assertEquals(5..10, matches[0].range)
        assertFalse(matches[0].mirrored)
    }

    @Test
    fun `match on the new side only is reported for that side`() {
        val rows = listOf(row(RowKind.INSERT, null, "new needle", newNo = 1))

        val matches = findDiffMatches(rows, "needle")

        assertEquals(1, matches.size)
        assertEquals(DiffSide.NEW, matches[0].side)
    }

    @Test
    fun `a changed row is searched on both sides`() {
        val rows = listOf(row(RowKind.CHANGE, "old needle", "new needle", 1, 1))

        val matches = findDiffMatches(rows, "needle")

        assertEquals(listOf(DiffSide.OLD, DiffSide.NEW), matches.map { it.side })
        assertTrue(matches.none { it.mirrored })
    }

    @Test
    fun `an unchanged row yields one mirrored match, not one per pane`() {
        // Both halves of an EQUAL row hold the same text, so Next must stop on it once.
        val rows = listOf(row(RowKind.EQUAL, "same needle", "same needle", 1, 1))

        val matches = findDiffMatches(rows, "needle")

        assertEquals(1, matches.size)
        assertTrue(matches[0].mirrored)
    }

    @Test
    fun `matches come back in reading order - down the rows, old side first`() {
        val rows = listOf(
            row(RowKind.EQUAL, "a needle", "a needle", 1, 1),
            row(RowKind.CHANGE, "b needle", "c needle", 2, 2),
            row(RowKind.INSERT, null, "d needle", newNo = 3),
        )

        val matches = findDiffMatches(rows, "needle")

        assertEquals(
            listOf(
                0 to DiffSide.NEW, // the mirrored (unchanged) row
                1 to DiffSide.OLD,
                1 to DiffSide.NEW,
                2 to DiffSide.NEW,
            ),
            matches.map { it.rowIndex to it.side },
        )
    }

    @Test
    fun `search is case-insensitive and finds repeats within a line`() {
        val rows = listOf(row(RowKind.INSERT, null, "Needle and needle", newNo = 1))

        val matches = findDiffMatches(rows, "NEEDLE")

        assertEquals(listOf(0..5, 11..16), matches.map { it.range })
    }

    @Test
    fun `a runaway query is capped`() {
        // 3000 rows each holding two hits: without the cap this would collect 6000.
        val rows = List(3000) { row(RowKind.INSERT, null, "aa", newNo = it + 1) }

        assertEquals(5000, findDiffMatches(rows, "a").size)
    }

    @Test
    fun `a mirrored match highlights both halves of its row`() {
        val match = DiffMatch(rowIndex = 4, side = DiffSide.NEW, range = 0..2, mirrored = true)
        val search = DiffSearch(groupDiffMatches(listOf(match)), activeDiffCells(match))

        assertEquals(listOf(0..2), search.rangesFor(4, DiffSide.OLD))
        assertEquals(listOf(0..2), search.rangesFor(4, DiffSide.NEW))
        assertEquals(0..2, search.activeRangeFor(4, DiffSide.OLD))
        assertEquals(0..2, search.activeRangeFor(4, DiffSide.NEW))
    }

    @Test
    fun `a one-sided match leaves the other half alone`() {
        val match = DiffMatch(rowIndex = 4, side = DiffSide.OLD, range = 0..2)
        val search = DiffSearch(groupDiffMatches(listOf(match)), activeDiffCells(match))

        assertEquals(listOf(0..2), search.rangesFor(4, DiffSide.OLD))
        assertEquals(emptyList<IntRange>(), search.rangesFor(4, DiffSide.NEW))
        assertNull(search.activeRangeFor(4, DiffSide.NEW))
    }

    @Test
    fun `only the active match reads as active`() {
        val rows = listOf(
            row(RowKind.INSERT, null, "needle", newNo = 1),
            row(RowKind.INSERT, null, "needle", newNo = 2),
        )
        val matches = findDiffMatches(rows, "needle")
        val search = DiffSearch(groupDiffMatches(matches), activeDiffCells(matches[1]))

        assertNull(search.activeRangeFor(0, DiffSide.NEW))
        assertEquals(0..5, search.activeRangeFor(1, DiffSide.NEW))
    }

    @Test
    fun `rows a query misses have no hits`() {
        val rows = listOf(row(RowKind.EQUAL, "nothing", "nothing", 1, 1))
        val search = DiffSearch(groupDiffMatches(findDiffMatches(rows, "needle")), activeDiffCells(null))

        assertEquals(emptyList<IntRange>(), search.rangesFor(0, DiffSide.OLD))
    }

    @Test
    fun `searching a real computed diff finds the change on both sides`() {
        // The line changed, so "needle" is found separately on each side; the surrounding context
        // lines are unchanged and so contribute one mirrored hit each.
        val result = DiffComputer.compute(
            "needle above\nold needle\nneedle below\n",
            "needle above\nnew needle\nneedle below\n",
        )

        val matches = findDiffMatches(result.rows, "needle")

        assertEquals(4, matches.size)
        assertEquals(2, matches.count { it.mirrored })
        assertEquals(listOf(DiffSide.OLD, DiffSide.NEW), matches.filterNot { it.mirrored }.map { it.side })
    }
}
