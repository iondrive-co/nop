package iondrive.nop.ui

import iondrive.nop.GroupedStrip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TabGroupsTest {
    private fun group(id: Long, name: String, collapsed: Boolean = false) = TabGroup(id, name, collapsed)
    private fun header(id: Long, name: String, collapsed: Boolean = false) =
        StripItem.Header(group(id, name, collapsed))

    private fun slot(id: String) = StripItem.Slot(id)

    @Test
    fun `nextName fills the lowest free slot in the MR series`() {
        assertEquals("MR1", TabGroups.nextName(emptyList()))
        assertEquals("MR2", TabGroups.nextName(listOf(group(0, "MR1"))))
        // MR2 was closed, so the next group reclaims it rather than climbing to MR4.
        assertEquals("MR2", TabGroups.nextName(listOf(group(0, "MR1"), group(1, "MR3"))))
    }

    @Test
    fun `nextName ignores groups the user renamed`() {
        assertEquals("MR1", TabGroups.nextName(listOf(group(0, "Review"), group(1, "MR-ish"))))
    }

    @Test
    fun `strip lays each group's tabs out after its header`() {
        val groups = listOf(group(1, "MR1"), group(2, "MR2"))
        val items = TabGroups.strip(groups, listOf("a", "b", "c"), mapOf("a" to 1L, "b" to 2L, "c" to 1L))
        assertEquals(
            listOf(header(1, "MR1"), slot("a"), slot("c"), header(2, "MR2"), slot("b")),
            items,
        )
    }

    @Test
    fun `strip keeps an empty group as a bare header`() {
        val groups = listOf(group(1, "MR1"), group(2, "MR2"))
        assertEquals(
            listOf(header(1, "MR1"), slot("a"), header(2, "MR2")),
            TabGroups.strip(groups, listOf("a"), mapOf("a" to 1L)),
        )
    }

    @Test
    fun `strip drops a tab whose group has gone`() {
        assertEquals(
            listOf(header(1, "MR1")),
            TabGroups.strip(listOf(group(1, "MR1")), listOf("orphan"), mapOf("orphan" to 9L)),
        )
    }

    @Test
    fun `membership assigns each tab to the header before it`() {
        val items = listOf(header(1, "MR1"), slot("a"), header(2, "MR2"), slot("b"), slot("c"))
        assertEquals(mapOf("a" to 1L, "b" to 2L, "c" to 2L), TabGroups.membership(items))
    }

    @Test
    fun `groups and tabOrder read a dragged strip back`() {
        val items = listOf(header(2, "MR2"), slot("b"), header(1, "MR1"), slot("a"))
        assertEquals(listOf("MR2", "MR1"), TabGroups.groups(items).map { it.name })
        assertEquals(listOf("b", "a"), TabGroups.tabOrder(items))
    }

    @Test
    fun `canLand refuses a slot ahead of the first header`() {
        // The front of the strip is fine for a header, never for a tab.
        assertTrue(TabGroups.canLand(listOf(header(1, "MR1"), slot("a")), 0))
        assertFalse(TabGroups.canLand(listOf(slot("a"), header(1, "MR1")), 0))
        assertTrue(TabGroups.canLand(listOf(slot("a"), header(1, "MR1")), 1))
        assertFalse(TabGroups.canLand(listOf(header(1, "MR1"), slot("a")), 9))
    }

    @Test
    fun `canLand refuses a slot hidden inside a collapsed group`() {
        val items = listOf(header(1, "MR1", collapsed = true), slot("a"), header(2, "MR2"), slot("b"))
        assertFalse(TabGroups.canLand(items, 1))
        assertTrue(TabGroups.canLand(items, 3))
    }

    @Test
    fun `a dragged tab never lands ahead of the first group`() {
        // The strip's whole extra rule over the project rail: dragging the leftmost tab further left
        // has nowhere to go, because a tab with no header before it would belong to no group.
        val items = listOf(header(1, "MR1"), slot("a"), slot("b"))
        assertNull(
            GroupedStrip.dragStep(
                items = items,
                from = 1,
                asGroup = false,
                delta = -200f,
                canLand = TabGroups::canLand,
            ) { 40 },
        )
    }

    @Test
    fun `a dragged tab crosses into the next group`() {
        val items = listOf(header(1, "MR1"), slot("a"), header(2, "MR2"), slot("b"))
        val moved = GroupedStrip.dragStep(
            items = items,
            from = 1,
            asGroup = false,
            delta = 60f,
            canLand = TabGroups::canLand,
        ) { 40 }!!
        assertEquals(listOf(header(1, "MR1"), header(2, "MR2"), slot("a"), slot("b")), moved.items)
        assertEquals(mapOf("a" to 2L, "b" to 2L), TabGroups.membership(moved.items))
    }

    @Test
    fun `activeAfterRemove falls to the group that takes the closed one's place`() {
        val groups = listOf(group(1, "MR1"), group(2, "MR2"), group(3, "MR3"))
        assertEquals(3L, TabGroups.activeAfterRemove(groups, removed = 2, active = 2))
        // Closing the last one falls back to its neighbour.
        assertEquals(2L, TabGroups.activeAfterRemove(groups, removed = 3, active = 3))
        // Closing a group that wasn't armed leaves the armed one alone.
        assertEquals(1L, TabGroups.activeAfterRemove(groups, removed = 3, active = 1))
        assertEquals(1L, TabGroups.activeAfterRemove(groups, removed = 99, active = 1))
        assertNull(TabGroups.activeAfterRemove(listOf(group(1, "MR1")), removed = 1, active = 1))
    }
}
