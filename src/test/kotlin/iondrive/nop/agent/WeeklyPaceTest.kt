package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class WeeklyPaceTest {
    private val now: Instant = Instant.parse("2026-10-06T12:00:00Z")
    private val sessionReset = now.plus(Duration.ofHours(3))
    private val weeklyReset = now.plus(Duration.ofDays(3))

    private fun reading(session: Double, weekly: Double, ratio: Double? = null) = UsageReading(
        session = UsageWindow(session, sessionReset, Duration.ofHours(5)),
        weekly = UsageWindow(weekly, weeklyReset, Duration.ofDays(7)),
        asOf = now,
        weeklyPerSession = ratio,
    )

    @Test
    fun `learns the weekly share of a session point once the session has moved`(@TempDir dir: Path) {
        val pace = WeeklyPace { dir.resolve("usage-pace") }
        assertNull(pace.annotate("a", reading(10.0, 30.0)).weeklyPerSession)
        assertNull(pace.annotate("a", reading(15.0, 30.0)).weeklyPerSession)
        assertEquals(0.1, pace.annotate("a", reading(30.0, 32.0)).weeklyPerSession!!, 1e-9)
        // Kept on disk, for the next nop.
        assertEquals(0.1, WeeklyPace { dir.resolve("usage-pace") }.annotate("a", reading(0.0, 0.0)).weeklyPerSession!!, 1e-9)
    }

    @Test
    fun `a rolled-over session window starts a new anchor`(@TempDir dir: Path) {
        val pace = WeeklyPace { dir.resolve("usage-pace") }
        pace.annotate("a", reading(80.0, 30.0))
        // Fell: a new window, so this is the anchor and not a negative rise.
        assertNull(pace.annotate("a", reading(2.0, 31.0)).weeklyPerSession)
        assertEquals(0.05, pace.annotate("a", reading(22.0, 32.0)).weeklyPerSession!!, 1e-9)
    }

    @Test
    fun `an unmoved weekly percent counts as half a point`() {
        assertEquals(0.025, WeeklyPace.ratioOf(20.0, 0.0)!!, 1e-9)
        assertNull(WeeklyPace.ratioOf(5.0, 1.0))
    }

    @Test
    fun `the weekly pace splits what the week had left evenly across this window and the rest`() {
        // Two hours into a five-hour window, three days (72h) of week left. At 0.1 weekly per
        // session point, the 20% spent this window cost 2 weekly points, so the week stood at 48%
        // when the window opened: 52 points over 74 hours, 14.8 windows, 3.51 points a window.
        val pace = reading(20.0, 50.0, ratio = 0.1).weeklyPace(now)!!
        assertEquals(52.0 / (74.0 / 5) / 0.1, pace, 1e-6)
        // Not known yet: the end of the bar.
        assertEquals(100.0, reading(20.0, 50.0).weeklyPace(now)!!, 1e-9)
    }

    @Test
    fun `a transcript estimate stands in until the account has been watched`(@TempDir dir: Path) {
        // 1M tokens for 20 session points, 4M for 8 weekly: 50k a session point, 500k a weekly one.
        assertEquals(0.1, WeeklyPace.estimateOf(1_000_000, 20.0, 4_000_000, 8.0)!!, 1e-9)
        assertNull(WeeklyPace.estimateOf(1_000_000, 2.0, 4_000_000, 8.0))
        assertNull(WeeklyPace.estimateOf(0, 20.0, 4_000_000, 8.0))

        val pace = WeeklyPace { dir.resolve("usage-pace") }
        pace.estimate("a", 0.2)
        val estimated = pace.annotate("a", reading(10.0, 30.0))
        assertEquals(0.2, estimated.weeklyPerSession!!, 1e-9)
        assertEquals(PaceSource.Transcripts, estimated.paceSource)
        val watched = pace.annotate("a", reading(30.0, 32.0))
        assertEquals(0.1, watched.weeklyPerSession!!, 1e-9)
        assertEquals(PaceSource.Observed, watched.paceSource)
        assertEquals(false, pace.wantsEstimate("a"))
    }

    @Test
    fun `spending this window does not move its own target`() {
        val before = reading(20.0, 50.0, ratio = 0.1).weeklyPace(now)!!
        val after = reading(40.0, 52.0, ratio = 0.1).weeklyPace(now)!!
        assertEquals(before, after, 1e-6)
    }
}
