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
        val reading = UsageReading(
            session = null,
            weekly = UsageWindow(30.0, now.plus(Duration.ofDays(3)), Duration.ofDays(7)),
            asOf = now,
            pool = "Gemini 9 Basalt",
            others = listOf(
                OtherLimit("Gemini Models · session", UsageWindow(4.0, now.plus(Duration.ofMinutes(90)))),
                OtherLimit("Gemini Models · weekly", UsageWindow(19.0, null)),
            ),
        )

        assertEquals(
            listOf(
                LimitLine("Gemini 9 Basalt · weekly", 30.0, "resets in 3d"),
                LimitLine("Gemini Models · session", 4.0, "resets in 1h 30m"),
                LimitLine("Gemini Models · weekly", 19.0, ""),
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
