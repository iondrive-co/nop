package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.io.File
import kotlin.math.roundToInt

/** Width of the strip [SubtypeGutter] draws in, beside the text of a Java file. */
val SUBTYPE_GUTTER_WIDTH = 14.dp

/** A type that extends or implements the one a [SubtypeMarker] sits beside, and where it is declared. */
data class SubtypeTarget(val name: String, val fqn: String, val file: File, val line: Int)

/** A type declared in the open file that has subtypes: [offset] is where its name starts. */
data class SubtypeMarker(val offset: Int, val name: String, val subtypes: List<SubtypeTarget>)

/**
 * A narrow column to the left of a Java file's text holding a down arrow beside every type that
 * something else extends or implements. Clicking the arrow jumps to the subtype through [onJump], or,
 * when there is more than one, opens a list of them to pick from. Aligned to the text the way
 * [BlameGutter] is: it shares the editor's [layout] and [scrollState].
 */
@Composable
fun SubtypeGutter(
    markers: List<SubtypeMarker>,
    layout: TextLayoutResult?,
    scrollState: ScrollState,
    onJump: (File, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = JewelTheme.isDark
    val arrowColor = if (isDark) Color(0xFF6E9FF2) else Color(0xFF3574F0)
    val hoverBg = if (isDark) Color(0x2685B0FF) else Color(0x224C8DFF)
    val density = LocalDensity.current
    val jump by rememberUpdatedState(onJump)

    var hovered by remember { mutableStateOf<SubtypeMarker?>(null) }
    // The marker whose list of subtypes is open, and the gutter-relative y its row starts at.
    var chooser by remember { mutableStateOf<Pair<SubtypeMarker, Float>?>(null) }

    /** The marker on the visual line at gutter-relative [y], if any. */
    fun markerAt(tl: TextLayoutResult, y: Float): SubtypeMarker? {
        val textLen = tl.layoutInput.text.length
        val line = tl.getLineForVerticalPosition(y + scrollState.value)
        return markers.firstOrNull { tl.getLineForOffset(it.offset.coerceIn(0, textLen)) == line }
    }

    fun rowTop(tl: TextLayoutResult, marker: SubtypeMarker): Float {
        val line = tl.getLineForOffset(marker.offset.coerceIn(0, tl.layoutInput.text.length))
        return tl.getLineTop(line) - scrollState.value
    }

    Box(
        modifier = modifier
            .width(SUBTYPE_GUTTER_WIDTH)
            .fillMaxHeight()
            .clipToBounds()
            .pointerHoverIcon(if (hovered != null) PointerIcon.Hand else PointerIcon.Default)
            .pointerInput(layout, markers) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        val tl = layout
                        if (event.type == PointerEventType.Exit || change == null || tl == null) {
                            hovered = null
                            continue
                        }
                        val marker = markerAt(tl, change.position.y)
                        hovered = marker
                        if (event.type == PointerEventType.Press && marker != null) {
                            change.consume()
                            // The jump swaps the file under a pointer that hasn't moved, so no exit
                            // event would clear this.
                            hovered = null
                            val only = marker.subtypes.singleOrNull()
                            if (only != null) {
                                jump(only.file, only.line)
                            } else {
                                chooser = marker to rowTop(tl, marker)
                            }
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val tl = layout ?: return@Canvas
            val stroke = 1.5.dp.toPx()
            val half = 3.5.dp.toPx()
            for (marker in markers) {
                val line = tl.getLineForOffset(marker.offset.coerceIn(0, tl.layoutInput.text.length))
                val top = tl.getLineTop(line) - scrollState.value
                val bottom = tl.getLineBottom(line) - scrollState.value
                if (bottom < 0f || top > size.height) continue
                if (marker == hovered || marker == chooser?.first) {
                    drawRect(hoverBg, topLeft = Offset(0f, top), size = Size(size.width, bottom - top))
                }
                // A down arrow: a shaft with a chevron on its lower end, centred on the row.
                val cx = size.width / 2f
                val cy = (top + bottom) / 2f
                val shaftTop = cy - half - 1f
                val tip = cy + half
                drawLine(arrowColor, Offset(cx, shaftTop), Offset(cx, tip), stroke, StrokeCap.Round)
                val head = Path().apply {
                    moveTo(cx - half, tip - half)
                    lineTo(cx, tip)
                    lineTo(cx + half, tip - half)
                }
                drawPath(
                    head,
                    arrowColor,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }

        val open = chooser
        // Also guarded by [markers]: the gutter outlives a switch to another file's tab.
        val hover = hovered?.takeIf { it in markers }
        if (open != null) {
            val (marker, top) = open
            val rowHeight = layout?.let { tl ->
                val line = tl.getLineForOffset(marker.offset.coerceIn(0, tl.layoutInput.text.length))
                tl.getLineBottom(line) - tl.getLineTop(line)
            } ?: 0f
            Popup(
                offset = IntOffset(
                    with(density) { SUBTYPE_GUTTER_WIDTH.roundToPx() },
                    (top + rowHeight).roundToInt(),
                ),
                onDismissRequest = { chooser = null },
                properties = PopupProperties(focusable = true),
            ) {
                SubtypeChooser(marker) { target ->
                    chooser = null
                    jump(target.file, target.line)
                }
            }
        } else if (hover != null && layout != null) {
            SubtypeTooltip(hover, rowTop(layout, hover))
        }
    }
}

/** The list a multi-subtype arrow opens: each subtype's name over its fully-qualified name. */
@Composable
private fun SubtypeChooser(marker: SubtypeMarker, onPick: (SubtypeTarget) -> Unit) {
    val isDark = JewelTheme.isDark
    val border = if (isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
    val muted = if (isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
    Column(
        modifier = Modifier
            .widthIn(min = 220.dp, max = 480.dp)
            .heightIn(max = 400.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(JewelTheme.globalColors.panelBackground)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .padding(vertical = 4.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Subtypes of ${marker.name}",
            color = muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        for (target in marker.subtypes) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(target) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(target.name)
                Text(target.fqn, color = muted, fontSize = 11.sp)
            }
        }
    }
}

/** What a click on the hovered arrow will do, shown beside it. */
@Composable
private fun SubtypeTooltip(marker: SubtypeMarker, top: Float) {
    val isDark = JewelTheme.isDark
    val bg = if (isDark) Color(0xFF2B2D30) else Color(0xFFFDFDFE)
    val border = if (isDark) Color(0xFF4A4D54) else Color(0xFFC9CCD3)
    val density = LocalDensity.current
    val label = marker.subtypes.singleOrNull()?.let { "Go to subtype ${it.name}" }
        ?: "${marker.subtypes.size} subtypes of ${marker.name}"
    Popup(
        offset = IntOffset(
            with(density) { (SUBTYPE_GUTTER_WIDTH + 4.dp).roundToPx() },
            top.roundToInt(),
        ),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(label, fontSize = 12.sp)
        }
    }
}
