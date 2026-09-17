package iondrive.nop.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** The screenshot's canned readings: what the fixture file says is exactly what the UI is handed. */
class UsageFixtureTest {

    private val now: Instant = Instant.parse("2026-09-17T10:00:00Z")

    private fun fixture(dir: Path, text: String): Path =
        dir.resolve("usage.json").also { Files.writeString(it, text) }

    private fun account(name: String) = Account(name, Provider.Anthropic, "/nowhere/$name")

    @Test
    fun `windows resolve their resets against the moment the reading is taken`(@TempDir tmp: Path) {
        val file = fixture(
            tmp,
            """{"work": {
                "session": {"percent": 72, "resetsInMinutes": 108, "windowMinutes": 300},
                "weekly": {"percent": 41.5, "resetsInMinutes": 4380, "windowMinutes": 10080},
                "models": ["claude-opus-5", "claude-sonnet-5"]
            }}""",
        )

        val reading = UsageFixture.read(file, account("work"), now)

        assertEquals(UsageWindow(72.0, now.plus(Duration.ofMinutes(108)), Duration.ofHours(5)), reading.session)
        assertEquals(UsageWindow(41.5, now.plus(Duration.ofMinutes(4380)), Duration.ofDays(7)), reading.weekly)
        assertEquals(now, reading.asOf)
        assertEquals(listOf("claude-opus-5", "claude-sonnet-5"), UsageFixture.models(file, account("work")))
    }

    @Test
    fun `an unavailable entry reads as unavailable`(@TempDir tmp: Path) {
        val file = fixture(tmp, """{"lab": {"unavailable": "not signed in"}}""")

        assertEquals(UsageReading.unavailable("not signed in"), UsageFixture.read(file, account("lab"), now))
    }

    @Test
    fun `an account the fixture leaves out is never handed to its provider`(@TempDir tmp: Path) {
        val file = fixture(tmp, """{"work": {"session": {"percent": 10}}}""")

        val reading = UsageFixture.read(file, account("elsewhere"), now)

        assertEquals("no usage recorded yet", reading.unavailable)
        assertEquals(emptyList<String>(), UsageFixture.models(file, account("elsewhere")))
    }

    @Test
    fun `a window without a reset or span still shows its percentage`(@TempDir tmp: Path) {
        val file = fixture(tmp, """{"work": {"session": {"percent": 130}}}""")

        val reading = UsageFixture.read(file, account("work"), now)

        assertEquals(UsageWindow(100.0, null, null), reading.session)
        assertNull(reading.weekly)
    }
}
