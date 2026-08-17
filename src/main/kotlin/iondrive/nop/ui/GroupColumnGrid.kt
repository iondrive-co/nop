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
import androidx.compose.foundation.layout.Arrangement
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
 * [GroupGridMetrics]; rather than squeezing thinner when they run out of room they wrap to a new row
 * below, and only once the height is used up does the grid scroll horizontally.
 *
 * The separator between two neighbouring columns can be dragged to hand width from one to the
 * other ([GroupColumnWidths]), and double-clicked to put the row back to even columns. The tweaks
 * are remembered per column key for as long as the grid is on screen.
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
    // Width handed to a column by its separators, in dp, keyed by column so a group keeps the room
    // it was given while its rows churn underneath it.
    val widthOffsets = remember { mutableStateMapOf<String, Float>() }
    BoxWithConstraints(modifier = modifier) {
        var grid = GroupGridMetrics.layout(columns.size, naturalColumnWidth, maxWidth, maxHeight)
        if (grid.scrollHorizontally) {
            // Leave a strip at the bottom for the scrollbar so it can't cover a row.
            grid = GroupGridMetrics.layout(
                columns.size,
                naturalColumnWidth,
                maxWidth,
                maxHeight - H_SCROLLBAR_RESERVE,
            )
        }
        val density = LocalDensity.current
        val gridContent: @Composable () -> Unit = {
            Column(
                verticalArrangement = Arrangement.spacedBy(GroupGridMetrics.ROW_GAP),
                modifier = if (grid.scrollHorizontally) Modifier.width(grid.contentWidth) else Modifier.fillMaxWidth(),
            ) {
                for (rowIndex in 0 until grid.rows) {
                    val first = rowIndex * grid.columnsPerRow
                    val rowColumns = columns.subList(first, minOf(first + grid.columnsPerRow, columns.size))
                    if (rowColumns.isEmpty()) continue
                    val widths = GroupColumnWidths.resolve(
                        baseWidth = grid.columnWidth.value,
                        offsets = rowColumns.map { widthOffsets[it.key] ?: 0f },
                    )
                    Row(modifier = Modifier.height(grid.rowHeight)) {
                        rowColumns.forEachIndexed { colIndex, column ->
                            if (colIndex > 0) {
                                ColumnSeparator(
                                    onDrag = { deltaPx ->
                                        val moved = GroupColumnWidths.drag(
                                            widths,
                                            colIndex - 1,
                                            with(density) { deltaPx.toDp() }.value,
                                        )
                                        // Write the whole row back, not just the pair that moved:
                                        // what's on screen is the resolved widths, which may
                                        // already differ from the stored offsets after a re-pack.
                                        rowColumns.forEachIndexed { i, c ->
                                            widthOffsets[c.key] = moved[i] - grid.columnWidth.value
                                        }
                                    },
                                    onReset = { rowColumns.forEach { widthOffsets.remove(it.key) } },
                                )
                            }
                            key(column.key) {
                                HeadedColumn(
                                    header = column.header,
                                    modifier = Modifier.width(widths[colIndex].dp).fillMaxHeight(),
                                    content = column.content,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (grid.scrollHorizontally) {
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

/** How far the pointer may wander during a press for it to still count as a click, in px. */
private const val SEPARATOR_CLICK_SLOP = 3f

/**
 * The draggable line between two columns. It fills the gap the packing left between them, so the
 * grab area is the full [GroupGridMetrics.COLUMN_GAP] even though the line drawn down it is a
 * hairline. Like [SplitPane]'s divider it starts dragging on the first press rather than after
 * Compose's drag slop, which the cursor would cover by leaving this narrow band.
 *
 * Dragging from the press is also why the double-click that calls [onReset] is counted out of the
 * drag's own start/stop instead of a `clickable`: the drag has already claimed the press by the
 * time a click modifier would see it, so a stacked one never fires.
 */
@Composable
private fun ColumnSeparator(onDrag: (Float) -> Unit, onReset: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val doubleClickMs = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val clicks = remember { SeparatorClicks() }
    val active = hovered || dragging
    val color = when {
        JewelTheme.isDark -> if (active) Color(0xFF6F737A) else Color(0xFF393B40)
        else -> if (active) Color(0xFF9BA0A8) else Color(0xFFD3D5DB)
    }
    Box(
        modifier = Modifier
            .width(GroupGridMetrics.COLUMN_GAP)
            .fillMaxHeight()
            .hoverable(interaction)
            .pointerHoverIcon(HorizontalResizeCursor)
            .draggable(
                state = rememberDraggableState(onDelta = { delta ->
                    clicks.travelled += abs(delta)
                    onDrag(delta)
                }),
                orientation = Orientation.Horizontal,
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
        Box(Modifier.width(if (active) 2.dp else 1.dp).fillMaxHeight().background(color))
    }
}

/** Scratch state for [ColumnSeparator]'s click counting; nothing here drives recomposition. */
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
