package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import java.awt.Cursor

internal val HorizontalResizeCursor = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))
private val VerticalResizeCursor = PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR))

/**
 * Two side-by-side panes split by a draggable vertical line. Replaces Jewel's
 * HorizontalSplitLayout, which silently ignored drag deltas in our layout.
 *
 * State is hoisted via [ratio] / [onRatioChange] so callers can persist the value across
 * restarts. [ratio] is the fraction of the total width given to [first].
 */
@Composable
fun HorizontalSplit(
    ratio: Float,
    onRatioChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    minFirstDp: Dp = 120.dp,
    minSecondDp: Dp = 120.dp,
    dividerThickness: Dp = 4.dp,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val totalPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val dividerPx = with(density) { dividerThickness.toPx() }
        val minFirstPx = with(density) { minFirstDp.toPx() }
        val minSecondPx = with(density) { minSecondDp.toPx() }
        val available = (totalPx - dividerPx).coerceAtLeast(1f)
        val minRatio = (minFirstPx / available).coerceIn(0f, 1f)
        val maxRatio = (1f - (minSecondPx / available)).coerceIn(minRatio, 1f)
        val clampedRatio = ratio.coerceIn(minRatio, maxRatio)
        val firstWidthDp = with(density) { (available * clampedRatio).toDp() }

        Row(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.width(firstWidthDp).fillMaxHeight()) { first() }
            DividerBar(
                thickness = dividerThickness,
                orientation = Orientation.Horizontal,
                cursor = HorizontalResizeCursor,
                onDrag = { delta ->
                    onRatioChange(((clampedRatio * available + delta) / available).coerceIn(minRatio, maxRatio))
                },
            )
            Box(Modifier.weight(1f).fillMaxHeight()) { second() }
        }
    }
}

/**
 * Two stacked panes split by a draggable horizontal line. Replaces Jewel's VerticalSplitLayout.
 */
@Composable
fun VerticalSplit(
    ratio: Float,
    onRatioChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    minFirstDp: Dp = 80.dp,
    minSecondDp: Dp = 80.dp,
    dividerThickness: Dp = 4.dp,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val totalPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val dividerPx = with(density) { dividerThickness.toPx() }
        val minFirstPx = with(density) { minFirstDp.toPx() }
        val minSecondPx = with(density) { minSecondDp.toPx() }
        val available = (totalPx - dividerPx).coerceAtLeast(1f)
        val minRatio = (minFirstPx / available).coerceIn(0f, 1f)
        val maxRatio = (1f - (minSecondPx / available)).coerceIn(minRatio, 1f)
        val clampedRatio = ratio.coerceIn(minRatio, maxRatio)
        val firstHeightDp = with(density) { (available * clampedRatio).toDp() }

        Column(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.height(firstHeightDp).fillMaxWidth()) { first() }
            DividerBar(
                thickness = dividerThickness,
                orientation = Orientation.Vertical,
                cursor = VerticalResizeCursor,
                onDrag = { delta ->
                    onRatioChange(((clampedRatio * available + delta) / available).coerceIn(minRatio, maxRatio))
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) { second() }
        }
    }
}

@Composable
private fun DividerBar(
    thickness: Dp,
    orientation: Orientation,
    cursor: PointerIcon,
    onDrag: (Float) -> Unit,
) {
    val color = if (JewelTheme.isDark) androidx.compose.ui.graphics.Color(0xFF2B2D30)
        else androidx.compose.ui.graphics.Color(0xFFD3D5DB)
    // We pass startDragImmediately=true so the drag fires from the first press, before slop
    // is exceeded. Without it the user has to overshoot the divider's slop distance, but by then
    // the cursor has left the thin divider's hit area and pointerInput stops receiving moves —
    // Compose only delivers pointer events while the pointer is inside the modifier's bounds.
    val sized = when (orientation) {
        Orientation.Horizontal -> Modifier.width(thickness).fillMaxHeight()
        Orientation.Vertical -> Modifier.fillMaxWidth().height(thickness)
    }
    Box(
        sized
            .background(color)
            .pointerHoverIcon(cursor)
            .draggable(
                state = rememberDraggableState(onDelta = onDrag),
                orientation = orientation,
                startDragImmediately = true,
            )
    )
}
