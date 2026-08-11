package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalHistoryFormatTest {
    private val now = 1_700_000_000_000L

    private fun ageAfter(millis: Long) = LocalHistoryFormat.age(now - millis, now)

    @Test
    fun `ages read the way someone hunting for a lost edit thinks about them`() {
        assertEquals("just now", ageAfter(0))
        assertEquals("just now", ageAfter(44_000))
        assertEquals("a minute ago", ageAfter(60_000))
        assertEquals("20 minutes ago", ageAfter(20 * 60_000L))
        assertEquals("an hour ago", ageAfter(70 * 60_000L))
        assertEquals("5 hours ago", ageAfter(5 * 60 * 60_000L))
        assertEquals("yesterday", ageAfter(30 * 60 * 60_000L))
        assertEquals("3 days ago", ageAfter(3 * 24 * 60 * 60_000L))
    }

    @Test
    fun `a timestamp from the future reads as just now rather than a negative age`() {
        // A clock step (NTP, DST) can leave a revision stamped ahead of now; "-1 days ago" would be
        // a worse answer than the one a fresh revision gets.
        assertEquals("just now", LocalHistoryFormat.age(now + 60_000, now))
    }

    @Test
    fun `sizes are rounded to the unit that fits`() {
        assertEquals("512 B", LocalHistoryFormat.size(512))
        assertEquals("2 kB", LocalHistoryFormat.size(2048))
        assertEquals("1.5 MB", LocalHistoryFormat.size((1.5 * 1024 * 1024).toLong()))
    }
}
