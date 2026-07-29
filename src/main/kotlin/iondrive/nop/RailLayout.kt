package iondrive.nop

import java.nio.file.Path
import java.nio.file.Paths

/**
 * One row in the left project rail. Either a [Project] tab (a directory the workspace can switch
 * to) or a [Separator] — a standalone, user-named bold label that heads the group of tabs below
 * it (down to the next separator). A separator is a lightweight divider the user can drag anywhere
 * in the rail; it can also be [collapsed], which hides the tabs in its group until it's expanded.
 */
sealed interface RailItem {
    data class Project(val path: Path) : RailItem

    /**
     * [id] gives the separator a stable identity for drag-reorder keys and rename/remove, since two
     * separators may share a name. It is runtime-only — not persisted — so it's left out of equality
     * (used by snapshot diffing and list comparisons); [collapsed] *is* persisted and part of
     * equality, so toggling it registers as a change that gets saved.
     */
    class Separator(val name: String, val id: Long, val collapsed: Boolean = false) : RailItem {
        override fun equals(other: Any?): Boolean =
            other is Separator && other.name == name && other.collapsed == collapsed
        override fun hashCode(): Int = 31 * name.hashCode() + collapsed.hashCode()
        override fun toString(): String = "Separator($name, collapsed=$collapsed)"
    }
}

/**
 * One visible row of the rail, mapped back to its position in the full [RailItem] list. [start] is
 * the row's index; [span] is how many list entries it carries when dragged. A row spans more than
 * one entry only for a collapsed separator, which drags as a unit together with its hidden group
 * members. The members themselves never appear as their own blocks — they're folded into the
 * separator's span, which is exactly why a collapsed group renders (and moves) as a single row.
 */
data class RailBlock(val start: Int, val span: Int)

/**
 * The result of one drag step: the reordered [items], and the on-screen distance the dragged row
 * [travelled] to get there (signed — positive moving down the rail, negative moving up). The caller
 * subtracts [travelled] from the drag's running offset so it keeps measuring from the new slot.
 */
data class RailDragStep(val items: List<RailItem>, val travelled: Int)

/**
 * Pure helpers for the rail's ordered list and its on-disk encoding, kept out of the Compose/IO
 * layers so they can be unit-tested directly.
 */
object RailLayout {
    private const val PROJECT_PREFIX = "project:"
    private const val SEPARATOR_PREFIX = "sep:"
    // A separate prefix for a collapsed separator keeps the format backward-compatible: rows written
    // by a pre-collapse build all use "sep:" and decode as expanded, which is the right default.
    private const val SEPARATOR_COLLAPSED_PREFIX = "sepc:"

    /** The project paths in rail order, dropping separators. */
    fun projects(items: List<RailItem>): List<Path> =
        items.filterIsInstance<RailItem.Project>().map { it.path }

    /**
     * Moves the item at [from] to index [to], shifting the rest. Out-of-range indices (or from==to)
     * return the list unchanged, so callers can wire it straight to a drag handler without guarding.
     */
    fun move(items: List<RailItem>, from: Int, to: Int): List<RailItem> {
        if (from == to || from !in items.indices || to !in items.indices) return items
        val out = items.toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }

    /**
     * The rows the rail actually renders, in order, each pointing back into [items]. Every entry is
     * its own single-span block except a collapsed separator, which swallows the run of projects
     * beneath it (up to the next separator) into its span so the whole group renders — and drags —
     * as one row. Projects hidden inside a collapsed group never get their own block.
     */
    fun visibleBlocks(items: List<RailItem>): List<RailBlock> = blocksBy(items) { it.collapsed }

    /**
     * The same list at *group* granularity: every separator swallows the tabs beneath it, collapsed
     * or not, and each project above the first separator stands alone. Dragging a group's header
     * moves over these, so a group hops a whole neighbouring group in one step — and carries its own
     * tabs with it — instead of leaving them behind for whichever separator ends up above them.
     */
    fun groupBlocks(items: List<RailItem>): List<RailBlock> = blocksBy(items) { true }

    /**
     * Rows covered by the group headed by the item at [index]: the separator plus the run of
     * projects below it. 1 for a separator heading no tabs, and for a project (which heads nothing),
     * so a caller can read this as "does this row drag as a group?" by testing for more than 1.
     */
    fun groupSpan(items: List<RailItem>, index: Int): Int {
        if (items.getOrNull(index) !is RailItem.Separator) return 1
        var j = index + 1
        while (j < items.size && items[j] is RailItem.Project) j++
        return j - index
    }

    /**
     * The rows [items] renders, in order, each pointing back into the list. A separator swallows the
     * run of projects beneath it (up to the next separator) into its span when [swallows] says so;
     * every other entry is its own single-span block.
     */
    private fun blocksBy(items: List<RailItem>, swallows: (RailItem.Separator) -> Boolean): List<RailBlock> {
        val out = mutableListOf<RailBlock>()
        var i = 0
        while (i < items.size) {
            val item = items[i]
            if (item is RailItem.Separator && swallows(item)) {
                var j = i + 1
                while (j < items.size && items[j] is RailItem.Project) j++
                out.add(RailBlock(i, j - i))
                i = j
            } else {
                out.add(RailBlock(i, 1))
                i++
            }
        }
        return out
    }

    /**
     * Swaps two adjacent blocks: the [aLen] items at [aStart] with the [bLen] items directly after
     * them. Used by drag-reorder to move a row past its neighbour a step at a time — a plain swap
     * when both are single rows, and a whole-group hop when either is a collapsed separator's block.
     * Returns the list unchanged if the blocks don't fit, so a stale mid-drag index is a no-op.
     */
    fun swapAdjacentBlocks(items: List<RailItem>, aStart: Int, aLen: Int, bLen: Int): List<RailItem> {
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
     * One step of a drag-reorder. The row at [from] has been dragged [delta] px from its settled slot
     * (positive downwards); once that passes half the height of the neighbour it is heading for, the
     * two swap and the new order comes back. Returns null while the row hasn't travelled far enough,
     * when it's already at the end it's heading towards, or when [from] isn't a block start (a stale
     * mid-drag index).
     *
     * [asGroup] picks the granularity, and the caller pins it for the whole drag: a group header
     * moves with its tabs and steps over whole neighbouring groups, while a bare row (a project, or a
     * separator heading no tabs) steps one visible row at a time so it can be dropped inside a group.
     * [rowHeight] gives the measured height of the row at an item index.
     */
    fun dragStep(
        items: List<RailItem>,
        from: Int,
        asGroup: Boolean,
        delta: Float,
        rowHeight: (Int) -> Int,
    ): RailDragStep? {
        val blocks = if (asGroup) groupBlocks(items) else visibleBlocks(items)
        val at = blocks.indexOfFirst { it.start == from }
        if (at < 0) return null
        val block = blocks[at]
        if (delta > 0f && at < blocks.lastIndex) {
            val next = blocks[at + 1]
            val height = blockHeight(items, next, rowHeight)
            if (height > 0 && delta > height / 2f) {
                return RailDragStep(swapAdjacentBlocks(items, block.start, block.span, next.span), height)
            }
        } else if (delta < 0f && at > 0) {
            val prev = blocks[at - 1]
            val height = blockHeight(items, prev, rowHeight)
            if (height > 0 && -delta > height / 2f) {
                return RailDragStep(swapAdjacentBlocks(items, prev.start, prev.span, block.span), -height)
            }
        }
        return null
    }

    /**
     * How tall [block] stands on screen: its head row plus the member rows of an expanded group. A
     * collapsed group is just its separator row, since its tabs aren't drawn — so the distance a drag
     * has to cover to pass a neighbour always matches what the user sees.
     */
    private fun blockHeight(items: List<RailItem>, block: RailBlock, rowHeight: (Int) -> Int): Int {
        val head = items.getOrNull(block.start)
        val rows = if (head is RailItem.Separator && head.collapsed) 1 else block.span
        return (block.start until block.start + rows).sumOf { rowHeight(it) }
    }

    /** State-file value for one rail row. Newlines in a name are flattened so the line format holds. */
    fun encode(item: RailItem): String = when (item) {
        is RailItem.Project -> PROJECT_PREFIX + item.path.toAbsolutePath().normalize().toString()
        is RailItem.Separator ->
            (if (item.collapsed) SEPARATOR_COLLAPSED_PREFIX else SEPARATOR_PREFIX) +
                item.name.replace('\n', ' ').replace('\r', ' ')
    }

    /**
     * Parses one encoded rail row. Separators are assigned [separatorId] (the caller supplies a
     * unique id per row). Returns null for a malformed project path or unrecognized prefix so a
     * single bad line is skipped rather than aborting the whole load.
     */
    fun decode(value: String, separatorId: Long): RailItem? = when {
        value.startsWith(PROJECT_PREFIX) ->
            runCatching { Paths.get(value.removePrefix(PROJECT_PREFIX)) }.getOrNull()?.let { RailItem.Project(it) }
        value.startsWith(SEPARATOR_COLLAPSED_PREFIX) ->
            RailItem.Separator(value.removePrefix(SEPARATOR_COLLAPSED_PREFIX), separatorId, collapsed = true)
        value.startsWith(SEPARATOR_PREFIX) ->
            RailItem.Separator(value.removePrefix(SEPARATOR_PREFIX), separatorId)
        else -> null
    }
}
