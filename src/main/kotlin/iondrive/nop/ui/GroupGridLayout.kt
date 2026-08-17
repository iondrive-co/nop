package iondrive.nop.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor

/**
 * How a panel's group columns are packed into the available area. Both the commit panel's changes
 * and the find-in-files results lay out through this.
 *
 * Every row takes as many columns as fit at the width that just covers the longest file name, so a
 * group only drops to the row below once the row above is genuinely full. When the rows that need
 * won't fit down the height, the grid grows past the right edge instead: [scrollHorizontally] is
 * set and the caller wraps the grid in a horizontal scroller.
 */
data class GroupGrid(
    val columnWidth: Dp,
    val rowHeight: Dp,
    val columnsPerRow: Int,
    val rows: Int,
    val scrollHorizontally: Boolean,
    val availableWidth: Dp,
) {
    /**
     * Width of each column on a row holding [count] of them. A short last row — the remainder left
     * by filling the rows above — stretches its columns to fill the width rather than leaving a
     * ragged empty band down the right of the panel.
     */
    fun columnWidthFor(count: Int): Dp {
        if (count <= 0 || scrollHorizontally || count >= columnsPerRow) return columnWidth
        val gaps = GroupGridMetrics.COLUMN_GAP.value * (count - 1)
        return maxOf(columnWidth.value, (availableWidth.value - gaps) / count).dp
    }
}

object GroupGridMetrics {
    /** Never render a column narrower than this — below it even short names get cramped. */
    val MIN_COLUMN_WIDTH = 140.dp

    /** A single very long path ellipsizes (hover shows it in full) rather than widening every column. */
    val MAX_COLUMN_WIDTH = 340.dp

    val COLUMN_GAP = 12.dp
    val ROW_GAP = 12.dp

    /** A row must fit its header, rule and a few item rows to stay legible before we add another. */
    val MIN_ROW_HEIGHT = 108.dp

    /**
     * Pack [groupCount] columns into [availableWidth] x [availableHeight]. [naturalColumnWidth] is
     * the width that just covers the longest file name (plus row chrome); it is clamped into
     * [[MIN_COLUMN_WIDTH], [MAX_COLUMN_WIDTH]] before use.
     */
    fun layout(
        groupCount: Int,
        naturalColumnWidth: Dp,
        availableWidth: Dp,
        availableHeight: Dp,
    ): GroupGrid {
        if (groupCount <= 0) return GroupGrid(availableWidth, availableHeight, 0, 0, false, availableWidth)

        val colGap = COLUMN_GAP.value
        val rowGap = ROW_GAP.value
        val minRow = MIN_ROW_HEIGHT.value
        val natural = naturalColumnWidth.value.coerceIn(MIN_COLUMN_WIDTH.value, MAX_COLUMN_WIDTH.value)
        // Guard against the zero/negative constraints Compose can hand us on the first layout pass.
        val availW = availableWidth.value.coerceAtLeast(natural)
        val availH = availableHeight.value.coerceAtLeast(minRow)

        // How many natural-width columns fit across the width, and how many min-height rows down it.
        val fitCols = maxOf(1, floor((availW + colGap) / (natural + colGap)).toInt())
        val fitRows = maxOf(1, floor((availH + rowGap) / (minRow + rowGap)).toInt())

        // Fill each row up before starting the next, so no group sits a row lower than it needs to.
        var columnsPerRow = minOf(groupCount, fitCols)
        var rows = ceil(groupCount.toDouble() / columnsPerRow).toInt()
        val scroll = rows > fitRows
        if (scroll) {
            // Vertical space exhausted: keep the natural width and overflow to the right.
            rows = fitRows
            columnsPerRow = ceil(groupCount.toDouble() / rows).toInt()
        }

        val columnWidth = if (scroll) {
            natural
        } else {
            // Stretch to fill so there's no ragged empty band on the right; never below natural.
            maxOf(natural, (availW - colGap * (columnsPerRow - 1)) / columnsPerRow)
        }
        val rowHeight = if (rows <= 1) availH else (availH - rowGap * (rows - 1)) / rows
        return GroupGrid(columnWidth.dp, rowHeight.dp, columnsPerRow, rows, scroll, availW.dp)
    }
}

/**
 * The sizes of one axis of a grid row or column stack, after the user has dragged the separators
 * between the tracks — the columns across a row, or the rows down the grid.
 *
 * A drag is zero-sum: it moves space from one track to its neighbour, leaving the row (or the
 * stack) the size the packer made it, so nothing reflows or starts scrolling just because a
 * separator moved. Offsets are held per track (in dp, relative to the packed size) rather than as
 * absolute sizes, so a track keeps the extra room it was given as the window resizes and the packed
 * size underneath it changes.
 */
object GroupTrackSizes {
    /**
     * The sizes for tracks packed at [baseSize] with the user's [offsets] applied. The offsets are
     * re-centred on zero so the total is always `baseSize * offsets.size`, and any track dragged
     * under [min] is pulled back up at the expense of whichever tracks still have slack.
     */
    fun resolve(baseSize: Float, offsets: List<Float>, min: Float): List<Float> {
        val n = offsets.size
        if (n == 0) return emptyList()
        val total = baseSize * n
        // Too little room to honour the minimum everywhere: share what there is out evenly.
        if (total <= min * n) return List(n) { total / n }

        val mean = offsets.sum() / n
        val sizes = MutableList(n) { baseSize + offsets[it] - mean }
        // Each pass moves the shortfall of the too-small tracks onto the ones above the minimum, in
        // proportion to the slack each has; a track pinned at the minimum has none, so at most one
        // track can be pinned per pass and n passes always settle it.
        repeat(n) {
            val deficit = sizes.sumOf { maxOf(0f, min - it).toDouble() }.toFloat()
            if (deficit <= 0.01f) return@repeat
            val slack = sizes.sumOf { maxOf(0f, it - min).toDouble() }.toFloat()
            if (slack <= 0f) return@repeat
            val moved = minOf(deficit, slack)
            for (i in sizes.indices) {
                val over = sizes[i] - min
                if (over > 0f) sizes[i] -= moved * (over / slack)
            }
            for (i in sizes.indices) {
                val under = min - sizes[i]
                if (under > 0f) sizes[i] += under * (moved / deficit)
            }
        }
        return sizes
    }

    /**
     * [sizes] after the separator following track [dividerIndex] is dragged [delta] dp, clamped so
     * neither of the two tracks it sits between drops below [min].
     */
    fun drag(sizes: List<Float>, dividerIndex: Int, delta: Float, min: Float): List<Float> {
        if (dividerIndex < 0 || dividerIndex + 1 >= sizes.size) return sizes
        val before = sizes[dividerIndex]
        val after = sizes[dividerIndex + 1]
        val lower = min - before
        val upper = after - min
        // Both sides are already at or under the minimum — there is nothing left to trade.
        if (lower > upper) return sizes
        val applied = delta.coerceIn(lower, upper)
        if (applied == 0f) return sizes
        return sizes.toMutableList().also {
            it[dividerIndex] = before + applied
            it[dividerIndex + 1] = after - applied
        }
    }

    /**
     * [sizes] after the separator *past the last track* is dragged [delta] dp. Nothing is on the
     * other side to trade with, so this one grows or shrinks the row itself — which is how a
     * clipped file name in the rightmost column is read: drag its edge out and the grid scrolls.
     */
    fun dragTrailing(sizes: List<Float>, delta: Float, min: Float): List<Float> {
        if (sizes.isEmpty()) return sizes
        val last = sizes.last()
        val applied = maxOf(delta, min - last)
        if (applied == 0f) return sizes
        return sizes.toMutableList().also { it[it.lastIndex] = last + applied }
    }
}
