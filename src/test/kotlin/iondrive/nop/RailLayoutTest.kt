package iondrive.nop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class RailLayoutTest {
    private fun proj(s: String) = RailItem.Project(Paths.get(s))
    private fun sep(name: String, id: Long = 0) = RailItem.Separator(name, id)
    private fun sepC(name: String, id: Long = 0) = RailItem.Separator(name, id, collapsed = true)

    @Test
    fun `projects drops separators and preserves order`() {
        val items = listOf(proj("/p/a"), sep("Work"), proj("/p/b"), proj("/p/c"))
        assertEquals(listOf(Paths.get("/p/a"), Paths.get("/p/b"), Paths.get("/p/c")), RailLayout.projects(items))
    }

    @Test
    fun `move shifts an item forward`() {
        val items = listOf(proj("/p/a"), proj("/p/b"), proj("/p/c"))
        assertEquals(listOf(proj("/p/b"), proj("/p/c"), proj("/p/a")), RailLayout.move(items, 0, 2))
    }

    @Test
    fun `move shifts an item backward`() {
        val items = listOf(proj("/p/a"), proj("/p/b"), proj("/p/c"))
        assertEquals(listOf(proj("/p/c"), proj("/p/a"), proj("/p/b")), RailLayout.move(items, 2, 0))
    }

    @Test
    fun `move is a no-op for equal or out-of-range indices`() {
        val items = listOf(proj("/p/a"), proj("/p/b"))
        assertEquals(items, RailLayout.move(items, 1, 1))
        assertEquals(items, RailLayout.move(items, 0, 5))
        assertEquals(items, RailLayout.move(items, -1, 0))
    }

    @Test
    fun `encode and decode round-trip a project`() {
        val item = proj("/p/a")
        assertEquals(item, RailLayout.decode(RailLayout.encode(item), separatorId = 0))
    }

    @Test
    fun `encode and decode round-trip a separator by name`() {
        val decoded = RailLayout.decode(RailLayout.encode(sep("Work projects")), separatorId = 7)
        assertEquals(sep("Work projects"), decoded)
        assertEquals(7L, (decoded as RailItem.Separator).id)
    }

    @Test
    fun `separator equality ignores id`() {
        assertEquals(sep("Work", 1), sep("Work", 99))
    }

    @Test
    fun `separator equality distinguishes collapsed state`() {
        assertNotEquals(sep("Work"), sepC("Work"))
    }

    @Test
    fun `encode and decode round-trip a collapsed separator`() {
        val decoded = RailLayout.decode(RailLayout.encode(sepC("Work", 3)), separatorId = 5)
        assertEquals(sepC("Work"), decoded)
        assertEquals(true, (decoded as RailItem.Separator).collapsed)
    }

    @Test
    fun `decode treats a legacy sep prefix as expanded`() {
        val decoded = RailLayout.decode("sep:Work", separatorId = 0) as RailItem.Separator
        assertEquals(false, decoded.collapsed)
    }

    @Test
    fun `visibleBlocks gives every row its own single span when nothing is collapsed`() {
        val items = listOf(proj("/a"), sep("Work"), proj("/b"), proj("/c"))
        assertEquals(
            listOf(RailBlock(0, 1), RailBlock(1, 1), RailBlock(2, 1), RailBlock(3, 1)),
            RailLayout.visibleBlocks(items),
        )
    }

    @Test
    fun `visibleBlocks folds a collapsed group's tabs into the separator's span`() {
        val items = listOf(proj("/a"), sepC("Work"), proj("/b"), proj("/c"), sep("Other"), proj("/d"))
        assertEquals(
            // /a on its own; "Work" swallows /b and /c (span 3); "Other" open with /d following.
            listOf(RailBlock(0, 1), RailBlock(1, 3), RailBlock(4, 1), RailBlock(5, 1)),
            RailLayout.visibleBlocks(items),
        )
    }

    @Test
    fun `visibleBlocks handles a collapsed separator with no tabs and one at the end`() {
        val items = listOf(sepC("Empty"), sep("Work"), proj("/a"), sepC("Tail"))
        assertEquals(
            listOf(RailBlock(0, 1), RailBlock(1, 1), RailBlock(2, 1), RailBlock(3, 1)),
            RailLayout.visibleBlocks(items),
        )
    }

    @Test
    fun `groupBlocks folds an expanded group's tabs into the separator's span`() {
        val items = listOf(proj("/a"), sep("Work"), proj("/b"), proj("/c"), sep("Other"), proj("/d"))
        assertEquals(
            // /a stands alone above the first separator; "Work" covers /b and /c; "Other" covers /d.
            listOf(RailBlock(0, 1), RailBlock(1, 3), RailBlock(4, 2)),
            RailLayout.groupBlocks(items),
        )
    }

    @Test
    fun `groupBlocks gives a separator heading no tabs a span of one`() {
        val items = listOf(sep("Empty"), sepC("Work"), proj("/a"))
        assertEquals(listOf(RailBlock(0, 1), RailBlock(1, 2)), RailLayout.groupBlocks(items))
    }

    @Test
    fun `groupSpan counts a separator's tabs and is one for anything else`() {
        val items = listOf(proj("/a"), sep("Work"), proj("/b"), proj("/c"), sep("Tail"))
        assertEquals(3, RailLayout.groupSpan(items, 1))
        assertEquals(1, RailLayout.groupSpan(items, 4)) // separator heading no tabs
        assertEquals(1, RailLayout.groupSpan(items, 0)) // a project heads nothing
        assertEquals(1, RailLayout.groupSpan(items, 9)) // out of range
    }

    // Drag steps below measure every row at ROW px, so a group of n rows stands n * ROW tall and has
    // to be travelled halfway for the swap to commit.
    private val ROW = 50
    private fun step(items: List<RailItem>, from: Int, asGroup: Boolean, delta: Float) =
        RailLayout.dragStep(items, from, asGroup, delta) { ROW }

    private fun under(items: List<RailItem>, from: Int, delta: Float) =
        RailLayout.collapsedUnderDrag(items, from, delta) { ROW }

    @Test
    fun `dragStep moves a group above the previous group with its tabs`() {
        // The reported bug: dragging the last group's header up used to move the bare label, leaving
        // its tabs behind to be swallowed by whichever separator ended up above them.
        val items = listOf(sep("A", 1), proj("/a"), sep("B", 2), proj("/b"), sep("C", 3), proj("/c"))
        val moved = step(items, from = 4, asGroup = true, delta = -60f)!!
        assertEquals(
            listOf(sep("A", 1), proj("/a"), sep("C", 3), proj("/c"), sep("B", 2), proj("/b")),
            moved.items,
        )
        // Group B is two rows tall, so the drag consumed its full height moving up past it.
        assertEquals(-2 * ROW, moved.travelled)
    }

    @Test
    fun `dragStep moves a group below the next group with its tabs`() {
        val items = listOf(sep("B", 1), proj("/b1"), proj("/b2"), sep("C", 2), proj("/c"))
        val moved = step(items, from = 0, asGroup = true, delta = 60f)!!
        assertEquals(
            listOf(sep("C", 2), proj("/c"), sep("B", 1), proj("/b1"), proj("/b2")),
            moved.items,
        )
        assertEquals(2 * ROW, moved.travelled)
    }

    @Test
    fun `dragStep waits until a group has been travelled halfway`() {
        val items = listOf(sep("A", 1), proj("/a1"), proj("/a2"), sep("B", 2), proj("/b"))
        // Group A is three rows tall (150px): 70px up isn't yet past its midpoint, 80px is.
        assertNull(step(items, from = 3, asGroup = true, delta = -70f))
        assertNotNull(step(items, from = 3, asGroup = true, delta = -80f))
    }

    @Test
    fun `dragStep measures a collapsed neighbour by its separator row alone`() {
        // "Work" hides two tabs, so it's one row on screen — half of ROW is all the travel it takes
        // to hop it, even though the block that moves is three entries long.
        val items = listOf(sepC("Work", 1), proj("/b"), proj("/c"), sep("New", 2))
        val moved = step(items, from = 3, asGroup = false, delta = -30f)!!
        assertEquals(listOf(sep("New", 2), sepC("Work", 1), proj("/b"), proj("/c")), moved.items)
        assertEquals(-ROW, moved.travelled)
    }

    @Test
    fun `dragStep steps a bare separator one row at a time so it can split a group`() {
        // A newly added separator heads no tabs, so it drags as a plain row: one step up drops it
        // between the tabs of the group above, splitting it.
        val items = listOf(sep("Work", 1), proj("/a"), proj("/b"), sep("New", 2))
        val moved = step(items, from = 3, asGroup = false, delta = -30f)!!
        assertEquals(listOf(sep("Work", 1), proj("/a"), sep("New", 2), proj("/b")), moved.items)
    }

    @Test
    fun `dragStep moves a project across a group boundary a row at a time`() {
        val items = listOf(sep("A", 1), proj("/a"), sep("B", 2), proj("/b"))
        val moved = step(items, from = 3, asGroup = false, delta = -30f)!!
        assertEquals(listOf(sep("A", 1), proj("/a"), proj("/b"), sep("B", 2)), moved.items)
    }

    @Test
    fun `dragStep carries a project over a closed group above rather than into it`() {
        // The reported bug: one step up used to swap /b1 with its own separator, parking it after the
        // closed group's tabs — i.e. inside it — where it stopped being drawn at all. Both rows above
        // have to be crossed to reach the next slot /b1 still shows in, at the top of the rail.
        val items = listOf(sepC("A", 1), proj("/a1"), proj("/a2"), sep("B", 2), proj("/b1"), proj("/b2"))
        assertNull(step(items, from = 4, asGroup = false, delta = -30f)) // past B's row, nowhere to land
        val moved = step(items, from = 4, asGroup = false, delta = -80f)!!
        assertEquals(
            listOf(proj("/b1"), sepC("A", 1), proj("/a1"), proj("/a2"), sep("B", 2), proj("/b2")),
            moved.items,
        )
        // Closed A is one row on screen despite its two hidden tabs, so B's row plus A's is 2 * ROW.
        assertEquals(-2 * ROW, moved.travelled)
    }

    @Test
    fun `dragStep carries a project over a closed group below into the next open one`() {
        val items = listOf(proj("/a"), sepC("W", 1), proj("/w"), sep("O", 2), proj("/o"))
        val moved = step(items, from = 0, asGroup = false, delta = 80f)!!
        assertEquals(
            // /a lands as the first tab of the open group, the nearest slot below where it shows.
            listOf(sepC("W", 1), proj("/w"), sep("O", 2), proj("/a"), proj("/o")),
            moved.items,
        )
        assertEquals(2 * ROW, moved.travelled)
    }

    @Test
    fun `dragStep leaves a project put when only closed groups lie beyond it`() {
        // Nothing below is drawn — every slot is inside "W" — so the row holds its place instead of
        // disappearing into the group. Hover-to-open is the way in; see expandUnderDrag.
        val items = listOf(proj("/a"), sepC("W", 1), proj("/w1"), proj("/w2"))
        assertNull(step(items, from = 0, asGroup = false, delta = 30f))
        assertNull(step(items, from = 0, asGroup = false, delta = 500f))
    }

    @Test
    fun `dragStep hops a bare separator over a closed group instead of splitting it`() {
        // Slots inside a closed group aren't drop targets even for a separator, which would otherwise
        // take two visually identical steps to cross one that hides two tabs.
        val items = listOf(sepC("W", 1), proj("/w1"), proj("/w2"), sep("New", 2))
        val moved = step(items, from = 3, asGroup = false, delta = -30f)!!
        assertEquals(listOf(sep("New", 2), sepC("W", 1), proj("/w1"), proj("/w2")), moved.items)
    }

    @Test
    fun `dragStep moves a group over a closed group whole`() {
        val items = listOf(sepC("A", 1), proj("/a"), sep("B", 2), proj("/b"))
        val moved = step(items, from = 2, asGroup = true, delta = -30f)!!
        assertEquals(listOf(sep("B", 2), proj("/b"), sepC("A", 1), proj("/a")), moved.items)
        assertEquals(-ROW, moved.travelled)
    }

    @Test
    fun `collapsedUnderDrag names the closed group the row is held over`() {
        val items = listOf(sepC("A", 1), proj("/a"), sepC("B", 2), proj("/b"), sep("C", 3), proj("/c"))
        // Dragging /c up: C's row covers the first ROW of travel, then closed B, then closed A.
        assertNull(under(items, from = 5, delta = -30f)) // still over C's own separator
        assertEquals(2, under(items, from = 5, delta = -70f)) // over B
        assertEquals(0, under(items, from = 5, delta = -120f)) // over A
        // Dragged clear off the top it keeps A, so parking above the lot still opens the last one.
        assertEquals(0, under(items, from = 5, delta = -900f))
    }

    @Test
    fun `collapsedUnderDrag ignores rows that are already open`() {
        val items = listOf(sep("A", 1), proj("/a"), proj("/b"))
        assertNull(under(items, from = 2, delta = -30f))
        assertNull(under(items, from = 2, delta = 0f))
        assertNull(under(items, from = 1, delta = 30f)) // nothing below
    }

    @Test
    fun `expandUnderDrag opens a group above and lifts the dragged row into it`() {
        val items = listOf(sepC("A", 1), proj("/a1"), proj("/a2"), sep("B", 2), proj("/b"))
        val moved = RailLayout.expandUnderDrag(items, from = 4, sep = 0) { ROW }!!
        assertEquals(
            listOf(sep("A", 1), proj("/b"), proj("/a1"), proj("/a2"), sep("B", 2)),
            moved.items,
        )
        // /b sat below the two separator rows and now sits below one, so it rose a single row — the
        // tabs that just appeared are beneath it and don't shove it anywhere.
        assertEquals(-ROW, moved.travelled)
    }

    @Test
    fun `expandUnderDrag opens a group below and lifts the dragged row into it`() {
        val items = listOf(proj("/a"), sepC("W", 1), proj("/w"))
        val moved = RailLayout.expandUnderDrag(items, from = 0, sep = 1) { ROW }!!
        assertEquals(listOf(sep("W", 1), proj("/a"), proj("/w")), moved.items)
        // /a dropped past the separator row it was held over.
        assertEquals(ROW, moved.travelled)
    }

    @Test
    fun `expandUnderDrag refuses a group that is already open, a non-separator, and a group header`() {
        val items = listOf(sep("A", 1), proj("/a"), sepC("W", 2), proj("/w"), sep("Bare", 3))
        assertNull(RailLayout.expandUnderDrag(items, from = 3, sep = 0) { ROW }) // "A" isn't closed
        assertNull(RailLayout.expandUnderDrag(items, from = 3, sep = 1) { ROW }) // /a isn't a separator
        assertNull(RailLayout.expandUnderDrag(items, from = 9, sep = 2) { ROW }) // stale row index
        assertNull(RailLayout.expandUnderDrag(items, from = 0, sep = 2) { ROW }) // "A" heads tabs
        // A separator heading no tabs is a bare row, so it can be dropped into a group like any tab.
        assertNotNull(RailLayout.expandUnderDrag(items, from = 4, sep = 2) { ROW })
    }

    @Test
    fun `isVisible hides only the tabs inside a closed group`() {
        val items = listOf(proj("/a"), sepC("W", 1), proj("/w"), sep("O", 2), proj("/o"))
        assertEquals(true, RailLayout.isVisible(items, 0)) // above every separator
        assertEquals(true, RailLayout.isVisible(items, 1)) // separators always show
        assertEquals(false, RailLayout.isVisible(items, 2)) // inside closed "W"
        assertEquals(true, RailLayout.isVisible(items, 4)) // inside open "O"
        assertEquals(false, RailLayout.isVisible(items, 9)) // out of range
    }

    @Test
    fun `dragStep is a no-op at the ends and for a stale index`() {
        val items = listOf(sep("A", 1), proj("/a"), proj("/b"))
        assertNull(step(items, from = 0, asGroup = true, delta = -80f)) // already at the top
        assertNull(step(items, from = 2, asGroup = false, delta = 80f)) // already at the bottom
        assertNull(step(items, from = -1, asGroup = false, delta = 80f))
        assertNull(step(items, from = 1, asGroup = true, delta = 0f))
    }

    @Test
    fun `dragStep ignores a row it hasn't measured yet`() {
        val items = listOf(proj("/a"), proj("/b"))
        assertNull(RailLayout.dragStep(items, from = 1, asGroup = false, delta = -80f) { 0 })
    }

    @Test
    fun `swapAdjacentBlocks swaps two single rows`() {
        val items = listOf(proj("/a"), proj("/b"), proj("/c"))
        assertEquals(
            listOf(proj("/b"), proj("/a"), proj("/c")),
            RailLayout.swapAdjacentBlocks(items, aStart = 0, aLen = 1, bLen = 1),
        )
    }

    @Test
    fun `swapAdjacentBlocks moves a collapsed group past a neighbour as a unit`() {
        // "Work" (collapsed, span 3: sep + /b + /c) hops below the project /a beneath it.
        val items = listOf(sepC("Work"), proj("/b"), proj("/c"), proj("/a"))
        assertEquals(
            listOf(proj("/a"), sepC("Work"), proj("/b"), proj("/c")),
            RailLayout.swapAdjacentBlocks(items, aStart = 0, aLen = 3, bLen = 1),
        )
    }

    @Test
    fun `swapAdjacentBlocks is a no-op when the blocks don't fit`() {
        val items = listOf(proj("/a"), proj("/b"))
        assertEquals(items, RailLayout.swapAdjacentBlocks(items, aStart = 1, aLen = 1, bLen = 1))
        assertEquals(items, RailLayout.swapAdjacentBlocks(items, aStart = 0, aLen = 0, bLen = 1))
        assertEquals(items, RailLayout.swapAdjacentBlocks(items, aStart = -1, aLen = 1, bLen = 1))
    }

    @Test
    fun `encode flattens newlines in a separator name`() {
        val encoded = RailLayout.encode(sep("line1\nline2"))
        assertEquals("sep:line1 line2", encoded)
    }

    @Test
    fun `decode rejects an unknown prefix`() {
        assertNull(RailLayout.decode("garbage", separatorId = 0))
    }
}
