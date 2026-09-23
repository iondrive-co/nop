package iondrive.nop.agent

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * How often each account's provider is actually asked for usage, however many pollers are asking.
 *
 * Every project tab and every window polls for itself, and a project switch builds a new tab that
 * polls at once. Without this, a few quick switches sent each account's usage request several times
 * in a minute. Anthropic's usage endpoint answers a burst with 429 and a `Retry-After` of seconds,
 * and nop then showed "no answer from the usage API" until its next poll five minutes later.
 *
 * A good reading is handed back to every caller for [HOLD]. A provider that fails to answer is
 * backed off, doubling from [BACKOFF] up to [MAX_BACKOFF], and never sooner than its `Retry-After`.
 * While it is backed off, callers get the last good reading with a [UsageReading.note] saying why it
 * is not fresh. The reading keeps its own `asOf`, so [UsageReading.looksSpent] stops trusting it
 * once it is old, as it would any stale reading.
 *
 * Keyed by the caller, one key per account. [clock] exists for the tests.
 */
internal class UsageGate(private val clock: () -> Instant = Instant::now) {

    private class Held(
        /** What callers get until [until]. */
        val shown: UsageReading,
        /** The last reading the provider actually gave, if it has ever given one. */
        val good: UsageReading?,
        val until: Instant,
        /** Failures in a row, which is what the backoff doubles on. */
        val strikes: Int,
    )

    private val held = ConcurrentHashMap<String, Held>()

    /** Invalidates any held reading for [key]. */
    fun invalidate(key: String) {
        held.remove(key)
    }

    /** Clears all held readings. */
    fun clear() {
        held.clear()
    }

    /** The reading to give without asking the provider, or null when it is time to ask again. */
    fun held(key: String): UsageReading? {
        val entry = held[key] ?: return null
        return entry.shown.takeIf { clock().isBefore(entry.until) }
    }

    /** The provider answered with [reading]. Held for [HOLD] (or [BACKOFF] if spent), and returned. */
    fun answered(key: String, reading: UsageReading): UsageReading {
        // A signed-out answer drops the old numbers: they are not to come back as "stale" later.
        val good = reading.takeIf { it.unavailable == null }
        val holdDuration = when {
            reading.looksSpent(clock()) == true -> {
                val until = reading.spentUntil(clock())
                if (until != null) {
                    val remaining = Duration.between(clock(), until).coerceAtLeast(Duration.ofSeconds(15))
                    remaining.coerceAtMost(BACKOFF)
                } else {
                    BACKOFF
                }
            }
            else -> HOLD
        }
        held[key] = Held(reading, good, clock().plus(holdDuration), strikes = 0)
        return reading
    }

    /**
     * The provider did not answer, for [why]. Returns the last good reading with [why] as its note,
     * or a reading carrying only the note if there has never been one.
     */
    fun unanswered(key: String, why: String, retryAfter: Duration? = null): UsageReading {
        val before = held[key]
        val strikes = (before?.strikes ?: 0) + 1
        val doubled = BACKOFF.multipliedBy(1L shl (strikes - 1).coerceAtMost(MAX_DOUBLINGS))
        val wait = maxOf(doubled.coerceAtMost(MAX_BACKOFF), retryAfter ?: Duration.ZERO)
        val good = before?.good
        val shown = good?.copy(note = why) ?: UsageReading(null, null, null, note = why)
        held[key] = Held(shown, good, clock().plus(wait), strikes)
        return shown
    }

    private fun Duration.coerceAtMost(max: Duration): Duration = if (this > max) max else this
    private fun Duration.coerceAtLeast(min: Duration): Duration = if (this < min) min else this

    companion object {
        /**
         * How long a good reading is held. A little under the pollers' five minutes, so each poll
         * still asks, and a project switch in between gets the reading without asking.
         */
        val HOLD: Duration = Duration.ofMinutes(4)

        /** The first wait after a failure. The usage endpoint's 429s have come with ~20-second `Retry-After`s. */
        val BACKOFF: Duration = Duration.ofMinutes(1)

        /** The longest wait. Past this, the numbers on screen are too old to be much use anyway. */
        val MAX_BACKOFF: Duration = Duration.ofMinutes(15)

        private const val MAX_DOUBLINGS = 10
    }
}
