package iondrive.nop.agent

import iondrive.nop.Log
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * How much of an account's weekly window one point of its session window costs, learnt from watching
 * the two move together.
 *
 * The session bar's black line says where usage should be to spend the session window exactly by its
 * reset. The question it cannot answer is whether spending the session window is affordable at all:
 * five-hour windows come round thirty-odd times a week, and the weekly cap is a handful of them. To put
 * the week's pace on the session bar, a session percent has to be turned into a weekly one, and no
 * provider says what that rate is. It shows in the readings, though: inside one session window, the
 * weekly percent rises by a steady share of what the session percent rises by.
 *
 * So each account's first reading in a session window is kept as an anchor, and once the session
 * percent has climbed [MIN_SESSION_DELTA] points past it, the weekly rise over the session rise is the
 * ratio. The percents arrive rounded to whole points, so a small rise says little, and a weekly rise of
 * nothing is taken as half a point rather than as free usage. The latest ratio is kept on disk, since it
 * is a property of the plan rather than of the moment, and a restart should not lose it.
 *
 * Until an account has been watched that long, an estimate from its transcripts stands in (see
 * [estimateOf]), and with neither the reading carries no ratio and the line sits at the end of the
 * bar.
 */
class WeeklyPace(private val file: () -> Path) {
    private data class Anchor(
        val sessionResetsAt: Instant,
        val session: Double,
        val weekly: Double,
        val weeklyResetsAt: Instant?,
    )

    private val anchors = ConcurrentHashMap<String, Anchor>()

    /** Ratios estimated from transcripts, for accounts not yet [PaceSource.Observed]. Not kept. */
    private val estimates = ConcurrentHashMap<String, Double>()

    @Volatile
    private var ratios: Map<String, Double>? = null

    /**
     * [reading] with [UsageReading.weeklyPerSession] filled in, learning from it when it is fresh and
     * [learn] is set.
     */
    fun annotate(account: String, reading: UsageReading, learn: Boolean = true): UsageReading {
        if (learn && reading.note == null && reading.unavailable == null) learn(account, reading)
        loaded()[account]?.let { return reading.copy(weeklyPerSession = it, paceSource = PaceSource.Observed) }
        estimates[account]?.let { return reading.copy(weeklyPerSession = it, paceSource = PaceSource.Transcripts) }
        return reading.copy(weeklyPerSession = null, paceSource = null)
    }

    /** Whether [account] still has no ratio of its own, so a transcript estimate is worth taking. */
    fun wantsEstimate(account: String): Boolean = account !in loaded()

    fun estimate(account: String, ratio: Double?) {
        if (ratio == null) estimates.remove(account) else estimates[account] = ratio
    }

    private fun learn(account: String, reading: UsageReading) {
        val session = reading.session ?: return
        val weekly = reading.weekly ?: return
        val sessionResetsAt = session.resetsAt ?: return
        val anchor = anchors[account]
        if (anchor == null || !sameWindows(anchor, session, weekly)) {
            anchors[account] = Anchor(sessionResetsAt, session.percent, weekly.percent, weekly.resetsAt)
            return
        }
        val ratio = ratioOf(session.percent - anchor.session, weekly.percent - anchor.weekly) ?: return
        val known = loaded()[account]
        if (known != null && Math.abs(known - ratio) <= known * 0.01) return
        val next = loaded() + (account to ratio)
        ratios = next
        save(next)
    }

    /**
     * Whether a reading is still inside the windows [anchor] was taken in. The reset times wobble by
     * seconds between readings, so a few minutes either way is the same window; a percent that fell is
     * a window that rolled over between two polls.
     */
    private fun sameWindows(anchor: Anchor, session: UsageWindow, weekly: UsageWindow): Boolean {
        val resetsAt = session.resetsAt ?: return false
        if (Duration.between(anchor.sessionResetsAt, resetsAt).abs() > SAME_RESET) return false
        if (session.percent < anchor.session - 1 || weekly.percent < anchor.weekly - 1) return false
        val weeklyAt = weekly.resetsAt
        if (anchor.weeklyResetsAt != null && weeklyAt != null &&
            Duration.between(anchor.weeklyResetsAt, weeklyAt).abs() > SAME_RESET
        ) {
            return false
        }
        return true
    }

    private fun loaded(): Map<String, Double> {
        ratios?.let { return it }
        val read = runCatching {
            val f = file()
            if (!Files.isRegularFile(f)) return@runCatching emptyMap()
            Files.readAllLines(f).mapNotNull { line ->
                val eq = line.lastIndexOf('=')
                if (eq <= 0) return@mapNotNull null
                val value = line.substring(eq + 1).trim().toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                line.substring(0, eq).trim() to value
            }.toMap()
        }.getOrDefault(emptyMap())
        ratios = read
        return read
    }

    private fun save(map: Map<String, Double>) {
        runCatching {
            val f = file()
            Files.createDirectories(f.parent)
            Files.writeString(f, map.entries.joinToString("") { "${it.key}=${it.value}\n" })
        }.onFailure { Log.warn("could not save the weekly pace: $it") }
    }

    companion object {
        /** Fewer session points than this and the rounding is most of the answer. */
        const val MIN_SESSION_DELTA: Double = 10.0

        private val SAME_RESET: Duration = Duration.ofMinutes(10)

        /** Weekly points per session point, or null while the session has not moved enough to say. */
        internal fun ratioOf(sessionDelta: Double, weeklyDelta: Double): Double? {
            if (sessionDelta < MIN_SESSION_DELTA || weeklyDelta < 0) return null
            return weeklyDelta.coerceAtLeast(0.5) / sessionDelta
        }

        /**
         * Weekly points per session point from token counts: the tokens the transcripts show spent
         * in this session window and this week, beside the percent each window reads. One session
         * point costs `sessionTokens / session` tokens and one weekly point `weekTokens / weekly`,
         * so their quotient is the ratio. Rough — models cost quota at different rates per token,
         * and work done on another machine is in the percents but not the transcripts — so it is
         * only taken while the percents are big enough for their rounding not to swamp it.
         */
        internal fun estimateOf(sessionTokens: Long, session: Double, weekTokens: Long, weekly: Double): Double? {
            if (sessionTokens <= 0 || weekTokens < sessionTokens) return null
            if (session < MIN_ESTIMATE_PERCENT || weekly < 1) return null
            return (weekly / weekTokens) / (session / sessionTokens)
        }

        /** Fewer session points than this and the rounding is most of the estimate. */
        private const val MIN_ESTIMATE_PERCENT = 5.0
    }
}

/** Where an account's weekly pace ratio came from: estimated from transcripts, or watched. */
enum class PaceSource { Transcripts, Observed }
