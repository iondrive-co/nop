package iondrive.nop.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

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
        val gridContent: @Composable () -> Unit = {
            Column(
                verticalArrangement = Arrangement.spacedBy(GroupGridMetrics.ROW_GAP),
                modifier = if (grid.scrollHorizontally) Modifier.width(grid.contentWidth) else Modifier.fillMaxWidth(),
            ) {
                for (rowIndex in 0 until grid.rows) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(GroupGridMetrics.COLUMN_GAP),
                        modifier = Modifier.height(grid.rowHeight),
                    ) {
                        for (colIndex in 0 until grid.columnsPerRow) {
                            val index = rowIndex * grid.columnsPerRow + colIndex
                            if (index < columns.size) {
                                val column = columns[index]
                                key(column.key) {
                                    HeadedColumn(
                                        header = column.header,
                                        modifier = Modifier.width(grid.columnWidth).fillMaxHeight(),
                                        content = column.content,
                                    )
                                }
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
