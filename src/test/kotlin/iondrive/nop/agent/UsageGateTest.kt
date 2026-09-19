package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * How often a provider is asked for usage, whoever is asking.
 *
 * The bug this exists for: every project switch polled every account at once, a few quick switches
 * got Anthropic's usage endpoint to answer 429, and the strip showed "no answer from the usage API"
 * for all the Claude accounts until the next poll.
 */
class UsageGateTest {

    private var now: Instant = Instant.parse("2026-09-19T04:00:00Z")
    private val gate = UsageGate { now }
    private val key = "Anthropic:/home/a"

    private fun reading(percent: Double) =
        UsageReading(UsageWindow(percent, null), UsageWindow(percent, null), asOf = now)

    private fun advance(by: Duration) {
        now = now.plus(by)
    }

    @Test
    fun `nothing is held before the first ask`() {
        assertNull(gate.held(key))
    }

    /** A project switch between two polls gets the reading without asking. */
    @Test
    fun `a good reading is handed back until the hold runs out`() {
        val good = gate.answered(key, reading(12.0))

        advance(UsageGate.HOLD.minusSeconds(1))
        assertSame(good, gate.held(key))

        advance(Duration.ofSeconds(1))
        assertNull(gate.held(key), "past the hold the next poll should ask")
    }

    @Test
    fun `accounts are held apart`() {
        gate.answered(key, reading(12.0))

        assertNull(gate.held("Anthropic:/home/b"))
    }

    /** The case the user saw: the numbers stay up, marked stale, rather than going blank. */
    @Test
    fun `a refusal keeps showing the last good numbers with the reason`() {
        val good = gate.answered(key, reading(12.0))
        advance(UsageGate.HOLD)

        val shown = gate.unanswered(key, "usage API rate-limited", Duration.ofSeconds(23))

        assertEquals(12.0, shown.session?.percent)
        assertEquals(good.asOf, shown.asOf, "a stale reading keeps the time it was taken")
        assertEquals("usage API rate-limited", shown.note)
        assertNull(shown.unavailable, "a rate limit is not a sign-out")
    }

    @Test
    fun `a refusal before any good reading shows only the reason`() {
        val shown = gate.unanswered(key, "usage API rate-limited")

        assertNull(shown.session)
        assertNull(shown.weekly)
        assertNull(shown.unavailable)
        assertEquals("usage API rate-limited", shown.note)
    }

    @Test
    fun `failures in a row wait longer each time, up to the cap`() {
        val waits = (1..8).map {
            gate.unanswered(key, "usage API rate-limited")
            var waited = Duration.ZERO
            while (gate.held(key) != null) {
                advance(Duration.ofSeconds(30))
                waited = waited.plusSeconds(30)
            }
            waited
        }

        assertEquals(UsageGate.BACKOFF, waits.first())
        assertEquals(UsageGate.BACKOFF.multipliedBy(2), waits[1])
        assertEquals(UsageGate.MAX_BACKOFF, waits.last())
    }

    @Test
    fun `Retry-After is never cut short`() {
        gate.unanswered(key, "usage API rate-limited", Duration.ofMinutes(40))

        advance(Duration.ofMinutes(39))
        assertNotNull(gate.held(key))
        advance(Duration.ofMinutes(1))
        assertNull(gate.held(key))
    }

    @Test
    fun `an answer after failures resets the backoff`() {
        repeat(4) {
            gate.unanswered(key, "usage API rate-limited")
            advance(UsageGate.MAX_BACKOFF)
        }
        gate.answered(key, reading(30.0))
        advance(UsageGate.HOLD)

        gate.unanswered(key, "usage API rate-limited")
        advance(UsageGate.BACKOFF)
        assertNull(gate.held(key), "the first failure after a good answer waits the shortest time")
    }

    /** Old numbers must not come back as "stale" for an account that has since been signed out. */
    @Test
    fun `a signed-out answer drops the old numbers`() {
        gate.answered(key, reading(12.0))
        advance(UsageGate.HOLD)
        gate.answered(key, UsageReading.unavailable("usage API refused the sign-in"))
        advance(UsageGate.HOLD)

        val shown = gate.unanswered(key, "usage API rate-limited")

        assertNull(shown.session)
    }

    /**
     * A stale reading has to stop contradicting the terminal once it is old: the quota-wall check
     * trusts only a reading taken recently, and holding one must not refresh its age.
     */
    @Test
    fun `a reading held through failures ages out of looksSpent`() {
        gate.answered(key, reading(12.0))
        advance(UsageReading.FRESH_ENOUGH.plusMinutes(1))

        val shown = gate.unanswered(key, "usage API rate-limited")

        assertNull(shown.looksSpent(now))
    }
}
