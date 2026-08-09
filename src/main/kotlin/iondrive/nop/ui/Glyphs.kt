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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/**
 * The handful of tiny vector glyphs nop draws itself rather than shipping an icon for, and the close
 * affordance built on one of them. Shared by the two grouped strips — the vertical project rail and
 * the horizontal editor tab bar — so a group chevron or a close cross looks the same in both.
 */
internal fun DrawScope.drawPlusIcon(tint: Color) {
    val c = size.width / 2f
    drawLine(tint, Offset(c, 2.5f), Offset(c, size.height - 2.5f), strokeWidth = 1.5f, cap = StrokeCap.Round)
    drawLine(tint, Offset(2.5f, c), Offset(size.width - 2.5f, c), strokeWidth = 1.5f, cap = StrokeCap.Round)
}

internal fun DrawScope.drawCloseIcon(tint: Color) {
    val pad = 0.5f
    drawLine(tint, Offset(pad, pad), Offset(size.width - pad, size.height - pad), strokeWidth = 1.3f, cap = StrokeCap.Round)
    drawLine(tint, Offset(size.width - pad, pad), Offset(pad, size.height - pad), strokeWidth = 1.3f, cap = StrokeCap.Round)
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
