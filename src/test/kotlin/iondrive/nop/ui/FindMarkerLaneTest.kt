package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The stripes Ctrl+F paints down the scrollbar lane. What matters is that every hit is placed where
 * the file says it is, that none of them escapes the track, and that a huge match count doesn't turn
 * into a huge number of draw calls.
 */
class FindMarkerLaneTest {
    @Test
    fun `no hits means no bars`() {
        assertEquals(emptyList<MarkerBar>(), markerBars(emptyList(), laneHeight = 100f, markerHeight = 3f))
    }

    @Test
    fun `a lane with no height yet draws nothing`() {
        // First composition, before layout: dividing into a zero-height lane must not produce bars.
        assertEquals(emptyList<MarkerBar>(), markerBars(listOf(0.5f), laneHeight = 0f, markerHeight = 3f))
    }

    @Test
    fun `a hit is placed at its share of the lane`() {
        val bars = markerBars(listOf(0f, 0.25f, 0.5f), laneHeight = 200f, markerHeight = 4f)
        assertEquals(listOf(0f, 50f, 100f), bars.map { it.top })
        assertTrue(bars.all { it.height == 4f })
    }

    @Test
    fun `the last hit in a file stays inside the track`() {
        // A hit at the very bottom would otherwise be drawn half off the end of the lane.
        val bars = markerBars(listOf(1f), laneHeight = 100f, markerHeight = 3f)
        assertEquals(listOf(MarkerBar(97f, 3f)), bars)
    }

    @Test
    fun `out-of-range positions are clamped rather than dropped`() {
        val bars = markerBars(listOf(-0.5f, 1.5f), laneHeight = 100f, markerHeight = 2f)
        assertEquals(listOf(0f, 98f), bars.map { it.top })
    }

    @Test
    fun `a marker taller than the lane is cut down to it`() {
        val bars = markerBars(listOf(0.5f), laneHeight = 5f, markerHeight = 20f)
        assertEquals(listOf(MarkerBar(0f, 5f)), bars)
    }

    @Test
    fun `hits on the same pixel row collapse to one bar`() {
        // 500 hits packed into the top of a long file: they land on the same row, so one rect is
        // drawn instead of 500 identical ones.
        val fractions = List(500) { it * 0.000001f }
        val bars = markerBars(fractions, laneHeight = 400f, markerHeight = 3f)
        assertEquals(1, bars.size)
    }

    @Test
    fun `hits a pixel apart are all kept`() {
        val fractions = listOf(0f, 0.01f, 0.02f, 0.03f)
        val bars = markerBars(fractions, laneHeight = 100f, markerHeight = 3f)
        assertEquals(listOf(0f, 1f, 2f, 3f), bars.map { it.top })
    }

    @Test
    fun `spread-out hits in a long file each get a bar`() {
        // 5000 hits (the find cap) spread evenly down a 600px lane: capped by the pixel rows
        // available, not by the match count.
        val fractions = List(5000) { it / 5000f }
        val bars = markerBars(fractions, laneHeight = 600f, markerHeight = 3f)
        assertTrue(bars.size in 500..600, "expected roughly one bar per pixel row, got ${bars.size}")
        assertTrue(bars.all { it.top >= 0f && it.top + it.height <= 600f }, "every bar must sit in the lane")
    }
}
