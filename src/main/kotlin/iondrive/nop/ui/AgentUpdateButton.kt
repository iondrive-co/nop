package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import iondrive.nop.agent.CliUpdates
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/**
 * Checks for and applies updates to installed vendor agent CLIs via [CliUpdates]. Sits beside
 * [ThemeToggleButton] in the project bar.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AgentUpdateButton(
    status: CliUpdates.Status = CliUpdates.status,
    onUpdate: () -> Unit = { CliUpdates.updateNow() },
    modifier: Modifier = Modifier,
) {
    val isDark = JewelTheme.isDark
    val updating = status is CliUpdates.Status.Updating
    val failed = status is CliUpdates.Status.Done && status.results.any { !it.outcome.success }
    val tint = when {
        updating -> AgentStatusColors(isDark).working
        failed -> ChangeColors.REMOVED
        isDark -> ProjectIconTintDark
        else -> ProjectIconTintLight
    }
    Box(modifier = modifier.padding(vertical = 8.dp)) {
        Tooltip(tooltip = { Text(agentUpdateTooltip(status)) }) {
            IconButton(
                onClick = { if (!updating) onUpdate() },
            ) {
                Canvas(Modifier.size(16.dp)) { drawAgentUpdateIcon(tint) }
            }
        }
    }
}

/** Formats the current [CliUpdates.Status] for the button's tooltip. */
internal fun agentUpdateTooltip(status: CliUpdates.Status): String = when (status) {
    CliUpdates.Status.Idle -> "Update agents — check for CLI updates and refresh model lists"
    is CliUpdates.Status.Updating -> when {
        status.refreshingModels -> "Refreshing agent model lists…"
        status.provider != null -> "Updating ${status.provider.label}…"
        else -> "Checking for agent updates…"
    }
    is CliUpdates.Status.Done -> if (status.results.isEmpty()) {
        "No updatable agent CLIs found (Codex or Claude Code); model lists refreshed.\nClick to check again."
    } else {
        status.results.joinToString("\n") { (provider, outcome) ->
            "${provider.label}: ${summarizeAgentUpdate(outcome)}"
        } + "\nModel lists refreshed. Click to check again."
    }
}

private fun summarizeAgentUpdate(outcome: CliUpdates.Outcome): String {
    val body = if (outcome.success) {
        outcome.detail.removePrefix("exit 0: ").takeIf { it != "exit 0" } ?: "up to date"
    } else {
        outcome.detail
    }
    val line = body.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: body
    return if (line.length > 140) line.take(139) + "…" else line
}

/** A downward arrow into an open tray, scaled from a 16-unit grid. */
internal fun DrawScope.drawAgentUpdateIcon(tint: Color) {
    scale(size.minDimension / 16f, pivot = Offset.Zero) {
        val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(tint, Offset(8f, 2.2f), Offset(8f, 10.2f), strokeWidth = 1.3f, cap = StrokeCap.Round)
        drawPath(
            ComposePath().apply {
                moveTo(5.2f, 7.4f)
                lineTo(8f, 10.2f)
                lineTo(10.8f, 7.4f)
            },
            color = tint,
            style = stroke,
        )
        drawPath(
            ComposePath().apply {
                moveTo(2.6f, 10.8f)
                lineTo(2.6f, 13.4f)
                lineTo(13.4f, 13.4f)
                lineTo(13.4f, 10.8f)
            },
            color = tint,
            style = stroke,
        )
    }
}
