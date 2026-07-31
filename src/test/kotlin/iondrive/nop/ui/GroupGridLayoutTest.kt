package iondrive.nop.ui

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroupGridLayoutTest {

    // A comfortably large area so the interesting behaviour is driven by group count, not clamps.
    private val wide = 1000.dp
    private val tall = 400.dp

    @Test
    fun `single group fills the whole width in one row`() {
        val grid = GroupGridMetrics.layout(1, 200.dp, wide, tall)
        assertEquals(1, grid.rows)
        assertEquals(1, grid.columnsPerRow)
        assertFalse(grid.scrollHorizontally)
        assertEquals(1000f, grid.columnWidth.value, 0.01f)
        assertEquals(400f, grid.rowHeight.value, 0.01f)
    }

    @Test
    fun `a few groups share one row, stretched to fill and never below natural`() {
        val grid = GroupGridMetrics.layout(3, 200.dp, wide, tall)
        assertEquals(1, grid.rows)
        assertEquals(3, grid.columnsPerRow)
        assertFalse(grid.scrollHorizontally)
        assertTrue(grid.columnWidth.value >= 200f)
    }

    @Test
    fun `columns wrap to a new row below before scrolling once they no longer fit across`() {
        // 200dp columns fit 4 across 1000dp; a 5th wraps rather than squeezing thinner.
        val grid = GroupGridMetrics.layout(6, 200.dp, wide, tall)
        assertEquals(2, grid.rows)
        assertEquals(3, grid.columnsPerRow)
        assertFalse(grid.scrollHorizontally)
        // Two rows split the height (minus the inter-row gap).
        assertEquals((400f - 12f) / 2f, grid.rowHeight.value, 0.01f)
    }

    @Test
    fun `once vertical space is exhausted the grid scrolls horizontally`() {
        // Height only fits one row; the ten groups overflow to the right instead of wrapping.
        val grid = GroupGridMetrics.layout(10, 200.dp, wide, 150.dp)
        assertEquals(1, grid.rows)
        assertEquals(10, grid.columnsPerRow)
        assertTrue(grid.scrollHorizontally)
        // Scrolling columns keep their natural width so more fit before the scrollbar appears.
        assertEquals(200f, grid.columnWidth.value, 0.01f)
        assertTrue(grid.contentWidth.value > wide.value)
    }

    @Test
    fun `natural width is clamped up to the minimum`() {
        // Short names, but forced to scroll so the width isn't stretched away from the clamp.
        val grid = GroupGridMetrics.layout(20, 50.dp, 300.dp, 120.dp)
        assertTrue(grid.scrollHorizontally)
        assertEquals(GroupGridMetrics.MIN_COLUMN_WIDTH.value, grid.columnWidth.value, 0.01f)
    }

    @Test
    fun `natural width is clamped down to the maximum`() {
        val grid = GroupGridMetrics.layout(10, 1000.dp, 500.dp, 120.dp)
        assertTrue(grid.scrollHorizontally)
        assertEquals(GroupGridMetrics.MAX_COLUMN_WIDTH.value, grid.columnWidth.value, 0.01f)
    }

    @Test
    fun `no groups yields an empty grid`() {
        val grid = GroupGridMetrics.layout(0, 200.dp, wide, tall)
        assertEquals(0, grid.rows)
        assertEquals(0, grid.columnsPerRow)
        assertFalse(grid.scrollHorizontally)
    }

    @Test
    fun `degenerate zero-size constraints do not crash and stay sane`() {
        val grid = GroupGridMetrics.layout(3, 200.dp, 0.dp, 0.dp)
        assertTrue(grid.columnsPerRow >= 1)
        assertTrue(grid.rows >= 1)
        assertTrue(grid.columnWidth.value >= GroupGridMetrics.MIN_COLUMN_WIDTH.value)
    }

    @Test
    fun `adding groups to a fixed area progresses stretch then wrap then scroll`() {
        // fitCols across 1000dp at 200dp natural = 4; fitRows down 400dp at 108dp min = 3.
        // 1..4 groups: single row.
        for (n in 1..4) {
            val g = GroupGridMetrics.layout(n, 200.dp, wide, tall)
            assertEquals(1, g.rows, "n=$n should stay on one row")
            assertFalse(g.scrollHorizontally, "n=$n should not scroll")
        }
        // 5..12 groups: wraps onto up to three rows, still no scroll.
        val wrapped = GroupGridMetrics.layout(9, 200.dp, wide, tall)
        assertTrue(wrapped.rows in 2..3)
        assertFalse(wrapped.scrollHorizontally)
        // Beyond 4x3 capacity: scrolls horizontally.
        val overflow = GroupGridMetrics.layout(13, 200.dp, wide, tall)
        assertEquals(3, overflow.rows)
        assertTrue(overflow.scrollHorizontally)
    }
}
