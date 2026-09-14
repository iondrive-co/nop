package iondrive.nop

/** Where a dragged tab lands, and the on-screen distance it [travelled] to get there (signed). */
data class StripDragStep(val to: Int, val travelled: Int)

/**
 * Drag-reorder maths for a plain, ungrouped strip of tabs — the project bar along the top of the
 * window. Kept out of the Compose layer so it can be unit-tested directly.
 *
 * The grouped strips (the editor tab bar) need [GroupedStrip] instead, which has the same shape but
 * also reckons with headers that swallow the tabs after them; a bar of nothing but tabs doesn't, and
 * paying for that machinery here would mean wrapping every path in a header-less entry to say so.
 */
object StripDrag {
    /**
     * One step of a drag. The tab at [from] has been dragged [delta] px from where it settled
     * (positive towards the end of the strip); every neighbour whose midpoint it has passed counts as
     * crossed, and the tab moves to the furthest one. [sizes] gives each tab's measured width, and a
     * neighbour not yet laid out (width 0) stops the walk, since there is no midpoint to pass and
     * nothing beyond it can be reached without crossing it.
     *
     * Returns null while the drag hasn't travelled far enough to swap anything, or when [from] is
     * out of range — a stale index mid-drag is then a no-op rather than a jump.
     */
    fun step(sizes: List<Int>, from: Int, delta: Float): StripDragStep? {
        if (from !in sizes.indices || delta == 0f) return null
        val forward = delta > 0f
        val travel = if (forward) delta else -delta
        var crossed = 0
        var best: StripDragStep? = null
        var i = from
        while (true) {
            i = if (forward) i + 1 else i - 1
            val size = sizes.getOrNull(i) ?: break
            if (size <= 0) break
            if (travel <= crossed + size / 2f) break
            crossed += size
            best = StripDragStep(i, if (forward) crossed else -crossed)
        }
        return best
    }
}
