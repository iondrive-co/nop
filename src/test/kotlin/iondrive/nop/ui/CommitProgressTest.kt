package iondrive.nop.ui

import iondrive.nop.git.CommitProgress
import iondrive.nop.git.CommitProgress.Phase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommitProgressTest {
    private val start = 1_000_000L

    private fun writing(
        bytesDone: Long,
        bytesTotal: Long,
        filesDone: Int = 0,
        filesTotal: Int = 0,
    ) = CommitProgress(Phase.WRITING, bytesDone, bytesTotal, filesDone, filesTotal, start)

    @Test
    fun `label shows the percentage and an ETA extrapolated from the rate so far`() {
        // A quarter of the bytes in one minute means three minutes to go.
        val label = commitProgressLabel(writing(bytesDone = 250, bytesTotal = 1000), start + 60_000)
        assertEquals("25% · 3m left", label)
    }

    @Test
    fun `ETA under a minute is quoted in seconds`() {
        // Half done in ten seconds: ten to go.
        assertEquals("50% · 10s left", commitProgressLabel(writing(500, 1000), start + 10_000))
    }

    @Test
    fun `long ETAs fall back to hours and minutes`() {
        // A tenth done in eight minutes — the shape of the 1.9 GB commits this exists for.
        assertEquals("10% · 1h 12m left", commitProgressLabel(writing(100, 1000), start + 8 * 60_000))
    }

    @Test
    fun `percentage is floored so it never claims work that is not done`() {
        assertTrue(commitProgressLabel(writing(999, 1000), start + 10_000).startsWith("99%"))
        assertTrue(commitProgressLabel(writing(1, 1000), start + 10_000).startsWith("0%"))
    }

    @Test
    fun `a met total moves on to the phase label rather than reading 100 percent`() {
        // A still-greyed button reading 100% looks stuck; once the bytes are all in, what is left
        // is the index and the commit object, so the button says so.
        assertEquals("Writing… 10s", commitProgressLabel(writing(10000, 10000), start + 10_000))
    }

    @Test
    fun `no ETA until enough time has passed for the rate to mean anything`() {
        // The first second is fixed costs (the plan walk, the index lock); extrapolating from it
        // would quote a wildly wrong figure at exactly the moment the user first looks.
        assertEquals("50%", commitProgressLabel(writing(500, 1000), start + 500))
    }

    @Test
    fun `unmeasurable work falls back to the phase and elapsed time`() {
        // JGit's AddCommand reports nothing as it walks, so bytesTotal stays 0 on that path.
        val staging = CommitProgress(Phase.STAGING, filesTotal = 12, startedAtMillis = start)
        assertEquals("Staging… 1m", commitProgressLabel(staging, start + 80_000))
        assertEquals("Staging… 12s", commitProgressLabel(staging, start + 12_000))
        // Under a second there is no number worth showing.
        assertEquals("Staging…", commitProgressLabel(staging, start + 300))
    }

    @Test
    fun `a measurable total with nothing done yet still reads as elapsed time`() {
        // Between the plan and the first blob landing there is a total but no rate.
        assertEquals("Writing… 3s", commitProgressLabel(writing(0, 1000), start + 3_000))
    }

    @Test
    fun `each phase names itself`() {
        for (phase in Phase.entries) {
            val label = commitProgressLabel(CommitProgress(phase, startedAtMillis = start), start + 5_000)
            assertEquals("${phase.label}… 5s", label, "phase $phase should name itself in the button")
        }
    }

    @Test
    fun `a null snapshot reads as the plain in-flight label`() {
        assertEquals("Committing…", commitProgressLabel(null, start))
    }

    @Test
    fun `fraction is null without a total and clamped with one`() {
        assertNull(CommitProgress(Phase.STAGING, bytesDone = 10).fraction)
        assertEquals(1f, writing(2000, 1000).fraction)
        assertEquals(0.5f, writing(500, 1000).fraction)
    }

    @Test
    fun `detail spells out the counts the bar has no room for`() {
        val progress = writing(bytesDone = 512L * 1024 * 1024, bytesTotal = 2L * 1024 * 1024 * 1024, filesDone = 120, filesTotal = 400)
        assertEquals(
            """
            Writing 120 of 400 files into the object store
            512 MB of 2.0 GB
            1m 00s elapsed · about 3m 00s left
            """.trimIndent(),
            commitProgressDetail(progress, start + 60_000),
        )
    }

    @Test
    fun `detail leaves out sizes and ETA when there are none`() {
        val staging = CommitProgress(Phase.STAGING, filesTotal = 1, startedAtMillis = start)
        assertEquals("Staging 1 file\n4s elapsed", commitProgressDetail(staging, start + 4_000))
    }

    @Test
    fun `byte sizes read the way a file manager writes them`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("999 B", formatBytes(999))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.9 GB", formatBytes(2_040_109_466))
        // Past three digits the decimal is noise, so it goes.
        assertEquals("512 MB", formatBytes(512L * 1024 * 1024))
        assertEquals("1.0 TB", formatBytes(1024L * 1024 * 1024 * 1024))
    }
}
