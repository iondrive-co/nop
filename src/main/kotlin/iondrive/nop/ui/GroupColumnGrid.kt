package iondrive.nop.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import kotlin.math.abs

/** One column of a [GroupColumnGrid]: a heading and the rows beneath it. [key] must be unique. */
class GroupColumn(
    val key: String,
    val header: String,
    val content: @Composable () -> Unit,
)

/** Height reserved at the bottom of the grid for the horizontal scrollbar when it's shown. */
private val H_SCROLLBAR_RESERVE = 14.dp

/**
 * Lays [columns] out side by side, each under its heading. Columns pack left to right at
 * [naturalColumnWidth] — the width that just covers the caller's longest row label, clamped by
 * [GroupGridMetrics]; a row takes as many as fit before the next starts, and only once the height
 * is used up does the grid scroll horizontally.
 *
 * Every separator is draggable ([GroupTrackSizes]), and double-clicking one puts its row — or the
 * stack of rows — back to even. The one between two columns hands width from one to the other; the
 * one past the last column of a row widens the row itself, which is how a clipped name in the
 * rightmost column gets read; the one between two rows trades height. The tweaks are remembered per
 * column key (and per row) for as long as the grid is on screen.
 *
 * The caller measures its own natural width because only it knows the fonts and row chrome its rows
 * use; everything from there — packing, headings, wrapping, the scroller — is shared, so the commit
 * panel's change columns and the find-in-files result columns read the same.
 */
@Composable
fun GroupColumnGrid(
    columns: List<GroupColumn>,
    naturalColumnWidth: Dp,
    modifier: Modifier = Modifier,
) {
    if (columns.isEmpty()) return
    // Width handed to a column by the separators either side of it, in dp, keyed by column so a
    // group keeps the room it was given while its rows churn underneath it.
    val widthOffsets = remember { mutableStateMapOf<String, Float>() }
    // Width a row's trailing separator has added past the packed width, keyed by row index.
    val rowOverhangs = remember { mutableStateMapOf<Int, Float>() }
    // Height handed to a row by the separators above and below it, keyed by row index.
    val heightOffsets = remember { mutableStateMapOf<Int, Float>() }
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val colGap = GroupGridMetrics.COLUMN_GAP
        // The trailing separator sits past the last column, so the packer gets the width left over
        // once its band is reserved. Otherwise every row would overhang by the band's width and the
        // grid would always think it had to scroll.
        val packWidth = (maxWidth - colGap).coerceAtLeast(colGap)

        var grid = GroupGridMetrics.layout(columns.size, naturalColumnWidth, packWidth, maxHeight)
        var rows = rowsOf(columns, grid)
        var widths = rows.mapIndexed { i, row -> rowWidths(grid, row, i, widthOffsets, rowOverhangs) }
        var scroll = grid.scrollHorizontally || widths.any { rowWidth(it) > packWidth.value + 0.5f }
        if (scroll) {
            // Leave a strip at the bottom for the scrollbar so it can't cover a row.
            grid = GroupGridMetrics.layout(
                columns.size,
                naturalColumnWidth,
                packWidth,
                maxHeight - H_SCROLLBAR_RESERVE,
            )
            rows = rowsOf(columns, grid)
            widths = rows.mapIndexed { i, row -> rowWidths(grid, row, i, widthOffsets, rowOverhangs) }
            scroll = true
        }
        val heights = GroupTrackSizes.resolve(
            baseSize = grid.rowHeight.value,
            offsets = rows.indices.map { heightOffsets[it] ?: 0f },
            min = GroupGridMetrics.MIN_ROW_HEIGHT.value,
        )
        // Rows can differ in width once one has been dragged out; the widest is what scrolls.
        val contentWidth = (widths.maxOfOrNull { rowWidth(it) } ?: 0f).dp + colGap

        val gridContent: @Composable () -> Unit = {
            Column(modifier = if (scroll) Modifier.width(contentWidth) else Modifier.fillMaxWidth()) {
                rows.forEachIndexed { rowIndex, rowColumns ->
                    if (rowIndex > 0) {
                        GridSeparator(
                            orientation = Orientation.Vertical,
                            onDrag = { deltaPx ->
                                val moved = GroupTrackSizes.drag(
                                    heights,
                                    rowIndex - 1,
                                    with(density) { deltaPx.toDp() }.value,
                                    GroupGridMetrics.MIN_ROW_HEIGHT.value,
                                )
                                rows.indices.forEach { heightOffsets[it] = moved[it] - grid.rowHeight.value }
                            },
                            onReset = { heightOffsets.clear() },
                        )
                    }
                    val rowSizes = widths[rowIndex]
                    // Write the whole row back after a drag, not just the tracks that moved: what's
                    // on screen is the resolved sizes, which may already differ from the stored
                    // offsets after a re-pack. The row's overhang is held separately, so it comes
                    // back off the last column before the rest is stored as that column's offset.
                    val storeWidths: (List<Float>) -> Unit = { moved ->
                        val base = grid.columnWidthFor(rowColumns.size).value
                        val overhang = rowOverhangs[rowIndex] ?: 0f
                        rowColumns.forEachIndexed { i, c ->
                            val own = if (i == rowColumns.lastIndex) moved[i] - overhang else moved[i]
                            widthOffsets[c.key] = own - base
                        }
                    }
                    Row(modifier = Modifier.height(heights[rowIndex].dp)) {
                        rowColumns.forEachIndexed { colIndex, column ->
                            if (colIndex > 0) {
                                GridSeparator(
                                    orientation = Orientation.Horizontal,
                                    onDrag = { deltaPx ->
                                        storeWidths(
                                            GroupTrackSizes.drag(
                                                rowSizes,
                                                colIndex - 1,
                                                with(density) { deltaPx.toDp() }.value,
                                                GroupGridMetrics.MIN_COLUMN_WIDTH.value,
                                            )
                                        )
                                    },
                                    onReset = { rowColumns.forEach { widthOffsets.remove(it.key) } },
                                )
                            }
                            key(column.key) {
                                HeadedColumn(
                                    header = column.header,
                                    modifier = Modifier.width(rowSizes[colIndex].dp).fillMaxHeight(),
                                    content = column.content,
                                )
                            }
                        }
                        // The row's own right edge: dragging it widens the row past the panel,
                        // scrolling the grid, rather than taking width off a neighbour.
                        GridSeparator(
                            orientation = Orientation.Horizontal,
                            onDrag = { deltaPx ->
                                val moved = GroupTrackSizes.dragTrailing(
                                    rowSizes,
                                    with(density) { deltaPx.toDp() }.value,
                                    GroupGridMetrics.MIN_COLUMN_WIDTH.value,
                                )
                                rowOverhangs[rowIndex] = moved.last() - rowSizes.last() +
                                    (rowOverhangs[rowIndex] ?: 0f)
                            },
                            onReset = { rowOverhangs.remove(rowIndex) },
                        )
                    }
                }
            }
        }
        if (scroll) {
            val hScroll = rememberScrollState()
            Box(modifier = Modifier.fillMaxSize().horizontalScroll(hScroll)) { gridContent() }
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(hScroll),
                style = NopScrollbarStyle,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            )
        } else {
            gridContent()
        }
    }
}

/** The columns of each row, in order — the last row holding whatever the ones above didn't take. */
private fun rowsOf(columns: List<GroupColumn>, grid: GroupGrid): List<List<GroupColumn>> =
    (0 until grid.rows).mapNotNull { rowIndex ->
        val first = rowIndex * grid.columnsPerRow
        if (first >= columns.size) null
        else columns.subList(first, minOf(first + grid.columnsPerRow, columns.size))
    }

/** The width of every column on one row: the packed width, plus what its separators have moved. */
private fun rowWidths(
    grid: GroupGrid,
    rowColumns: List<GroupColumn>,
    rowIndex: Int,
    widthOffsets: Map<String, Float>,
    rowOverhangs: Map<Int, Float>,
): List<Float> {
    val base = grid.columnWidthFor(rowColumns.size).value
    val widths = GroupTrackSizes.resolve(
        baseSize = base,
        offsets = rowColumns.map { widthOffsets[it.key] ?: 0f },
        min = GroupGridMetrics.MIN_COLUMN_WIDTH.value,
    )
    val overhang = rowOverhangs[rowIndex] ?: 0f
    if (overhang == 0f) return widths
    return GroupTrackSizes.dragTrailing(widths, overhang, GroupGridMetrics.MIN_COLUMN_WIDTH.value)
}

/** Total width of a row: its columns plus the separators between them. */
private fun rowWidth(widths: List<Float>): Float =
    widths.sum() + GroupGridMetrics.COLUMN_GAP.value * (widths.size - 1).coerceAtLeast(0)

/** How far the pointer may wander during a press for it to still count as a click, in px. */
private const val SEPARATOR_CLICK_SLOP = 3f

/**
 * The draggable line between two tracks of the grid — two columns of a row, or two rows. It fills
 * the gap the packing left between them, so the grab area is the full gap even though the line
 * drawn down it is a hairline. Like [SplitPane]'s divider it starts dragging on the first press
 * rather than after Compose's drag slop, which the cursor would cover by leaving this narrow band.
 *
 * Dragging from the press is also why the double-click that calls [onReset] is counted out of the
 * drag's own start/stop instead of a `clickable`: the drag has already claimed the press by the
 * time a click modifier would see it, so a stacked one never fires.
 */
@Composable
private fun GridSeparator(orientation: Orientation, onDrag: (Float) -> Unit, onReset: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val doubleClickMs = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val clicks = remember { SeparatorClicks() }
    val across = orientation == Orientation.Horizontal
    val active = hovered || dragging
    val color = when {
        JewelTheme.isDark -> if (active) Color(0xFF6F737A) else Color(0xFF393B40)
        else -> if (active) Color(0xFF9BA0A8) else Color(0xFFD3D5DB)
    }
    val thickness = if (active) 2.dp else 1.dp
    val band = if (across) {
        Modifier.width(GroupGridMetrics.COLUMN_GAP).fillMaxHeight()
    } else {
        Modifier.height(GroupGridMetrics.ROW_GAP).fillMaxWidth()
    }
    val line = if (across) {
        Modifier.width(thickness).fillMaxHeight()
    } else {
        Modifier.height(thickness).fillMaxWidth()
    }
    Box(
        modifier = band
            .hoverable(interaction)
            .pointerHoverIcon(if (across) HorizontalResizeCursor else VerticalResizeCursor)
            .draggable(
                state = rememberDraggableState(onDelta = { delta ->
                    clicks.travelled += abs(delta)
                    onDrag(delta)
                }),
                orientation = orientation,
                startDragImmediately = true,
                onDragStarted = {
                    dragging = true
                    clicks.travelled = 0f
                },
                onDragStopped = {
                    dragging = false
                    // A press that went nowhere is a click; two in quick succession reset the row.
                    if (clicks.travelled <= SEPARATOR_CLICK_SLOP) {
                        val now = System.currentTimeMillis()
                        if (now - clicks.lastClickAt <= doubleClickMs) {
                            clicks.lastClickAt = 0L
                            onReset()
                        } else {
                            clicks.lastClickAt = now
                        }
                    } else {
                        clicks.lastClickAt = 0L
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(line.background(color))
    }
}

/** Scratch state for [GridSeparator]'s click counting; nothing here drives recomposition. */
private class SeparatorClicks {
    var travelled = 0f
    var lastClickAt = 0L
}

/** A column's heading and rule, with the caller's rows filling the rest of its height. */
@Composable
private fun HeadedColumn(
    header: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val labelColor = if (JewelTheme.isDark) Color(0xFFCED0D6) else Color(0xFF3C4049)
    val rule = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
    Column(modifier = modifier) {
        Text(
            text = header,
            color = labelColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp).height(1.dp).background(rule))
        content()
    }
}
