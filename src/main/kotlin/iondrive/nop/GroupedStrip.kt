package iondrive.nop

/**
 * One entry of a grouped strip — the shape shared by the project bar along the top of the window
 * and the editor tab bar below it. An entry is either a plain member or a header ([isHeader]), which
 * owns the run of members after it, down to the next header. A [collapsed] header hides the members it owns: they
 * stay in the list but are neither drawn nor dragged on their own, so the group reads — and moves —
 * as a single slot.
 *
 * [T] is the implementing type itself, so the algorithms below hand back lists of the caller's own
 * item type rather than of this interface.
 */
interface GroupedEntry<T : GroupedEntry<T>> {
    val isHeader: Boolean

    /** Header only: whether the members it owns are hidden. Always false for a member. */
    val collapsed: Boolean get() = false

    /** This entry with its group opened. Only ever called on a collapsed header. */
    fun expanded(): T
}

/**
 * One visible slot of a grouped strip, mapped back to its position in the full item list. [start]
 * is the slot's index; [span] is how many list entries it carries when dragged. A slot spans more
 * than one entry only for a collapsed header, which drags as a unit together with its hidden
 * members. The members themselves never appear as their own blocks — they're folded into the
 * header's span, which is exactly why a collapsed group renders (and moves) as a single slot.
 */
data class GroupBlock(val start: Int, val span: Int)

/**
 * The result of one drag step: the reordered [items], and the on-screen distance the dragged slot
 * [travelled] to get there (signed — positive moving towards the end of the strip, negative towards
 * the start). The caller subtracts [travelled] from the drag's running offset so it keeps measuring
 * from the new slot.
 */
data class GroupDragStep<T>(val items: List<T>, val travelled: Int)

/**
 * Pure layout maths for a strip of items grouped under collapsible headers, kept out of the Compose
 * layer so it can be unit-tested directly. Axis-agnostic: the caller supplies each entry's `extent`
 * — a tab's width in both of nop's strips, a row's height were one laid out vertically — and reads
 * the resulting offsets along whichever axis it lays out on.
 */
object GroupedStrip {
    /**
     * The slots the strip actually renders, in order, each pointing back into [items]. Every entry
     * is its own single-span block except a collapsed header, which swallows the run of members
     * after it (up to the next header) into its span so the whole group renders — and drags — as one
     * slot. Members hidden inside a collapsed group never get their own block.
     */
    fun <T : GroupedEntry<T>> visibleBlocks(items: List<T>): List<GroupBlock> =
        blocksBy(items) { it.collapsed }

    /**
     * The same list at *group* granularity: every header swallows the members after it, collapsed or
     * not, and each member ahead of the first header stands alone. Dragging a group's header moves
     * over these, so a group hops a whole neighbouring group in one step — and carries its own
     * members with it — instead of leaving them behind for whichever header ends up before them.
     */
    fun <T : GroupedEntry<T>> groupBlocks(items: List<T>): List<GroupBlock> = blocksBy(items) { true }

    /**
     * Slots covered by the group headed by the item at [index]: the header plus the run of members
     * after it. 1 for a header owning no members, and for a member (which heads nothing), so a
     * caller can read this as "does this slot drag as a group?" by testing for more than 1.
     */
    fun <T : GroupedEntry<T>> groupSpan(items: List<T>, index: Int): Int {
        if (items.getOrNull(index)?.isHeader != true) return 1
        var j = index + 1
        while (j < items.size && !items[j].isHeader) j++
        return j - index
    }

    /**
     * Whether the entry at [index] is drawn at all. A header always is; a member is hidden when the
     * nearest header before it is collapsed. Drag-reorder leans on this to reject a landing slot
     * that would swallow the dragged entry into a closed group, where it would simply disappear.
     */
    fun <T : GroupedEntry<T>> isVisible(items: List<T>, index: Int): Boolean {
        val item = items.getOrNull(index) ?: return false
        if (item.isHeader) return true
        for (i in index - 1 downTo 0) {
            val before = items[i]
            if (before.isHeader) return !before.collapsed
        }
        return true
    }

    /**
     * The slots [items] renders, in order, each pointing back into the list. A header swallows the
     * run of members after it (up to the next header) into its span when [swallows] says so; every
     * other entry is its own single-span block.
     */
    private fun <T : GroupedEntry<T>> blocksBy(items: List<T>, swallows: (T) -> Boolean): List<GroupBlock> {
        val out = mutableListOf<GroupBlock>()
        var i = 0
        while (i < items.size) {
            val item = items[i]
            if (item.isHeader && swallows(item)) {
                var j = i + 1
                while (j < items.size && !items[j].isHeader) j++
                out.add(GroupBlock(i, j - i))
                i = j
            } else {
                out.add(GroupBlock(i, 1))
                i++
            }
        }
        return out
    }

    /**
     * Swaps two adjacent blocks: the [aLen] items at [aStart] with the [bLen] items directly after
     * them. Used by drag-reorder to move a slot past its neighbour a step at a time — a plain swap
     * when both are single slots, and a whole-group hop when either is a collapsed header's block.
     * Returns the list unchanged if the blocks don't fit, so a stale mid-drag index is a no-op.
     */
    fun <T> swapAdjacentBlocks(items: List<T>, aStart: Int, aLen: Int, bLen: Int): List<T> {
        val bStart = aStart + aLen
        if (aStart < 0 || aLen <= 0 || bLen <= 0 || bStart + bLen > items.size) return items
        return buildList {
            addAll(items.subList(0, aStart))
            addAll(items.subList(bStart, bStart + bLen))
            addAll(items.subList(aStart, bStart))
            addAll(items.subList(bStart + bLen, items.size))
        }
    }

    /**
     * One step of a drag-reorder. The slot at [from] has been dragged [delta] px from where it
     * settled (positive towards the end of the strip); each neighbour whose midpoint it passes counts
     * as crossed, and the slot moves to the furthest crossed position that [canLand] accepts. Returns
     * null while it hasn't travelled far enough, when there's no such position left in that
     * direction, or when [from] isn't a block start (a stale mid-drag index).
     *
     * By default [canLand] accepts anywhere the entry would still be *drawn*, which is what carries a
     * drag over a closed group rather than into it: the position after a collapsed header's members
     * belongs to that group, so landing there would make the dragged entry vanish. Crossing it needs
     * one more neighbour's worth of travel, to the far side of the group (or, moving forward, into the
     * next open group) — and if no drawn position exists beyond it, the entry stays put instead of
     * dropping out of sight. Holding still over the group is the way in; see [collapsedUnderDrag].
     * A caller with further rules about where an entry may sit tightens [canLand] instead.
     *
     * [asGroup] picks the granularity, and the caller pins it for the whole drag: a header moves with
     * its members and steps over whole neighbouring groups, while a bare entry (a member, or a header
     * owning nothing) steps one visible slot at a time so it can be dropped inside an open group.
     * [extent] gives the measured size of an entry along the strip's axis.
     */
    fun <T : GroupedEntry<T>> dragStep(
        items: List<T>,
        from: Int,
        asGroup: Boolean,
        delta: Float,
        canLand: (List<T>, Int) -> Boolean = { list, index -> isVisible(list, index) },
        extent: (T) -> Int,
    ): GroupDragStep<T>? {
        val blocks = if (asGroup) groupBlocks(items) else visibleBlocks(items)
        val at = blocks.indexOfFirst { it.start == from }
        if (at < 0 || delta == 0f) return null
        val block = blocks[at]
        val forward = delta > 0f
        val travel = if (forward) delta else -delta
        // Neighbours crossed so far: their on-screen extent, and the list entries they cover.
        var crossed = 0
        var span = 0
        var best: GroupDragStep<T>? = null
        var i = at
        while (true) {
            val next = blocks.getOrNull(if (forward) ++i else --i) ?: break
            val size = blockExtent(items, next, extent)
            // A neighbour we haven't measured yet (freshly revealed, or not laid out) has no midpoint
            // to pass, and nothing beyond it can be reached without crossing it.
            if (size <= 0) break
            if (travel <= crossed + size / 2f) break
            crossed += size
            span += next.span
            val moved =
                if (forward) swapAdjacentBlocks(items, block.start, block.span, span)
                else swapAdjacentBlocks(items, next.start, span, block.span)
            val landed = if (forward) block.start + span else next.start
            if (canLand(moved, landed)) best = GroupDragStep(moved, if (forward) crossed else -crossed)
        }
        return best
    }

    /**
     * The collapsed header the dragged entry at [from] is being held over, or null if it isn't over
     * one. The entry's leading edge — its trailing edge moving forward, its leading edge moving back
     * — has travelled [delta] px into the slots on that side, and whichever it currently covers is
     * the group that hover-to-open should expand. An entry dragged clear past the end keeps the last
     * closed group it covered, so parking beyond a run of them still opens one. Bare entries only: a
     * header can't be nested inside another group, so the caller doesn't ask on its behalf.
     */
    fun <T : GroupedEntry<T>> collapsedUnderDrag(
        items: List<T>,
        from: Int,
        delta: Float,
        extent: (T) -> Int,
    ): Int? {
        val blocks = visibleBlocks(items)
        val at = blocks.indexOfFirst { it.start == from }
        if (at < 0 || delta == 0f) return null
        val forward = delta > 0f
        val travel = if (forward) delta else -delta
        var passed = 0
        var last: Int? = null
        var i = at
        while (true) {
            val next = blocks.getOrNull(if (forward) ++i else --i) ?: break
            val size = blockExtent(items, next, extent)
            if (size <= 0) break
            val head = items[next.start]
            val collapsed = head.isHeader && head.collapsed
            if (travel <= passed + size) return if (collapsed) next.start else null
            if (collapsed) last = next.start
            passed += size
        }
        return last
    }

    /**
     * Opens the collapsed header at [header] mid-drag and lifts the dragged entry at [from] to the
     * front of the group it owns, so the entry settles where the pointer is holding instead of being
     * shoved along the strip by the members that just appeared. This is how an entry gets *into* a
     * closed group, which a drag over it can't do. [GroupDragStep.travelled] is the distance the
     * entry moved on screen for the caller to take off the drag's running offset; everything ahead of
     * its new slot was already drawn, so measured extents cover it. Returns null unless [header] is a
     * collapsed header and [from] is an entry that can move into one (a member, or a header owning
     * nothing).
     */
    fun <T : GroupedEntry<T>> expandUnderDrag(
        items: List<T>,
        from: Int,
        header: Int,
        extent: (T) -> Int,
    ): GroupDragStep<T>? {
        val head = items.getOrNull(header)?.takeIf { it.isHeader } ?: return null
        val dragged = items.getOrNull(from) ?: return null
        if (!head.collapsed || from == header || groupSpan(items, from) > 1) return null
        val before = visibleStart(items, from, extent)
        val out = items.toMutableList()
        out.removeAt(from)
        // Indices after the removal: everything past the dragged entry shifted down by one.
        val open = if (from < header) header - 1 else header
        out[open] = head.expanded()
        out.add(open + 1, dragged)
        return GroupDragStep(out, visibleStart(out, open + 1, extent) - before)
    }

    /** How far along the strip the entry at [index] sits: the extents of the drawn entries ahead of it. */
    private fun <T : GroupedEntry<T>> visibleStart(items: List<T>, index: Int, extent: (T) -> Int): Int =
        (0 until index).sumOf { if (isVisible(items, it)) extent(items[it]) else 0 }

    /**
     * How much room [block] takes on screen: its head entry plus the members of an expanded group. A
     * collapsed group is just its header, since its members aren't drawn — so the distance a drag has
     * to cover to pass a neighbour always matches what the user sees.
     */
    private fun <T : GroupedEntry<T>> blockExtent(items: List<T>, block: GroupBlock, extent: (T) -> Int): Int {
        val head = items.getOrNull(block.start)
        val entries = if (head != null && head.isHeader && head.collapsed) 1 else block.span
        return (block.start until block.start + entries).sumOf { extent(items[it]) }
    }
}
