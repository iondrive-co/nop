package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import iondrive.nop.git.BlameLine
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val GUTTER_WIDTH = 132.dp
private val GUTTER_FONT_SIZE = 11.sp
private val DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yy-MM-dd").withZone(ZoneId.systemDefault())

/**
 * Computes the start offset of every logical line in [text] — index 0, then the index just past
 * each newline. The L-th entry is where line L begins; used to map a blame line (which is indexed
 * by logical line) onto a position in the editor's [TextLayoutResult], which is indexed by *visual*
 * line and so differs once any line soft-wraps.
 */
internal fun lineStartOffsets(text: String): IntArray {
    val starts = ArrayList<Int>()
    starts.add(0)
    for (i in text.indices) if (text[i] == '\n') starts.add(i + 1)
    return starts.toIntArray()
}

/** Largest logical line index whose start offset is <= [offset] (binary search over [starts]). */
internal fun logicalLineForOffset(starts: IntArray, offset: Int): Int {
    var lo = 0
    var hi = starts.size - 1
    var ans = 0
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (starts[mid] <= offset) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

/**
 * IntelliJ-style "annotate" column: a fixed-width strip drawn to the left of the editor showing,
 * for each line, the date and author of the commit that last touched it. It shares the editor's
 * [scrollState] and [layout] so annotations stay glued to their lines through scrolling and
 * soft-wrapping. Lines from the same commit are banded with a faint shared background; clicking a
 * committed line opens that commit's diff via [onLineClick], and hovering pops a card with the
 * commit's full summary. Uncommitted (locally-edited) lines render muted and aren't clickable.
 */
@Composable
fun BlameGutter(
    blame: List<BlameLine>?,
    layout: TextLayoutResult?,
    scrollState: ScrollState,
    text: String,
    onLineClick: (BlameLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = JewelTheme.isDark
    val gutterBg = if (isDark) Color(0xFF26282E) else Color(0xFFECEEF2)
    val borderColor = if (isDark) Color(0xFF3A3D44) else Color(0xFFD4D7DE)
    val dateColor = if (isDark) Color(0xFF7E8896) else Color(0xFF6B7480)
    val authorColor = if (isDark) Color(0xFF9AA4B2) else Color(0xFF4A5360)
    val uncommittedColor = if (isDark) Color(0xFF5A626E) else Color(0xFFAAB0BA)
    val bandColor = if (isDark) Color(0x14FFFFFF) else Color(0x0A000000)
    val hoverBand = if (isDark) Color(0x2685B0FF) else Color(0x224C8DFF)

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val starts = remember(text) { lineStartOffsets(text) }

    // Logical line currently under the pointer (drives the hover band + the detail popup), plus the
    // pointer position so the popup can anchor next to the cursor. Cleared when the pointer leaves.
    var hoverLine by remember { mutableStateOf<Int?>(null) }
    var hoverPos by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .width(GUTTER_WIDTH)
            .fillMaxHeight()
            .background(gutterBg)
            .clipToBounds()
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(layout, blame, starts) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        val tl = layout
                        if (event.type == PointerEventType.Exit || change == null || tl == null) {
                            hoverLine = null
                        } else {
                            hoverPos = change.position
                            val docY = change.position.y + scrollState.value
                            val vline = tl.getLineForVerticalPosition(docY)
                            val off = tl.getLineStart(vline)
                            hoverLine = logicalLineForOffset(starts, off)
                        }
                        if (event.type == PointerEventType.Press && change != null && tl != null) {
                            val docY = change.position.y + scrollState.value
                            val vline = tl.getLineForVerticalPosition(docY)
                            val line = logicalLineForOffset(starts, tl.getLineStart(vline))
                            blame?.getOrNull(line)?.takeIf { it.committed }?.let(onLineClick)
                        }
                    }
                }
            },
    ) {
        if (blame == null) {
            Text("blame…", color = dateColor, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
        } else {
            val padPx = with(density) { 8.dp.toPx() }
            val contentWidthPx = with(density) { (GUTTER_WIDTH - 12.dp).toPx() }
            val hover = hoverLine
            Canvas(modifier = Modifier.fillMaxSize()) {
                val tl = layout ?: return@Canvas
                val scroll = scrollState.value.toFloat()
                val viewBottom = size.height
                var prevSha: String? = null
                var band = false
                for (line in blame.indices) {
                    if (line >= starts.size) break
                    val startOff = starts[line]
                    val vline = tl.getLineForOffset(startOff)
                    val top = tl.getLineTop(vline) - scroll
                    val bottom = tl.getLineBottom(vline) - scroll
                    val info = blame[line]
                    // Toggle the band whenever the commit changes, so runs of lines from one commit
                    // share a shade and the eye can group them at a glance.
                    if (info.sha != prevSha) { band = !band; prevSha = info.sha }
                    if (bottom < 0f || top > viewBottom) continue
                    val rowH = bottom - top
                    if (band) drawRect(bandColor, topLeft = Offset(0f, top), size = Size(size.width, rowH))
                    if (line == hover) drawRect(hoverBand, topLeft = Offset(0f, top), size = Size(size.width, rowH))

                    if (info.committed) {
                        val date = DATE_FMT.format(Instant.ofEpochSecond(info.whenEpochSeconds))
                        val dateLayout = measurer.measure(
                            date,
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = GUTTER_FONT_SIZE, color = dateColor),
                        )
                        drawText(dateLayout, topLeft = Offset(padPx, top))
                        val dateW = dateLayout.size.width
                        drawText(
                            measurer,
                            info.author,
                            topLeft = Offset(padPx + dateW + with(density) { 6.dp.toPx() }, top),
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = GUTTER_FONT_SIZE, color = authorColor),
                            size = Size(contentWidthPx - dateW, rowH),
                            overflow = TextOverflow.Clip,
                            maxLines = 1,
                            softWrap = false,
                        )
                    } else {
                        drawText(
                            measurer,
                            "uncommitted",
                            topLeft = Offset(padPx, top),
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = GUTTER_FONT_SIZE, color = uncommittedColor),
                            size = Size(contentWidthPx, rowH),
                            overflow = TextOverflow.Clip,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                // Right-hand divider separating the gutter from the editor body.
                drawRect(borderColor, topLeft = Offset(size.width - 1f, 0f), size = Size(1f, size.height))
            }

            // Detail card: the hovered line's full commit summary, anchored to the right of the
            // cursor so it spills over the editor rather than being clipped by the narrow gutter.
            val hoveredInfo = hover?.let { blame.getOrNull(it) }
            if (hoveredInfo != null) {
                BlameTooltip(hoveredInfo, hoverPos, isDark)
            }
        }
    }
}

@Composable
private fun BlameTooltip(info: BlameLine, anchor: Offset, isDark: Boolean) {
    val bg = if (isDark) Color(0xFF2B2D30) else Color(0xFFFDFDFE)
    val border = if (isDark) Color(0xFF4A4D54) else Color(0xFFC9CCD3)
    val fg = if (isDark) Color(0xFFC8CDD6) else Color(0xFF22262C)
    val muted = if (isDark) Color(0xFF8A909C) else Color(0xFF6B7480)
    val offset = IntOffset(GUTTER_WIDTH.value.roundToInt() + 8, anchor.y.roundToInt() + 14)
    Popup(offset = offset) {
        val header = buildString {
            info.shortSha?.let { append(it); append("  ") }
            if (info.whenEpochSeconds > 0) {
                append(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochSecond(info.whenEpochSeconds)),
                )
            }
        }
        Column(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(header, color = muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(info.author, color = muted, fontSize = 11.sp)
            Text(info.summary, color = fg, fontSize = 12.sp)
        }
    }
}
