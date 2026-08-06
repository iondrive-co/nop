package iondrive.nop.ui

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Thin fully-rounded thumb over a transparent track (Compose Desktop's ScrollbarStyle draws no
 * track of its own). The thumb is a neutral grey rather than a tinted one so it doesn't compete
 * with the change markers drawn in the same lane, but it stays opaque enough to find without
 * hunting — a thumb you have to look for is worse than one that's a shade too loud.
 */
val NopScrollbarStyle: ScrollbarStyle = ScrollbarStyle(
    minimalHeight = 16.dp,
    thickness = 8.dp,
    shape = RoundedCornerShape(4.dp),
    hoverDurationMillis = 150,
    unhoverColor = Color(0x99A6A6A6),
    hoverColor = Color(0xCCC8C8C8),
)

/** LazyColumn with a vertical scrollbar overlaid on the right edge. */
@Composable
fun ScrollableColumn(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxHeight().padding(end = 8.dp),
            content = content,
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            style = NopScrollbarStyle,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}
