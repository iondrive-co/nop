package iondrive.nop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Drag-reorder of the project bar's flat strip of tabs. Widths are the measured tab widths in px. */
class StripDragTest {
    private val even = listOf(100, 100, 100, 100)

    @Test
    fun `a tab that hasn't passed its neighbour's midpoint stays put`() {
        assertNull(StripDrag.step(even, from = 1, delta = 49f))
        assertNull(StripDrag.step(even, from = 1, delta = -49f))
        assertNull(StripDrag.step(even, from = 1, delta = 0f))
    }

    @Test
    fun `passing the midpoint swaps with that neighbour`() {
        assertEquals(StripDragStep(to = 2, travelled = 100), StripDrag.step(even, from = 1, delta = 51f))
        assertEquals(StripDragStep(to = 0, travelled = -100), StripDrag.step(even, from = 1, delta = -51f))
    }

    @Test
    fun `a long drag lands past every neighbour it crossed`() {
        assertEquals(StripDragStep(to = 3, travelled = 300), StripDrag.step(even, from = 0, delta = 260f))
    }

    @Test
    fun `a drag past the end of the strip stops at the last tab`() {
        assertEquals(StripDragStep(to = 3, travelled = 300), StripDrag.step(even, from = 0, delta = 5000f))
        assertEquals(StripDragStep(to = 0, travelled = -300), StripDrag.step(even, from = 3, delta = -5000f))
    }

    @Test
    fun `neighbours are crossed at their own widths, not an average`() {
        val mixed = listOf(60, 300, 60)
        // Half of the wide middle tab is 150px, so 120px of travel isn't yet enough to pass it.
        assertNull(StripDrag.step(mixed, from = 0, delta = 120f))
        assertEquals(StripDragStep(to = 1, travelled = 300), StripDrag.step(mixed, from = 0, delta = 160f))
    }

    @Test
    fun `a neighbour that hasn't been laid out yet stops the walk`() {
        // Nothing beyond an unmeasured tab can be reached without crossing it, and it has no
        // midpoint to cross.
        assertNull(StripDrag.step(listOf(100, 0, 100), from = 0, delta = 500f))
    }

    @Test
    fun `a stale index mid-drag is a no-op`() {
        assertNull(StripDrag.step(even, from = 9, delta = 200f))
        assertNull(StripDrag.step(emptyList(), from = 0, delta = 200f))
    }
}
