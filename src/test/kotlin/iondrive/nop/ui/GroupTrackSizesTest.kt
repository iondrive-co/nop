package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroupTrackSizesTest {

    private val min = GroupGridMetrics.MIN_COLUMN_WIDTH.value
    private val minRow = GroupGridMetrics.MIN_ROW_HEIGHT.value

    private fun total(sizes: List<Float>) = sizes.sum()

    private fun resolve(base: Float, offsets: List<Float>) =
        GroupTrackSizes.resolve(base, offsets, min)

    private fun drag(sizes: List<Float>, index: Int, delta: Float) =
        GroupTrackSizes.drag(sizes, index, delta, min)

    @Test
    fun `untouched tracks all get the packed size`() {
        assertEquals(listOf(200f, 200f, 200f), resolve(200f, listOf(0f, 0f, 0f)))
    }

    @Test
    fun `a drag hands width from one column to its neighbour and keeps the row width`() {
        val before = listOf(200f, 200f, 200f)
        val after = drag(before, 0, 40f)
        assertEquals(240f, after[0], 0.01f)
        assertEquals(160f, after[1], 0.01f)
        assertEquals(200f, after[2], 0.01f)
        assertEquals(total(before), total(after), 0.01f)
    }

    @Test
    fun `dragging left moves width the other way`() {
        val after = drag(listOf(300f, 300f), 0, -50f)
        assertEquals(250f, after[0], 0.01f)
        assertEquals(350f, after[1], 0.01f)
    }

    @Test
    fun `a drag stops at the minimum size of the track it is shrinking`() {
        val before = listOf(200f, 200f)
        val after = drag(before, 0, 1000f)
        assertEquals(min, after[1], 0.01f)
        assertEquals(400f - min, after[0], 0.01f)
        assertEquals(total(before), total(after), 0.01f)
    }

    @Test
    fun `a drag stops at the minimum size of the track it is shrinking on the left`() {
        val after = drag(listOf(200f, 200f), 0, -1000f)
        assertEquals(min, after[0], 0.01f)
        assertEquals(400f - min, after[1], 0.01f)
    }

    @Test
    fun `dragging a separator that is not there is a no-op`() {
        val before = listOf(200f, 200f)
        assertEquals(before, drag(before, 1, 20f))
        assertEquals(before, drag(before, -1, 20f))
    }

    @Test
    fun `dragging when there is no room to trade leaves the sizes alone`() {
        // Both tracks are already under the minimum, so neither can give the other anything.
        val before = listOf(min - 20f, min - 20f)
        assertEquals(before, drag(before, 0, 10f))
    }

    @Test
    fun `rows trade height against the row minimum`() {
        val before = listOf(200f, 200f)
        val after = GroupTrackSizes.drag(before, 0, 1000f, minRow)
        assertEquals(minRow, after[1], 0.01f)
        assertEquals(400f - minRow, after[0], 0.01f)
    }

    @Test
    fun `offsets survive a change in the packed size`() {
        // The user gave column 0 40dp of column 1; the window then narrows the packed width.
        val sizes = resolve(190f, listOf(40f, -40f, 0f))
        assertEquals(230f, sizes[0], 0.01f)
        assertEquals(150f, sizes[1], 0.01f)
        assertEquals(190f, sizes[2], 0.01f)
        assertEquals(570f, total(sizes), 0.01f)
    }

    @Test
    fun `offsets that do not cancel out are re-centred rather than widening the row`() {
        val sizes = resolve(200f, listOf(60f, 0f, 0f))
        assertEquals(600f, total(sizes), 0.01f)
        assertEquals(240f, sizes[0], 0.01f)
        assertEquals(180f, sizes[1], 0.01f)
        assertEquals(180f, sizes[2], 0.01f)
    }

    @Test
    fun `a track squeezed under the minimum is pulled back up at the others' expense`() {
        val sizes = resolve(200f, listOf(300f, -150f, -150f))
        assertEquals(600f, total(sizes), 0.01f)
        assertTrue(sizes.all { it >= min - 0.01f }, "sizes were $sizes")
        assertEquals(min, sizes[1], 0.01f)
        assertEquals(min, sizes[2], 0.01f)
        assertEquals(600f - 2 * min, sizes[0], 0.01f)
    }

    @Test
    fun `a row too narrow for the minimum everywhere shares out what there is evenly`() {
        val sizes = resolve(min / 2f, listOf(80f, 0f, -80f))
        assertEquals(3, sizes.size)
        assertTrue(sizes.all { kotlin.math.abs(it - min / 2f) < 0.01f }, "sizes were $sizes")
    }

    @Test
    fun `no tracks resolves to no sizes`() {
        assertEquals(emptyList<Float>(), resolve(200f, emptyList()))
    }

    @Test
    fun `dragged sizes round-trip through offsets`() {
        // What the grid does after a drag: store size-minus-packed for every track in the row, then
        // resolve again on the next frame. The layout must not shift under the user.
        val base = 200f
        val dragged = drag(listOf(base, base, base), 1, 35f)
        val resolved = resolve(base, dragged.map { it - base })
        for (i in dragged.indices) assertEquals(dragged[i], resolved[i], 0.01f)
    }

    @Test
    fun `the trailing separator widens the row instead of robbing a neighbour`() {
        val before = listOf(200f, 200f, 200f)
        val after = GroupTrackSizes.dragTrailing(before, 120f, min)
        assertEquals(200f, after[0], 0.01f)
        assertEquals(200f, after[1], 0.01f)
        assertEquals(320f, after[2], 0.01f)
        assertEquals(total(before) + 120f, total(after), 0.01f)
    }

    @Test
    fun `the trailing separator can pull the last column back in, down to the minimum`() {
        val after = GroupTrackSizes.dragTrailing(listOf(200f, 200f), -1000f, min)
        assertEquals(200f, after[0], 0.01f)
        assertEquals(min, after[1], 0.01f)
    }

    @Test
    fun `trailing drags accumulate as an overhang on the last column`() {
        val base = listOf(200f, 200f)
        val once = GroupTrackSizes.dragTrailing(base, 50f, min)
        val twice = GroupTrackSizes.dragTrailing(once, 30f, min)
        assertEquals(280f, twice[1], 0.01f)
    }

    @Test
    fun `dragging the trailing separator of an empty row is a no-op`() {
        assertEquals(emptyList<Float>(), GroupTrackSizes.dragTrailing(emptyList(), 40f, min))
    }
}
