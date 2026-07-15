package iondrive.nop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
