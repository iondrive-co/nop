package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class UsageBreakdownTest {

    @Test
    fun testFormatModelName() {
        assertEquals("GPT-6 Astra", UsageBreakdown.formatModelName("gpt-6-astra"))
        assertEquals("GPT-6.1 Sol", UsageBreakdown.formatModelName("gpt-6.1-sol"))
        assertEquals("Claude Opus 5.5", UsageBreakdown.formatModelName("claude-opus-5-5"))
        assertEquals("Gemini 3.8 Flash", UsageBreakdown.formatModelName("gemini-3.8-flash"))
        assertEquals("custom-model", UsageBreakdown.formatModelName("custom-model"))
    }

    @Test
    fun testFormatTokens() {
        assertEquals("500", UsageBreakdown.formatTokens(500L))
        assertEquals("1.5K", UsageBreakdown.formatTokens(1500L))
        assertEquals("2.4M", UsageBreakdown.formatTokens(2_400_000L))
        assertEquals("3.1B", UsageBreakdown.formatTokens(3_100_000_000L))
    }

    @Test
    fun testWindowStartCalculation() {
        val now = Instant.parse("2026-10-05T00:00:00Z")
        val account = Account(
            name = "claude-work",
            provider = Provider.Anthropic,
            home = "/tmp/fake",
        )

        // Weekly window resetting on Friday morning (Oct 9, 2026) -> started Oct 2, 2026
        val weeklyResets = Instant.parse("2026-10-09T00:00:00Z")
        val reading = UsageReading(
            session = null,
            weekly = UsageWindow(percent = 45.0, resetsAt = weeklyResets, length = Duration.ofDays(7)),
            asOf = now,
        )

        val start = UsageBreakdown.windowStartFor(account, reading, UsageScope.Weekly7d, now)
        assertEquals(Instant.parse("2026-10-02T00:00:00Z"), start)

        // If reset has already passed (e.g. rolled over 1 hour ago)
        val passedResets = Instant.parse("2026-10-04T23:00:00Z")
        val rolledReading = UsageReading(
            session = null,
            weekly = UsageWindow(percent = 0.0, resetsAt = passedResets, length = Duration.ofDays(7)),
            asOf = now,
        )
        val rolledStart = UsageBreakdown.windowStartFor(account, rolledReading, UsageScope.Weekly7d, now)
        assertEquals(passedResets, rolledStart)

        // Without reset info -> defaults to now - duration
        val emptyReading = UsageReading(session = null, weekly = null, asOf = now)
        val fallbackStart = UsageBreakdown.windowStartFor(account, emptyReading, UsageScope.Weekly7d, now)
        assertEquals(now.minus(Duration.ofDays(7)), fallbackStart)
    }

    @Test
    fun testComputeEmptyAccount(@TempDir tempDir: Path) {
        val account = Account(
            name = "test-acct",
            provider = Provider.Anthropic,
            home = tempDir.toString(),
        )
        val result = UsageBreakdown.compute(
            accounts = listOf(account),
            readings = emptyMap(),
            scope = UsageScope.Session5h,
        )

        assertTrue(result.containsKey("test-acct"))
        val breakdown = result["test-acct"]!!
        assertEquals("test-acct", breakdown.account.name)
        assertEquals(0L, breakdown.totalTokens)
        assertEquals(0, breakdown.totalSteps)
        assertTrue(breakdown.currentPeriod.models.isEmpty())
        assertTrue(breakdown.currentPeriod.sessions.isEmpty())
        assertTrue(breakdown.historicalPeriod.models.isEmpty())
        assertTrue(breakdown.historicalPeriod.sessions.isEmpty())
    }
}
