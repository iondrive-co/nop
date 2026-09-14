package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The handful of tiny vector glyphs nop draws itself rather than shipping an icon for, plus the
 * close affordance and the selected-tab underline built on them. Shared by the two grouped strips —
 * the project bar along the top of the window and the editor tab bar below it — so a group chevron,
 * a close cross or an accent rule looks the same in both.
 */
internal fun DrawScope.drawPlusIcon(tint: Color) {
    val c = size.width / 2f
    drawLine(tint, Offset(c, 2.5f), Offset(c, size.height - 2.5f), strokeWidth = 1.5f, cap = StrokeCap.Round)
    drawLine(tint, Offset(2.5f, c), Offset(size.width - 2.5f, c), strokeWidth = 1.5f, cap = StrokeCap.Round)
}

/**
 * The windows mark: two overlapping frames, the way every desktop draws "the other windows". Sits
 * beside the "+" in the project bar and opens the window picker.
 */
internal fun DrawScope.drawWindowsIcon(tint: Color) {
    val w = size.width
    val h = size.height
    val stroke = 1.3f
    // The frame behind, offset up and to the right; only its two outer edges show past the front one.
    drawLine(tint, Offset(w * 0.3f, h * 0.12f), Offset(w, h * 0.12f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(tint, Offset(w, h * 0.12f), Offset(w, h * 0.7f), strokeWidth = stroke, cap = StrokeCap.Round)
    // The frame in front, drawn whole, with a title bar so it reads as a window and not a box.
    val left = 0f
    val top = h * 0.3f
    val right = w * 0.7f
    val bottom = h * 0.98f
    drawLine(tint, Offset(left, top), Offset(right, top), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(tint, Offset(left, bottom), Offset(right, bottom), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(tint, Offset(left, top), Offset(left, bottom), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(tint, Offset(right, top), Offset(right, bottom), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(tint, Offset(left, h * 0.5f), Offset(right, h * 0.5f), strokeWidth = stroke, cap = StrokeCap.Round)
}

internal fun DrawScope.drawCloseIcon(tint: Color) {
    val pad = 0.5f
    drawLine(tint, Offset(pad, pad), Offset(size.width - pad, size.height - pad), strokeWidth = 1.3f, cap = StrokeCap.Round)
    drawLine(tint, Offset(size.width - pad, pad), Offset(pad, size.height - pad), strokeWidth = 1.3f, cap = StrokeCap.Round)
}

/**
 * The word-wrap mark: two full-width lines with a third that turns back on itself under an arrow —
 * the "this line continues on the next one" symbol every editor uses for the setting.
 */
internal fun DrawScope.drawWrapIcon(tint: Color) {
    val w = size.width
    val h = size.height
    val stroke = 1.3f
    // Two plain lines above, standing for text that fits.
    drawLine(tint, Offset(0f, h * 0.15f), Offset(w, h * 0.15f), strokeWidth = stroke, cap = StrokeCap.Round)
    // The wrapping line: runs out to the right margin, hooks down and comes back left to the arrow.
    drawLine(tint, Offset(0f, h * 0.45f), Offset(w * 0.85f, h * 0.45f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(tint, Offset(w * 0.85f, h * 0.45f), Offset(w * 0.85f, h * 0.8f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(tint, Offset(w * 0.85f, h * 0.8f), Offset(w * 0.2f, h * 0.8f), strokeWidth = stroke, cap = StrokeCap.Round)
    // Arrowhead on the returning end, symmetric about the line it sits on.
    drawLine(tint, Offset(w * 0.2f, h * 0.8f), Offset(w * 0.4f, h * 0.6f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(tint, Offset(w * 0.2f, h * 0.8f), Offset(w * 0.4f, h), strokeWidth = stroke, cap = StrokeCap.Round)
}

// A disclosure chevron: ">" (points right) when the group is collapsed, "v" (points down) when it's
// expanded — the usual "click to reveal what's underneath" convention.
internal fun DrawScope.drawDisclosure(tint: Color, collapsed: Boolean) {
    val w = size.width
    val h = size.height
    if (collapsed) {
        drawLine(tint, Offset(w * 0.35f, h * 0.15f), Offset(w * 0.7f, h * 0.5f), strokeWidth = 1.3f, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.7f, h * 0.5f), Offset(w * 0.35f, h * 0.85f), strokeWidth = 1.3f, cap = StrokeCap.Round)
    } else {
        drawLine(tint, Offset(w * 0.15f, h * 0.35f), Offset(w * 0.5f, h * 0.7f), strokeWidth = 1.3f, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.5f, h * 0.7f), Offset(w * 0.85f, h * 0.35f), strokeWidth = 1.3f, cap = StrokeCap.Round)
    }
}

/** A small "x" that lights up under the pointer. Closes a project tab, or an editor tab. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CloseButton(isDark: Boolean, onClose: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val base = if (isDark) Color(0xFF7A7E85) else Color(0xFF6B7079)
    val tint = if (hovered) {
        if (isDark) Color(0xFFDFE1E5) else Color(0xFF1F2329)
    } else base
    val bg = if (hovered) {
        if (isDark) Color(0xFF393B40) else Color(0xFFD0D3D8)
    } else Color.Transparent
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .hoverable(interaction)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(8.dp)) { drawCloseIcon(tint) }
    }
}

/**
 * An accent rule along a tab's bottom edge, marking a selected or active one.
 *
 * Painted rather than laid out: a strip may measure its children against an unbounded width, and a
 * `fillMaxWidth()` underline inside one is silently a no-op — it sizes to nothing and never appears.
 * Drawing after the content sidesteps the constraint entirely.
 */
internal fun Modifier.tabUnderline(show: Boolean, color: Color, thickness: Dp): Modifier =
    if (!show) this else drawWithContent {
        drawContent()
        val height = thickness.toPx()
        drawRect(
            color = color,
            topLeft = Offset(0f, size.height - height),
            size = Size(size.width, height),
        )
    }
