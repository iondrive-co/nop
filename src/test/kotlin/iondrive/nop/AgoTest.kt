package iondrive.nop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgoTest {
    private val now = 1_700_000_000_000L
    private fun ago(secondsBack: Long) = Ago.of(now - secondsBack * 1000, now)

    @Test
    fun `anything under a minute is just now`() {
        assertEquals("just now", ago(0))
        assertEquals("just now", ago(59))
    }

    @Test
    fun `minutes read as minutes, and one of them is singular`() {
        assertEquals("1 minute ago", ago(60))
        assertEquals("5 minutes ago", ago(5 * 60))
    }

    @Test
    fun `an all-but-round hour rounds up rather than reading as 59 minutes`() {
        assertEquals("1 hour ago", ago(59 * 60 + 40))
    }

    @Test
    fun `hours and days`() {
        assertEquals("2 hours ago", ago(2 * 3600))
        assertEquals("1 day ago", ago(24 * 3600))
        assertEquals("3 days ago", ago(3 * 24 * 3600))
    }

    @Test
    fun `a clock that went backwards still reads as just now`() {
        assertEquals("just now", Ago.of(now + 60_000, now))
    }

    @Test
    fun `a window that was never closed reads as just now`() {
        assertEquals("just now", Ago.of(null, now))
    }
}
