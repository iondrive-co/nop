package iondrive.nop.ui

import iondrive.nop.agent.OtherLimit
import iondrive.nop.agent.UsageReading
import iondrive.nop.agent.UsageWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class LimitLinesTest {
    private val now = Instant.parse("2026-09-15T08:00:00Z")

    @Test
    fun `the account's own windows come first, named by their pool, then every other limit`() {
        val weekly = UsageWindow(30.0, now.plus(Duration.ofDays(3)), Duration.ofDays(7))
        val gemini = UsageWindow(4.0, now.plus(Duration.ofMinutes(90)))
        val geminiWeek = UsageWindow(19.0, null)
        val reading = UsageReading(
            session = null,
            weekly = weekly,
            asOf = now,
            pool = "Gemini 9 Basalt",
            others = listOf(
                OtherLimit("Gemini Models · session", gemini),
                OtherLimit("Gemini Models · weekly", geminiWeek),
            ),
        )

        assertEquals(
            listOf(
                LimitLine("Gemini 9 Basalt · weekly", weekly, "resets in 3d"),
                LimitLine("Gemini Models · session", gemini, "resets in 1h 30m"),
                LimitLine("Gemini Models · weekly", geminiWeek, ""),
            ),
            limitLines(reading, now),
        )
    }

    @Test
    fun `a reading without windows is one line saying why`() {
        assertEquals(listOf(LimitLine("not signed in", null, "")), limitLines(UsageReading.unavailable("not signed in"), now))
        assertEquals(listOf(LimitLine("Not read yet", null, "")), limitLines(null, now))
    }
}
