package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/**
 * Restarts nop from the build on disk, every window with it, with the agents and terminals carried
 * across still running — see [iondrive.nop.RestartInPlace]. Beside the theme toggle, because it is
 * about nop and not about anything in the window.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RestartButton(onRestart: () -> Unit, modifier: Modifier = Modifier) {
    val tint = if (JewelTheme.isDark) ProjectIconTintDark else ProjectIconTintLight
    Box(modifier = modifier.padding(vertical = 8.dp)) {
        Tooltip(tooltip = { Text("Restart nop — agents and terminals keep running") }) {
            IconButton(onClick = onRestart) {
                Canvas(Modifier.size(16.dp)) { drawRestartIcon(tint) }
            }
        }
    }
}

/** A circular arrow, open at the top right where its head points round. */
internal fun DrawScope.drawRestartIcon(tint: Color) {
    val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawArc(
        color = tint,
        startAngle = -60f,
        sweepAngle = 300f,
        useCenter = false,
        topLeft = Offset(2.8f, 2.8f),
        size = Size(10.4f, 10.4f),
        style = stroke,
    )
    // The head, at the arc's start (−60°, the top right), pointing on round the circle.
    drawPath(
        path = ComposePath().apply {
            moveTo(10.2f, 1.6f)
            lineTo(10.7f, 3.6f)
            lineTo(8.6f, 4.1f)
        },
        color = tint,
        style = stroke,
    )
}
