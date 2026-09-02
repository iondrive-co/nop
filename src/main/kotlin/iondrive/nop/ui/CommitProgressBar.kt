package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.git.CommitProgress
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The commit button's face while a commit runs: a progress bar with the percentage and an ETA
 * written inside it, replacing the "Committing…" label that used to be all a multi-minute commit
 * had to show. Sized to its widest possible label so the readout doesn't shuffle the button's
 * width as the numbers change.
 *
 * Falls back to a bar with no fill and a phase-plus-elapsed label whenever the work isn't
 * measurable — see [CommitProgress.bytesTotal].
 */
@Composable
internal fun CommitProgressBar(progress: CommitProgress?, nowMillis: Long) {
    val fraction = progress?.fraction ?: 0f
    val dark = JewelTheme.isDark
    // The bar sits inside a disabled button, so both halves of the track have to carry legible
    // text: one tinted fill and one text colour, rather than a saturated accent that would need
    // the label to change colour where the fill ends.
    val track = if (dark) Color(0xFF393B40) else Color(0xFFD6DAE0)
    val fill = if (dark) Color(0xFF2E5490) else Color(0xFF9DC0F5)
    val labelColor = if (dark) Color(0xFFDFE1E5) else Color(0xFF1E1F22)
    val style = JewelTheme.defaultTextStyle.copy(fontSize = PROGRESS_FONT_SIZE, color = labelColor)

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val barWidth = remember(style, density) {
        val widest = WIDEST_LABELS.maxOf { measurer.measure(it, style).size.width }
        with(density) { widest.toDp() } + PROGRESS_LABEL_PADDING * 2
    }

    Box(
        modifier = Modifier
            .width(barWidth)
            .height(PROGRESS_BAR_HEIGHT)
            .clip(RoundedCornerShape(3.dp))
            .background(track)
            // Drawn rather than laid out as a child Box: a fraction of 0 has to leave no trace,
            // and a rectangle of zero width is simpler to guarantee than a zero-weight layout.
            .drawBehind { drawRect(fill, size = Size(size.width * fraction, size.height)) }
            .padding(horizontal = PROGRESS_LABEL_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            commitProgressLabel(progress, nowMillis),
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val PROGRESS_BAR_HEIGHT = 16.dp
private val PROGRESS_LABEL_PADDING = 5.dp
private val PROGRESS_FONT_SIZE = 11.sp

/**
 * The longest labels [commitProgressLabel] can produce, which is what the bar is sized from. An
 * hours-long ETA is wider still, but a commit that reports one has bigger problems than a clipped
 * label, and sizing for it would leave the button padded out for every ordinary commit.
 */
private val WIDEST_LABELS = listOf("100% · 59m left", "Committing… 59m")

/**
 * A clock that ticks while [running], for driving elapsed and ETA readouts. Progress snapshots
 * arrive only when bytes land, and one 400 MB file can be a minute of silence — without a clock of
 * its own the readout would freeze exactly when the user is most likely to be watching it.
 */
@Composable
internal fun rememberTickingClock(running: Boolean): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    if (running) {
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(TICK_INTERVAL_MS)
            }
        }
    }
    return now
}

/** Half a second, so a whole-second readout is never more than half a second stale. */
private const val TICK_INTERVAL_MS = 500L

/**
 * What the commit button says at [nowMillis]: `"47% · 4m left"` once there is a measurable total
 * and something has landed against it, otherwise the phase and how long it has been running
 * (`"Staging… 1m"`).
 *
 * Deliberately coarse. The percentage is floored and the ETA is quoted in whole minutes past the
 * first minute, because a readout inside a button is glanced at, not watched: a figure that
 * churns every frame reads as noise, and an ETA derived from an average rate is not accurate to
 * the second anyway.
 */
internal fun commitProgressLabel(progress: CommitProgress?, nowMillis: Long): String {
    if (progress == null) return "Committing…"
    val fraction = progress.fraction
    if (fraction != null && progress.bytesDone > 0L && fraction < 1f) {
        // Floored, and held at 99% until the writes are actually done: a bar that reads 100% while
        // the button is still greyed out looks stuck rather than nearly finished.
        val percent = (fraction * 100).toInt().coerceIn(0, 99)
        val eta = etaMillis(progress, nowMillis) ?: return "$percent%"
        return "$percent% · ${briefDuration(eta, roundUp = true)} left"
    }
    val elapsed = nowMillis - progress.startedAtMillis
    if (elapsed < 1_000L) return "${progress.phase.label}…"
    return "${progress.phase.label}… ${briefDuration(elapsed, roundUp = false)}"
}

/**
 * The tooltip behind the bar: the counts and sizes the bar has no room for. Multi-line, newest
 * fact last, so the phase reads as a heading.
 */
internal fun commitProgressDetail(progress: CommitProgress?, nowMillis: Long): String {
    if (progress == null) return "Committing…"
    val lines = mutableListOf<String>()
    lines += when (progress.phase) {
        CommitProgress.Phase.CHECKING -> "Checking the working tree for changes that appeared since you last looked"
        CommitProgress.Phase.STAGING -> "Staging ${plural(progress.filesTotal, "file")}"
        CommitProgress.Phase.WRITING ->
            "Writing ${progress.filesDone} of ${plural(progress.filesTotal, "file")} into the object store"
        CommitProgress.Phase.COMMITTING -> "Writing the index, then the commit"
        CommitProgress.Phase.REFRESHING -> "Reloading the file list"
    }
    if (progress.bytesTotal > 0L) {
        lines += "${formatBytes(progress.bytesDone)} of ${formatBytes(progress.bytesTotal)}"
    }
    val elapsed = (nowMillis - progress.startedAtMillis).coerceAtLeast(0L)
    val eta = etaMillis(progress, nowMillis)
    lines += "${preciseDuration(elapsed)} elapsed" +
        if (eta != null) " · about ${preciseDuration(eta)} left" else ""
    return lines.joinToString("\n")
}

/**
 * Milliseconds still to go, from the average rate so far, or null when there is nothing to
 * extrapolate from: no measurable total, nothing done yet, everything done, or too little elapsed
 * for the rate to mean anything. The first seconds of a commit are all fixed costs — the plan
 * walk, the index lock — and an ETA drawn from them is off by an order of magnitude.
 */
private fun etaMillis(progress: CommitProgress, nowMillis: Long): Long? {
    val remainingBytes = progress.bytesTotal - progress.bytesDone
    if (progress.bytesTotal <= 0L || progress.bytesDone <= 0L || remainingBytes <= 0L) return null
    val elapsed = nowMillis - progress.startedAtMillis
    if (elapsed < ETA_MIN_ELAPSED_MS) return null
    return (elapsed.toDouble() * remainingBytes / progress.bytesDone).roundToLong()
}

private const val ETA_MIN_ELAPSED_MS = 2_000L

/** One unit, for a bar with room for about fifteen characters: `"12s"`, `"4m"`, `"1h 5m"`. */
private fun briefDuration(millis: Long, roundUp: Boolean): String {
    val seconds = if (roundUp) (millis + 999) / 1000 else millis / 1000
    if (seconds < 60) return "${seconds.coerceAtLeast(0)}s"
    val minutes = if (roundUp) (seconds + 59) / 60 else seconds / 60
    if (minutes < 60) return "${minutes}m"
    return "${minutes / 60}h ${minutes % 60}m"
}

/** Two units, for the tooltip: `"12s"`, `"4m 20s"`, `"1h 05m"`. */
private fun preciseDuration(millis: Long): String {
    val seconds = millis / 1000
    if (seconds < 60) return "${seconds}s"
    if (seconds < 3600) return "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
    return "${seconds / 3600}h ${((seconds % 3600) / 60).toString().padStart(2, '0')}m"
}

/** Sizes the way a file manager does: 1024-based, one decimal until the number gets long. */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < BYTE_UNITS.lastIndex) {
        value /= 1024
        unit++
    }
    val rounded = String.format(Locale.ROOT, if (value >= 100) "%.0f" else "%.1f", value)
    return "$rounded ${BYTE_UNITS[unit]}"
}

private val BYTE_UNITS = listOf("B", "KB", "MB", "GB", "TB")
