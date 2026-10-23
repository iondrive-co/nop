package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import iondrive.nop.Ago
import iondrive.nop.agent.Account
import iondrive.nop.agent.Backup
import iondrive.nop.agent.PaceSource
import iondrive.nop.agent.UsageReading
import iondrive.nop.agent.UsageWindow
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
 * below has to give back (see [onHeight]). Every row the accounts wrap onto is a row of agent
 * taken away, and across the whole region they fit on one.
 *
 * It still flows rather than forcing the line, because the region is draggable and a fixed share
 * per account narrows to nothing: squeezed, the names go first and a strip of anonymous bars says
 * less than nothing. A second row is the honest answer to a region too narrow for one.
 *
 * Two bars per account, session then week, and no words on either. A percentage and its reset are
 * four words each, and a handful of accounts' worth of them is a block that wraps; the pair of
 * bars is the same glance — roughly how full, and which window — in the width of a word, with the
 * clock marked on it besides, which the words do not say. The numbers
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
    /** Where agent sessions are backed up to, or null when they are not: see [BackupGlyph]. */
    backupDir: String? = null,
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
        // One item, so a wrap never puts the backup's mark on a different row from the gear.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            backupDir?.takeIf { it.isNotBlank() }?.let { BackupGlyph(it, onClick) }
            SettingsGlyph(onClick)
        }
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

/**
 * One account's name and bars. Hovering it lists every limit the account has (see [LimitsList]),
 * which the two bars leave out: another model family's allowance, a cap on one model.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun UsageChip(account: Account, reading: UsageReading?) {
    Tooltip(tooltip = { LimitsList(account, reading) }) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            account.name,
            color = AgentMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Wide enough to display configured account names (such as "claude-personal"
            // and "google-workspace") in full rather than cutting them short with an ellipsis.
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
            reading.session == null && reading.weekly == null -> Text(
                reading.note ?: "—",
                color = AgentMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            else -> {
                // Session first, week second. A window the account does not have — a weekly-only
                // plan such as Codex's Business Pro Lite has no session one — takes no space; the
                // chip's tooltip says which window a lone bar is.
                UsageBar("session", reading.session, reading.weeklyPace(), hover = false)
                UsageBar("week", reading.weekly, hover = false)
                if (reading.note != null) Text(staleAge(reading), color = AgentMuted, maxLines = 1)
            }
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
 * [weeklyPace], on the session bar, is where to aim the fill by the end of this window — and every
 * window after it — for the weekly window to last exactly to its reset (see
 * [UsageReading.weeklyPace]), drawn as a grey line, which holds still for the whole window. Short
 * of the bar's end it says the week cannot afford the whole session; past it, the week is going
 * unspent, and it sits at the end — as it does while the pace is not known at all.
 *
 * Drawn rather than composed so a strip of five accounts costs ten nodes and not fifty.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun UsageBar(
    label: String,
    window: UsageWindow?,
    weeklyPace: Double? = null,
    paceSource: PaceSource? = null,
    /** Whether the bar has a tooltip of its own; off where whatever holds it has one that says more. */
    hover: Boolean = true,
) {
    val track = if (JewelTheme.isDark) Color(0xFF3C3F41) else Color(0xFFE3E5E9)
    // Nothing, not an empty track: an empty track is what 0% looks like, and a plan with no such
    // window has not used none of it.
    if (window == null) return
    val fill = usageColor(window.percent)
    val elapsed = window.elapsed()
    val marker = with(LocalDensity.current) { MARKER_WIDTH.toPx() }
    val paceColor = if (JewelTheme.isDark) PACE_MARKER_DARK else PACE_MARKER_LIGHT
    val paceWidth = with(LocalDensity.current) { PACE_MARKER_WIDTH.toPx() }
    val bar = @Composable {
        Canvas(modifier = Modifier.size(BAR_WIDTH, BAR_HEIGHT)) {
            drawTrack(track)
            val width = (size.width * (window.percent / 100.0)).toFloat().coerceIn(0f, size.width)
            if (width > 0f) {
                // Clipped rather than drawn as a separate rounded rectangle so the leading edge
                // reflects the exact fraction of the bar without prematurely rounding inward.
                clipRect(right = width.coerceAtLeast(1.dp.toPx())) {
                    drawTrack(fill)
                }
            }
            if (weeklyPace != null) {
                // Under the black line, so the clock still reads where the two meet. Kept clear of the
                // rounded caps: at either end the cap narrows to nothing, and a line drawn there — where
                // it sits at the end of the bar whenever the pace is unknown or past 100% — is lost.
                val inset = size.height / 2
                val x = (size.width * (weeklyPace / 100.0)).toFloat()
                    .coerceIn(inset, size.width - inset)
                drawLine(
                    color = paceColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = paceWidth,
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
    if (hover) Tooltip(tooltip = { Text(tooltipLine(label, window, weeklyPace, paceSource)) }) { bar() } else bar()
}

private fun DrawScope.drawTrack(color: Color) {
    drawRoundRect(color = color, cornerRadius = CornerRadius(size.height / 2, size.height / 2))
}

/** "12m old", or "old" when the reading does not say when it was taken. */
internal fun staleAge(reading: UsageReading, now: Instant = Instant.now()): String {
    val at = reading.asOf ?: return "old"
    val minutes = Duration.between(at, now).toMinutes().coerceAtLeast(0)
    return if (minutes < 60) "${minutes}m old" else "${minutes / 60}h old"
}

/** "as of 14:02, usage API rate-limited", or null for a fresh reading. */
internal fun staleLine(reading: UsageReading): String? {
    val note = reading.note ?: return null
    val at = reading.asOf ?: return note
    return "as of ${CLOCK.format(at.atZone(ZoneId.systemDefault()))}, $note"
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

/** One bar's numbers, for the hover that asks what it is actually showing. */
internal fun tooltipLine(
    label: String,
    window: UsageWindow,
    weeklyPace: Double? = null,
    paceSource: PaceSource? = null,
): String {
    val eta = window.eta()?.let { ", resets in $it" } ?: ""
    val pace = paceNote(weeklyPace, paceSource)?.let { ", $it" } ?: ""
    return "${window.percent.toInt()}% of the $label window$eta$pace"
}

/** What the session bar's grey line means, or null when there is none. */
internal fun paceNote(weeklyPace: Double?, paceSource: PaceSource?): String? = when {
    weeklyPace == null -> null
    paceSource == null -> "weekly pace not known yet"
    paceSource == PaceSource.Transcripts ->
        "aim for ~${Math.round(weeklyPace)}% for the week (estimated from transcripts)"
    else -> "aim for ${Math.round(weeklyPace)}% for the week"
}

/**
 * Small enough that two per account and the name still fit a line, big enough to read a fill and
 * to tell the weekly pace line from the clock beside it.
 */
private val BAR_WIDTH = 52.dp
private val BAR_HEIGHT = 9.dp

private val NAME_MAX_WIDTH = 160.dp

/** Thick enough to read as a line at a glance, thin enough not to hide the fill under it. */
private val MARKER_WIDTH = 1.5.dp

/** Black in both themes: it is the one mark on the bar that is not about how full it is. */
private val NOW_MARKER = Color.Black

/** Grey: lighter than the black line, and dark enough in the light theme to stand off its track. */
private val PACE_MARKER_LIGHT = Color(0xFF6B7079)
private val PACE_MARKER_DARK = Color(0xFFC9CCD1)

/** A touch heavier than the black line, since its grey is the fainter of the two. */
private val PACE_MARKER_WIDTH = 2.dp

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
 * Whether the session backup is working, beside the gear: grey while it is, red with a "!" once it
 * is not. A backup that has stopped says nothing by itself — it is noticed the day it is needed — so
 * the place that says so has to be one that is on screen anyway. The accounts dialog has the detail.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BackupGlyph(dir: String, onClick: () -> Unit) {
    // Lateness is a matter of the clock, which no state change reports.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val health = backupHealth(dir, Backup.status, now)
    val tint = when {
        health.failing -> ChangeColors.REMOVED
        JewelTheme.isDark -> ProjectIconTintDark
        else -> ProjectIconTintLight
    }
    Tooltip(tooltip = { Text(health.line) }) {
        Row(
            modifier = Modifier.clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(14.dp)) { drawBackupIcon(tint, health.failing) }
        }
    }
}

/** What the backup mark says: whether to warn, and the sentence behind it. */
internal data class BackupHealth(val failing: Boolean, val line: String)

/**
 * A few missed runs. One late run is a slow share; three is a backup that has stopped, whether it
 * fails, hangs or never starts.
 */
private const val BACKUP_OVERDUE_MS = 3 * Backup.INTERVAL_MINUTES * 60_000

internal fun backupHealth(dir: String, status: Backup.Status, now: Long = System.currentTimeMillis()): BackupHealth {
    val last = status.lastSuccessAt
    val lastLine = last?.let { "The last backup that finished was ${Ago.of(it, now)}." } ?: "Nothing has been backed up yet."
    val waiting = status.password
    if (waiting != null && !status.running) {
        val why = if (waiting.refused) "${waiting.share} did not accept the password" else "it is waiting for the password to ${waiting.share}"
        return BackupHealth(true, "Not backing up to $dir: $why. $lastLine")
    }
    if (status.lastError != null && !status.running) {
        return BackupHealth(true, "The last backup to $dir failed: ${status.lastError}. $lastLine")
    }
    val overdue = (last ?: status.since)?.let { now - it > BACKUP_OVERDUE_MS } == true
    if (overdue) {
        val doing = if (status.running) "A backup to $dir is still running." else "No backup to $dir has run for a while."
        return BackupHealth(true, "$doing $lastLine It runs every ${Backup.INTERVAL_MINUTES} minutes.")
    }
    return BackupHealth(
        false,
        when {
            status.running -> "Backing up to $dir…"
            last != null -> "Backed up to $dir ${Ago.of(last, now)}."
            else -> "Backs up to $dir every ${Backup.INTERVAL_MINUTES} minutes, and after each run ends."
        },
    )
}

/**
 * A cloud with an arrow going up into it, or a "!" in place of the arrow when the backup is not
 * working. Drawn on a 16-unit grid scaled to whatever the canvas is.
 */
private fun DrawScope.drawBackupIcon(tint: Color, failing: Boolean) {
    scale(size.minDimension / 16f, pivot = Offset.Zero) {
        val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Three lobes, each arc running from where it meets the one before to where it meets the next.
        val cloud = Path().apply {
            moveTo(11.5f, 13f)
            lineTo(4.5f, 13f)
            arcTo(Rect(1.5f, 7f, 7.5f, 13f), 90f, 180f, false)
            arcTo(Rect(4.5f, 3.5f, 11.5f, 10.5f), 180f, 163.5f, false)
            arcTo(Rect(8f, 6f, 15f, 13f), 267.6f, 182.4f, false)
            close()
        }
        drawPath(cloud, tint, style = stroke)
        if (failing) {
            drawLine(tint, Offset(8f, 6.2f), Offset(8f, 8.6f), strokeWidth = 1.4f, cap = StrokeCap.Round)
            drawCircle(tint, radius = 0.9f, center = Offset(8f, 11f))
        } else {
            drawLine(tint, Offset(8f, 11.2f), Offset(8f, 6.8f), strokeWidth = 1.3f, cap = StrokeCap.Round)
            drawPath(
                Path().apply {
                    moveTo(6.4f, 8.4f)
                    lineTo(8f, 6.8f)
                    lineTo(9.6f, 8.4f)
                },
                tint,
                style = stroke,
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
    if (parts.isEmpty()) return reading.note ?: "no usage recorded"
    return parts.joinToString("   ") + (staleLine(reading)?.let { "   ($it)" } ?: "")
}

/** One row of the limits list: what the limit is, its window, and when it resets. */
internal data class LimitLine(val label: String, val window: UsageWindow?, val detail: String)

/**
 * Every limit [reading] knows, in the order [LimitsList] shows them: the session and weekly windows
 * nop governs the account by, then the provider's others. A reading with no windows is one line
 * saying why.
 */
internal fun limitLines(reading: UsageReading?, now: Instant = Instant.now()): List<LimitLine> {
    if (reading == null) return listOf(LimitLine("Not read yet", null, ""))
    reading.unavailable?.let { return listOf(LimitLine(it, null, "")) }
    fun line(label: String, window: UsageWindow) =
        LimitLine(label, window, window.eta(now)?.let { "resets in $it" } ?: "")
    val own = reading.pool?.let { "$it · " } ?: ""
    val lines = buildList {
        reading.session?.let { add(line("${own}session", it)) }
        reading.weekly?.let { add(line("${own}weekly", it)) }
        reading.others.forEach { add(line(it.label, it.window)) }
    }
    return lines.ifEmpty { listOf(LimitLine(reading.note ?: "No usage recorded", null, "")) }
}

/**
 * The chip's tooltip: every limit of the account, a column each for name, bar, percent and reset so
 * the rows line up, then what the grey line means and how old a stale reading is.
 */
@Composable
private fun LimitsList(account: Account, reading: UsageReading?) {
    val lines = limitLines(reading)
    val pace = reading?.weeklyPace()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(account.model?.let { "${account.name}  ($it)" } ?: account.name, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) { lines.forEach { Text(it.label) } }
            if (lines.any { it.window != null }) {
                Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                    lines.forEach { line ->
                        Box(Modifier.height(textLineHeight()), contentAlignment = Alignment.Center) {
                            val session = line.window != null && line.window === reading?.session
                            UsageBar(line.label, line.window, if (session) pace else null, hover = false)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP), horizontalAlignment = Alignment.End) {
                    lines.forEach { Text(it.window?.let { w -> "${w.percent.toInt()}%" } ?: "") }
                }
                Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                    lines.forEach { Text(it.detail, color = AgentMuted) }
                }
            }
        }
        if (reading?.session != null) paceNote(pace, reading.paceSource)?.let { Text("Session: $it", color = AgentMuted) }
        reading?.let(::staleLine)?.let { Text(it, color = AgentMuted) }
    }
}

private val ROW_GAP = 3.dp

/** The height of one line of tooltip text, so a bar sits level with the text beside it. */
@Composable
private fun textLineHeight(): Dp = with(LocalDensity.current) {
    JewelTheme.defaultTextStyle.lineHeight.takeIf { it.isSp }?.toDp() ?: 16.dp
}
