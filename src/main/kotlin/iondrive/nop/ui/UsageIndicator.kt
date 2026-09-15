package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import iondrive.nop.agent.Account
import iondrive.nop.agent.UsageReading
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/**
 * Every configured account's remaining quota, in the window's bottom-right corner, whatever tab is
 * showing.
 *
 * It lives outside the tool panel because it is global state and because the reason to look at it
 * is to decide what to do next — which account to start the next session on, or whether the one
 * running is about to hit a wall. Behind a tab, that decision costs a click you only make once you
 * already suspect the answer.
 *
 * The accounts flow across the strip rather than stacking, so five of them are two short rows and
 * not five tall ones. Height matters here beyond looks: this floats over a terminal, which is a
 * heavyweight AWT component that would otherwise be drawn straight over the top of it, so whatever
 * height this ends up is also the height the terminal below has to give back — see [onHeight].
 *
 * An account shows "…" until its first reading lands, because a bar at 0% and a bar not yet read
 * look identical and mean opposite things.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun UsageIndicator(
    accounts: List<Account>,
    readings: Map<String, UsageReading>,
    onClick: () -> Unit,
    /** The height this ended up, so whatever is underneath can stop drawing where it sits. */
    onHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    if (accounts.isEmpty()) {
        onHeight(0.dp)
        return
    }
    val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)

    FlowRow(
        modifier = modifier
            .padding(8.dp)
            // Wide enough for several accounts on one line, short of spanning the whole window: it
            // is drawn over the editor as well as the tool panel, and a strip across the full width
            // takes a slice out of both.
            .widthIn(max = 620.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(JewelTheme.globalColors.panelBackground)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .onSizeChanged { onHeight(with(density) { it.height.toDp() } + VERTICAL_MARGIN) },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        accounts.forEach { account ->
            key(account.name) { UsageChip(account, readings[account.name]) }
        }
        SettingsGlyph(onClick)
    }
}

/** The margin the strip adds around itself, which counts toward the room it needs. */
private val VERTICAL_MARGIN = 16.dp

@Composable
private fun UsageChip(account: Account, reading: UsageReading?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            account.name,
            color = AgentMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 110.dp),
        )
        // The session window is what runs out first and is what a decision turns on; the weekly one
        // is in the settings dialog. One bar per account keeps the strip to a line or two.
        val window = reading?.session ?: reading?.weekly
        when {
            reading == null -> Text("…", color = AgentMuted)
            reading.unavailable != null -> Text(reading.unavailable!!, color = AgentMuted)
            window == null -> Text("—", color = AgentMuted)
            else -> {
                UsageBar(window.percent)
                Text("${window.percent.toInt()}%", color = usageColor(window.percent))
                window.eta()?.let { Text("· $it", color = AgentMuted) }
            }
        }
    }
}

/**
 * The way into the accounts dialog.
 *
 * The whole strip has always opened it, but nothing said so — a panel of numbers does not look like
 * a button, and the only other route was a link inside a tab you cannot reach until you have an
 * account to launch. A gear at the end of the row is the smallest thing that answers "where are the
 * settings" without another menu.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SettingsGlyph(onClick: () -> Unit) {
    val tint = if (JewelTheme.isDark) ProjectIconTintDark else ProjectIconTintLight
    Tooltip(tooltip = { Text("Agent accounts") }) {
        Row(
            modifier = Modifier.clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚙", color = tint)
        }
    }
}

/** A small filled bar. Drawn rather than composed so it costs one node per account, not five. */
@Composable
private fun UsageBar(percent: Double) {
    val track = if (JewelTheme.isDark) Color(0xFF3C3F41) else Color(0xFFE3E5E9)
    val fill = usageColor(percent)
    Canvas(modifier = Modifier.size(width = 40.dp, height = 6.dp)) {
        val radius = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(color = track, cornerRadius = radius)
        val width = (size.width * (percent / 100.0)).toFloat().coerceIn(0f, size.width)
        if (width > 0f) {
            drawRoundRect(
                color = fill,
                topLeft = Offset.Zero,
                size = Size(width.coerceAtLeast(size.height), size.height),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * Green, amber, red. The thresholds are where the decision changes, not where the number looks
 * alarming: under 60% there is a session left in it, past 85% there probably isn't.
 */
private fun usageColor(percent: Double): Color = when {
    percent >= 85 -> ChangeColors.REMOVED
    percent >= 60 -> ChangeColors.CONFLICT
    else -> ChangeColors.ADDED
}

/**
 * Both windows spelled out, for the places with room for them: the picker row, where the question
 * is which account to spend the next hour on, and the settings dialog. The corner strip draws bars
 * instead — several accounts of prose there would be a wall.
 */
internal fun usageLine(reading: UsageReading?): String {
    if (reading == null) return "usage …"
    reading.unavailable?.let { return it }
    val parts = buildList {
        reading.session?.let { add("${it.percent.toInt()}% session" + (it.eta()?.let { e -> " · $e" } ?: "")) }
        reading.weekly?.let { add("${it.percent.toInt()}% week" + (it.eta()?.let { e -> " · $e" } ?: "")) }
    }
    return if (parts.isEmpty()) "no usage recorded" else parts.joinToString("   ")
}
