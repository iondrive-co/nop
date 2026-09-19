package iondrive.nop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.agent.Activity
import kotlinx.coroutines.delay
import org.jetbrains.jewel.ui.component.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The colours an agent's state is drawn in, in both themes.
 *
 * Amber for a question and green for a finished turn, as in clio, which the same person reads the
 * same CLIs through: amber is "blocked on you", green is "your move when you like". Neither is red,
 * because neither is anything having gone wrong.
 */
internal class AgentStatusColors(isDark: Boolean) {
    val working = if (isDark) Color(0xFF548AF7) else Color(0xFF3574F0)
    val asking = if (isDark) Color(0xFFE5C07B) else Color(0xFFD6950A)

    /** The question mark inside the amber disc: dark on it in both themes, which is what reads. */
    val onAsking = Color(0xFF1F2329)
    val idle = if (isDark) Color(0xFF6BC273) else Color(0xFF3F9A48)
    val quiet = if (isDark) Color(0xFF7A7E85) else Color(0xFF70757D)

    /** A label in the state's colour: the same hues, darkened on white so the text stays legible. */
    fun label(activity: Activity, isDark: Boolean): Color? = when (activity) {
        Activity.Asking -> if (isDark) asking else Color(0xFFA36D00)
        Activity.Idle -> if (isDark) idle else Color(0xFF2F7D37)
        else -> null
    }
}

/**
 * How long a state that has not been seen pulses for once it starts. Long enough to catch the eye of
 * somebody in the room, short enough not to nag the one who has walked away — the mark stays after.
 */
private const val PULSE_MS = 8_000L

/**
 * Whether a mark should be pulsing now: unseen, and within [PULSE_MS] of the state beginning.
 * Recomposes itself false when the time runs out.
 */
@Composable
internal fun rememberPulsing(unseen: Boolean, since: Long): Boolean {
    var pulsing by remember(unseen, since) {
        mutableStateOf(unseen && System.currentTimeMillis() - since < PULSE_MS)
    }
    LaunchedEffect(pulsing, since) {
        if (!pulsing) return@LaunchedEffect
        delay((since + PULSE_MS - System.currentTimeMillis()).coerceAtLeast(0))
        pulsing = false
    }
    return pulsing
}

/**
 * The mark an agent tab carries in front of its name, saying what the session is doing.
 *
 * Each state has a *shape* of its own and not only a colour — a spinning arc, a disc with a
 * question mark, a dot, a square, an empty ring — so they tell apart on a glance at a crowded
 * strip, and for anyone who does not see the colours. [unseen] fills the ones that have a hollow
 * form: a green ring is a finished turn somebody has already looked at, a green dot is one nobody
 * has.
 */
@Composable
internal fun AgentStatusMark(
    activity: Activity,
    unseen: Boolean,
    since: Long,
    isDark: Boolean,
    size: Dp = 14.dp,
) {
    val colors = remember(isDark) { AgentStatusColors(isDark) }
    val pulsing = rememberPulsing(unseen && activity != Activity.Ended, since)
    val pulse = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "agent-pulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(650, easing = LinearEasing), RepeatMode.Reverse),
            label = "agent-pulse-alpha",
        ).value
    } else 1f

    Box(modifier = Modifier.size(size).alpha(pulse), contentAlignment = Alignment.Center) {
        when (activity) {
            Activity.Working -> {
                val transition = rememberInfiniteTransition(label = "agent-spin")
                val turn by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                    label = "agent-spin-angle",
                )
                Canvas(Modifier.size(size * 0.8f)) {
                    val stroke = 1.8.dp.toPx()
                    val inset = stroke / 2
                    rotate(turn) {
                        drawArc(
                            color = colors.working.copy(alpha = 0.25f),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = Size(this.size.width - stroke, this.size.height - stroke),
                            style = Stroke(stroke),
                        )
                        drawArc(
                            color = colors.working,
                            startAngle = 0f,
                            sweepAngle = 110f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = Size(this.size.width - stroke, this.size.height - stroke),
                            style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                }
            }

            Activity.Asking -> Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(size)) { drawCircle(colors.asking) }
                Text(
                    "?",
                    color = colors.onAsking,
                    fontSize = (size.value * 0.72f).sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Activity.Idle -> Canvas(Modifier.size(size * 0.6f)) {
                if (unseen) {
                    drawCircle(colors.idle)
                } else {
                    val stroke = 1.5.dp.toPx()
                    drawCircle(colors.idle, radius = this.size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
                }
            }

            Activity.Ended -> Canvas(Modifier.size(size * 0.5f)) {
                drawRect(if (unseen) colors.quiet else colors.quiet.copy(alpha = 0.6f))
            }

            Activity.Asleep -> Canvas(Modifier.size(size * 0.6f)) {
                val stroke = 1.3.dp.toPx()
                drawCircle(
                    colors.quiet,
                    radius = this.size.minDimension / 2 - stroke / 2,
                    style = Stroke(stroke, pathEffect = androidx.compose.ui.graphics.PathEffect
                        .dashPathEffect(floatArrayOf(2.dp.toPx(), 1.6.dp.toPx()))),
                )
            }

            Activity.Running -> Canvas(Modifier.size(size * 0.4f)) { drawCircle(colors.quiet) }
        }
    }
}

/**
 * The words for a state, as the session bar says it: what the agent is doing and, for the states
 * that are waiting on someone, since when. "Waiting for your answer · asked 21:03, 11 h 45 min ago"
 * is the sentence that would have saved a night.
 */
internal fun activityText(activity: Activity, since: Long, now: Long, worked: Boolean): String = when (activity) {
    Activity.Asleep -> "Asleep — starts when you open it"
    Activity.Running -> "Running"
    Activity.Working -> "Working"
    Activity.Asking -> "Waiting for your answer · asked ${clock(since, now)}"
    Activity.Idle -> if (worked) "Finished its turn · ${clock(since, now)}" else "Ready"
    Activity.Ended -> "Ended · ${clock(since, now)}"
}

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE HH:mm")

/**
 * When [since] was, and how long ago: "21:03, 11 h 45 min ago". Under a minute is "just now", which
 * is all anyone needs to know about a state that has only just begun. A different day gets its name.
 */
internal fun clock(since: Long, now: Long): String {
    val elapsed = (now - since).coerceAtLeast(0) / 1000
    if (elapsed < 60) return "just now"
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(since).atZone(zone)
    val sameDay = at.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val stamp = (if (sameDay) TIME else DAY_TIME).format(at)
    val minutes = elapsed / 60
    val ago = when {
        minutes < 60 -> "$minutes min"
        minutes < 48 * 60 -> "${minutes / 60} h ${minutes % 60} min"
        else -> "${minutes / (24 * 60)} days"
    }
    return "$stamp, $ago ago"
}

/** The time, ticking every [period] — for text that says how long ago something was. */
@Composable
internal fun rememberNow(period: Long = 30_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(period) {
        while (true) {
            delay(period)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * What a project's agents have to say to a user who is in another project: the loudest of them, or
 * null for nothing worth a mark on the project tab.
 *
 * A question comes first, and shows whether or not it has been seen — an agent blocked on the user
 * stays blocked however many times they have looked at it. After that, a turn or a run that ended
 * unseen. A working agent says nothing here: the project bar is for what needs the user, and a row
 * of projects all spinning would bury the one that does.
 */
data class ProjectAgentNews(val activity: Activity, val unseen: Boolean)

fun projectAgentNews(sessions: List<iondrive.nop.agent.AgentSession>): ProjectAgentNews? {
    val asking = sessions.filter { it.activity == Activity.Asking }
    if (asking.isNotEmpty()) return ProjectAgentNews(Activity.Asking, asking.any { it.unseen })
    val stopped = sessions.firstOrNull { it.unseen && (it.activity == Activity.Idle || it.activity == Activity.Ended) }
    return stopped?.let { ProjectAgentNews(it.activity, unseen = true) }
}
