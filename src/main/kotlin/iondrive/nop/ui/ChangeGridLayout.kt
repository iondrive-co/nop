package iondrive.nop.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor

/**
 * How the change-group columns are packed into the available commit-panel area.
 *
 * Columns fill left to right at [columnWidth]. When another column won't fit at the width that
 * just covers the longest file name, the grid wraps to a new row *below* (splitting the panel
 * vertically) rather than squeezing the columns thinner. Only once the vertical space is used up —
 * [rows] is capped by the height — does the grid grow past the right edge, at which point
 * [scrollHorizontally] is set and the caller wraps the grid in a horizontal scroller.
 */
data class ChangeGrid(
    val columnWidth: Dp,
    val rowHeight: Dp,
    val columnsPerRow: Int,
    val rows: Int,
    val scrollHorizontally: Boolean,
) {
    /** Total width the grid occupies — wider than the viewport exactly when [scrollHorizontally]. */
    val contentWidth: Dp
        get() = columnWidth * columnsPerRow + ChangeGridMetrics.COLUMN_GAP * (columnsPerRow - 1).coerceAtLeast(0)
}

object ChangeGridMetrics {
    /** Never render a column narrower than this — below it even short names get cramped. */
    val MIN_COLUMN_WIDTH = 140.dp

    /** A single very long path ellipsizes (hover shows it in full) rather than widening every column. */
    val MAX_COLUMN_WIDTH = 340.dp

    val COLUMN_GAP = 12.dp
    val ROW_GAP = 12.dp

    /** A row must fit its header, rule and a few change rows to stay legible before we add another. */
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
    ): ChangeGrid {
        if (groupCount <= 0) return ChangeGrid(availableWidth, availableHeight, 0, 0, false)

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
        return ChangeGrid(columnWidth.dp, rowHeight.dp, columnsPerRow, rows, scroll)
    }
}
