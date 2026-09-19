package iondrive.nop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * Where, in window pixels, the panel a control sits in is: the tool pane beside the agent pane
 * provides it (see [ToolTabs]). Null anywhere else, where the whole window is room enough.
 */
val LocalPanelBounds = compositionLocalOf<Rect?> { null }

/**
 * A dropdown opened from a control in one of the tool region's panels: the commit panel's recent
 * messages, the diff panel's base.
 *
 * It opens below [anchor] and stays inside the panel, moving left or growing narrower as it has to.
 * A plain `Popup` is only kept inside the *window*, sliding left as far as it takes, and left of
 * these panels is the agent pane — whose terminal is drawn over every popup, so whatever the slide
 * put there simply wasn't on screen. [content] is handed the width to take: [maxWidth], or the
 * panel's width if that is less.
 */
@Composable
fun PanelDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    anchor: @Composable () -> Unit,
    content: @Composable (width: Dp) -> Unit,
) {
    val density = LocalDensity.current
    val panel = LocalPanelBounds.current
    Box(modifier = modifier) {
        anchor()
        if (expanded) {
            val width = panel
                ?.let { minOf(maxWidth, with(density) { it.width.toDp() } - EDGE_MARGIN * 2) }
                ?.coerceAtLeast(MIN_WIDTH)
                ?: maxWidth
            val margin = with(density) { EDGE_MARGIN.roundToPx() }
            Popup(
                popupPositionProvider = remember(panel, margin) { WithinPanel(panel, margin) },
                onDismissRequest = onDismiss,
                properties = PopupProperties(focusable = true),
            ) {
                content(width)
            }
        }
    }
}

/** Room left clear between a dropdown and the edges of its panel. */
private val EDGE_MARGIN = 8.dp

/** Narrower than this and a dropdown's rows stop being readable; better to overhang the panel. */
private val MIN_WIDTH = 120.dp

/**
 * Just below the anchor, from its left edge where that fits, and otherwise moved left only as far
 * as the panel's right edge asks — never past the panel's left one. Kept inside the window
 * vertically, by moving up over the anchor when there is no room below it.
 */
private class WithinPanel(private val panel: Rect?, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val left = panel?.let { it.left.roundToInt() + margin } ?: 0
        val right = panel?.let { it.right.roundToInt() - margin } ?: windowSize.width
        return IntOffset(
            x = anchorBounds.left.coerceAtMost(right - popupContentSize.width).coerceAtLeast(left),
            y = (anchorBounds.bottom + GAP_PX)
                .coerceAtMost(windowSize.height - popupContentSize.height)
                .coerceAtLeast(0),
        )
    }
}

private const val GAP_PX = 4
