package iondrive.nop.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor

/**
 * How a panel's group columns are packed into the available area. Both the commit panel's changes
 * and the find-in-files results lay out through this.
 *
 * Columns fill left to right at [columnWidth]. When another column won't fit at the width that
 * just covers the longest file name, the grid wraps to a new row *below* (splitting the panel
 * vertically) rather than squeezing the columns thinner. Only once the vertical space is used up —
 * [rows] is capped by the height — does the grid grow past the right edge, at which point
 * [scrollHorizontally] is set and the caller wraps the grid in a horizontal scroller.
 */
data class GroupGrid(
    val columnWidth: Dp,
    val rowHeight: Dp,
    val columnsPerRow: Int,
    val rows: Int,
    val scrollHorizontally: Boolean,
) {
    /** Total width the grid occupies — wider than the viewport exactly when [scrollHorizontally]. */
    val contentWidth: Dp
        get() = columnWidth * columnsPerRow + GroupGridMetrics.COLUMN_GAP * (columnsPerRow - 1).coerceAtLeast(0)
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
        if (groupCount <= 0) return GroupGrid(availableWidth, availableHeight, 0, 0, false)

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

        val rows: Int
        val columnsPerRow: Int
        val scroll: Boolean
        when {
            groupCount <= fitCols -> {
                // Everything fits on one row; stretch the columns to fill the width.
                rows = 1
                columnsPerRow = groupCount
                scroll = false
            }
            ceil(groupCount.toDouble() / fitCols).toInt() <= fitRows -> {
                // Wrap onto extra rows that still fit vertically; balance columns across them.
                rows = ceil(groupCount.toDouble() / fitCols).toInt()
                columnsPerRow = ceil(groupCount.toDouble() / rows).toInt()
                scroll = false
            }
            else -> {
                // Vertical space exhausted: keep the natural width and overflow to the right.
                rows = fitRows
                columnsPerRow = ceil(groupCount.toDouble() / rows).toInt()
                scroll = true
            }
        }

        val columnWidth = if (scroll) {
            natural
        } else {
            // Stretch to fill so there's no ragged empty band on the right; never below natural.
            maxOf(natural, (availW - colGap * (columnsPerRow - 1)) / columnsPerRow)
        }
        val rowHeight = if (rows <= 1) availH else (availH - rowGap * (rows - 1)) / rows
        return GroupGrid(columnWidth.dp, rowHeight.dp, columnsPerRow, rows, scroll)
    }
}

/**
 * The per-column width tweaks made by dragging the separators between a grid row's columns.
 *
 * A drag is zero-sum: it moves width from one column to its neighbour, leaving the row as wide as
 * [GroupGridMetrics.layout] made it, so nothing reflows or starts scrolling just because a
 * separator moved. Offsets are held per column (in dp, relative to the packed column width) rather
 * than as absolute widths, so a column keeps the extra room it was given as the window resizes and
 * the packed width underneath it changes.
 */
object GroupColumnWidths {
    /**
     * The widths for one row of columns packed at [baseWidth] with the user's [offsets] applied.
     * The offsets are re-centred on zero so the total is always `baseWidth * offsets.size`, and any
     * column dragged under [GroupGridMetrics.MIN_COLUMN_WIDTH] is pulled back up at the expense of
     * whichever columns still have slack.
     */
    fun resolve(baseWidth: Float, offsets: List<Float>): List<Float> {
        val n = offsets.size
        if (n == 0) return emptyList()
        val min = GroupGridMetrics.MIN_COLUMN_WIDTH.value
        val total = baseWidth * n
        // Too little room to honour the minimum everywhere: share what there is out evenly.
        if (total <= min * n) return List(n) { total / n }

        val mean = offsets.sum() / n
        val widths = MutableList(n) { baseWidth + offsets[it] - mean }
        // Each pass moves the shortfall of the too-narrow columns onto the ones above the minimum,
        // in proportion to the slack each has; a column pinned at the minimum has none, so at most
        // one column can be pinned per pass and n passes always settle it.
        repeat(n) {
            val deficit = widths.sumOf { maxOf(0f, min - it).toDouble() }.toFloat()
            if (deficit <= 0.01f) return@repeat
            val slack = widths.sumOf { maxOf(0f, it - min).toDouble() }.toFloat()
            if (slack <= 0f) return@repeat
            val moved = minOf(deficit, slack)
            for (i in widths.indices) {
                val over = widths[i] - min
                if (over > 0f) widths[i] -= moved * (over / slack)
            }
            for (i in widths.indices) {
                val under = min - widths[i]
                if (under > 0f) widths[i] += under * (moved / deficit)
            }
        }
        return widths
    }

    /**
     * [widths] after the separator to the right of column [dividerIndex] is dragged [delta] dp,
     * clamped so neither of the two columns it sits between drops below the minimum width.
     */
    fun drag(widths: List<Float>, dividerIndex: Int, delta: Float): List<Float> {
        if (dividerIndex < 0 || dividerIndex + 1 >= widths.size) return widths
        val min = GroupGridMetrics.MIN_COLUMN_WIDTH.value
        val left = widths[dividerIndex]
        val right = widths[dividerIndex + 1]
        val lower = min - left
        val upper = right - min
        // Both sides are already at or under the minimum — there is nothing left to trade.
        if (lower > upper) return widths
        val applied = delta.coerceIn(lower, upper)
        if (applied == 0f) return widths
        return widths.toMutableList().also {
            it[dividerIndex] = left + applied
            it[dividerIndex + 1] = right - applied
        }
    }
}
