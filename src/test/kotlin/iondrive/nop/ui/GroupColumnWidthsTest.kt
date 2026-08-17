package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroupColumnWidthsTest {

    private val min = GroupGridMetrics.MIN_COLUMN_WIDTH.value

    private fun total(widths: List<Float>) = widths.sum()

    @Test
    fun `untouched columns all get the packed width`() {
        val widths = GroupColumnWidths.resolve(200f, listOf(0f, 0f, 0f))
        assertEquals(listOf(200f, 200f, 200f), widths.map { it })
    }

    @Test
    fun `a drag hands width from one column to its neighbour and keeps the row width`() {
        val before = listOf(200f, 200f, 200f)
        val after = GroupColumnWidths.drag(before, 0, 40f)
        assertEquals(240f, after[0], 0.01f)
        assertEquals(160f, after[1], 0.01f)
        assertEquals(200f, after[2], 0.01f)
        assertEquals(total(before), total(after), 0.01f)
    }

    @Test
    fun `dragging left moves width the other way`() {
        val after = GroupColumnWidths.drag(listOf(300f, 300f), 0, -50f)
        assertEquals(250f, after[0], 0.01f)
        assertEquals(350f, after[1], 0.01f)
    }

    @Test
    fun `a drag stops at the minimum width of the column it is shrinking`() {
        val before = listOf(200f, 200f)
        val after = GroupColumnWidths.drag(before, 0, 1000f)
        assertEquals(min, after[1], 0.01f)
        assertEquals(400f - min, after[0], 0.01f)
        assertEquals(total(before), total(after), 0.01f)
    }

    @Test
    fun `a drag stops at the minimum width of the column it is shrinking on the left`() {
        val after = GroupColumnWidths.drag(listOf(200f, 200f), 0, -1000f)
        assertEquals(min, after[0], 0.01f)
        assertEquals(400f - min, after[1], 0.01f)
    }

    @Test
    fun `dragging a separator that is not there is a no-op`() {
        val before = listOf(200f, 200f)
        assertEquals(before, GroupColumnWidths.drag(before, 1, 20f))
        assertEquals(before, GroupColumnWidths.drag(before, -1, 20f))
    }

    @Test
    fun `dragging when there is no room to trade leaves the widths alone`() {
        // Both columns are already under the minimum, so neither can give the other anything.
        val before = listOf(min - 20f, min - 20f)
        assertEquals(before, GroupColumnWidths.drag(before, 0, 10f))
    }

    @Test
    fun `offsets survive a change in the packed width`() {
        // The user gave column 0 40dp of column 1; the window then narrows the packed width.
        val offsets = listOf(40f, -40f, 0f)
        val widths = GroupColumnWidths.resolve(190f, offsets)
        assertEquals(230f, widths[0], 0.01f)
        assertEquals(150f, widths[1], 0.01f)
        assertEquals(190f, widths[2], 0.01f)
        assertEquals(570f, total(widths), 0.01f)
    }

    @Test
    fun `offsets that do not cancel out are re-centred rather than widening the row`() {
        val widths = GroupColumnWidths.resolve(200f, listOf(60f, 0f, 0f))
        assertEquals(600f, total(widths), 0.01f)
        assertEquals(240f, widths[0], 0.01f)
        assertEquals(180f, widths[1], 0.01f)
        assertEquals(180f, widths[2], 0.01f)
    }

    @Test
    fun `a column squeezed under the minimum is pulled back up at the others' expense`() {
        val widths = GroupColumnWidths.resolve(200f, listOf(300f, -150f, -150f))
        assertEquals(600f, total(widths), 0.01f)
        assertTrue(widths.all { it >= min - 0.01f }, "widths were $widths")
        assertEquals(min, widths[1], 0.01f)
        assertEquals(min, widths[2], 0.01f)
        assertEquals(600f - 2 * min, widths[0], 0.01f)
    }

    @Test
    fun `a row too narrow for the minimum everywhere shares out what there is evenly`() {
        val widths = GroupColumnWidths.resolve(min / 2f, listOf(80f, 0f, -80f))
        assertEquals(3, widths.size)
        assertTrue(widths.all { kotlin.math.abs(it - min / 2f) < 0.01f }, "widths were $widths")
    }

    @Test
    fun `no columns resolves to no widths`() {
        assertEquals(emptyList<Float>(), GroupColumnWidths.resolve(200f, emptyList()))
    }

    @Test
    fun `dragged widths round-trip through offsets`() {
        // What the grid does after a drag: store width-minus-packed for every column in the row,
        // then resolve again on the next frame. The layout must not shift under the user.
        val base = 200f
        val dragged = GroupColumnWidths.drag(listOf(base, base, base), 1, 35f)
        val resolved = GroupColumnWidths.resolve(base, dragged.map { it - base })
        for (i in dragged.indices) assertEquals(dragged[i], resolved[i], 0.01f)
    }
}
