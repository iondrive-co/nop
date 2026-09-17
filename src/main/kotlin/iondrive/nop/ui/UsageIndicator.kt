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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import iondrive.nop.agent.Account
import iondrive.nop.agent.UsageReading
import iondrive.nop.agent.UsageWindow
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
 * One line, spanning the whole tool region — the agent on the left and the panel beside it —
 * rather than a block wrapped into the right-hand corner. Height matters here beyond looks: this
 * floats over a terminal, which is a heavyweight AWT component that would otherwise be drawn
 * straight over the top of it, so whatever height this ends up is also the height the terminal
 * below has to give back (see [onHeight]). Every row the accounts wrapped onto was a row of agent
 * taken away, and across the whole region they fit on one.
 *
 * It still flows rather than forcing the line, because the region is draggable and a fixed share
 * per account narrows to nothing: squeezed, the names go first and a strip of anonymous bars says
 * less than nothing. A second row is the honest answer to a region too narrow for one.
 *
 * Two bars per account, session then week, and no words on either. A percentage and its reset are
 * four words each, and a handful of accounts' worth of them is the block that was wrapping; the
 * pair of bars is the same glance — roughly how full, and which window — in the width of a word,
 * with the clock marked on it besides, which no amount of words was saying. The numbers
 * themselves are still spelled out where they are being *compared*: the picker row, where the
 * question is which account to spend the next hour on, and the accounts dialog (see [usageLine]),
 * with the tooltip here for a reading of one bar in passing.
 *
 * An account shows "…" until its first reading lands, because 0% and not-yet-read look identical
 * and mean opposite things.
 */
@Composable
fun UsageIndicator(
    accounts: List<Account>,
    readings: Map<String, UsageReading>,
    onClick: () -> Unit,
    /** The height this ended up, so whatever is underneath can stop drawing where it sits. */
    onHeight: (Dp) -> Unit,
    /**
     * How wide the tool region below is, which is how wide the strip draws. Zero until the region
     * has been measured, and then the strip falls back to a width that fits a few accounts.
     */
    spanWidth: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    if (accounts.isEmpty()) {
        onHeight(0.dp)
        return
    }
    val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
    val span = if (spanWidth > 0.dp) {
        Modifier.width((spanWidth - HORIZONTAL_MARGIN).coerceAtLeast(MIN_WIDTH))
    } else {
        // It is drawn over the editor as well as the tool panel, so an unmeasured strip stops short
        // of spanning the window rather than taking a slice out of both.
        Modifier.widthIn(max = FALLBACK_MAX_WIDTH)
    }

    FlowRow(
        modifier = modifier
            .padding(8.dp)
            .then(span)
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

/** The same margin on both sides, which the span has to give back so it fits the region. */
private val HORIZONTAL_MARGIN = 16.dp

/** Wide enough for a few accounts, for the moment before the region below has been measured. */
private val FALLBACK_MAX_WIDTH = 800.dp

/** Narrower than this and the strip is drawing nothing legible anyway. */
private val MIN_WIDTH = 120.dp

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
            // Wide enough to display configured account names (such as "claude-aloancloud"
            // and "google-aloancloud") in full rather than cutting them short with an ellipsis.
            modifier = Modifier.widthIn(max = NAME_MAX_WIDTH),
        )
        when {
            reading == null -> Text("…", color = AgentMuted)
            reading.unavailable != null -> Text(
                reading.unavailable!!,
                color = AgentMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            reading.session == null && reading.weekly == null -> Text("—", color = AgentMuted)
            else -> {
                // Session first, week second, and both places drawn even when the account answered
                // about only one of them: with no labels, position is the only thing that says
                // which window a bar is, so the pair cannot close up around a missing one.
                UsageBar("session", reading.session)
                UsageBar("week", reading.weekly)
            }
        }
    }
}

/**
 * One window: how much of it is spent, and where in it we are.
 *
 * The fill is the quota gone; the black line is the clock, at the point the window has reached
 * between the moment it opened and the moment it resets. Together they answer the question a bare
 * percentage cannot — 60% spent is comfortable an hour before the reset and a wall four hours
 * before it — and neither of them needs a word to say so.
 *
 * Drawn rather than composed so a strip of five accounts costs ten nodes and not fifty.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun UsageBar(label: String, window: UsageWindow?) {
    val track = if (JewelTheme.isDark) Color(0xFF3C3F41) else Color(0xFFE3E5E9)
    if (window == null) {
        Canvas(modifier = Modifier.size(BAR_WIDTH, BAR_HEIGHT)) { drawTrack(track) }
        return
    }
    val fill = usageColor(window.percent)
    val elapsed = window.elapsed()
    val marker = with(LocalDensity.current) { MARKER_WIDTH.toPx() }
    Tooltip(tooltip = { Text(tooltipLine(label, window)) }) {
        Canvas(modifier = Modifier.size(BAR_WIDTH, BAR_HEIGHT)) {
            drawTrack(track)
            val width = (size.width * (window.percent / 100.0)).toFloat().coerceIn(0f, size.width)
            if (width > 0f) {
                drawRoundRect(
                    color = fill,
                    topLeft = Offset.Zero,
                    // A sliver narrower than the bar is tall has no rounded end to draw and comes
                    // out as nothing at all, so the smallest fill is a dot rather than an empty bar.
                    size = Size(width.coerceAtLeast(size.height), size.height),
                    cornerRadius = CornerRadius(size.height / 2, size.height / 2),
                )
            }
            if (elapsed != null) {
                // Kept a half-stroke inside each end: at the start and the end of a window the line
                // would otherwise be drawn half outside the bar and read as thinner than it is.
                val x = (size.width * elapsed).toFloat().coerceIn(marker / 2, size.width - marker / 2)
                drawLine(
                    color = NOW_MARKER,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = marker,
                )
            }
        }
    }
}

private fun DrawScope.drawTrack(color: Color) {
    drawRoundRect(color = color, cornerRadius = CornerRadius(size.height / 2, size.height / 2))
}

/** One bar's numbers, for the hover that asks what it is actually showing. */
internal fun tooltipLine(label: String, window: UsageWindow): String {
    val eta = window.eta()?.let { ", resets in $it" } ?: ""
    return "${window.percent.toInt()}% of the $label window$eta"
}

/** Small enough that two per account and the name still fit a line, big enough to read a fill. */
private val BAR_WIDTH = 30.dp
private val BAR_HEIGHT = 7.dp

private val NAME_MAX_WIDTH = 160.dp

/** Thick enough to read as a line at a glance, thin enough not to hide the fill under it. */
private val MARKER_WIDTH = 1.5.dp

/** Black in both themes: it is the one mark on the bar that is not about how full it is. */
private val NOW_MARKER = Color.Black

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
 * Both windows on one line, for the places that want them as a sentence rather than as a row of
 * their own: the picker row, where the question is which account to spend the next hour on, and the
 * settings dialog.
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
