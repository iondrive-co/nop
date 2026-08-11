package iondrive.nop.history

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class LocalHistoryTest {
    private fun historyIn(tmp: Path) = LocalHistory(tmp.resolve("localhistory"))

    @Test
    fun `records snapshots newest first and reads them back`(@TempDir tmp: Path) {
        val file = tmp.resolve("a.kt").also { it.writeText("v1") }.toFile()
        val history = historyIn(tmp)

        history.record(file, "v1", at = 1_000)
        history.record(file, "v2", at = 2_000)
        history.record(file, "v3", at = 3_000)

        val revisions = history.revisions(file)
        assertEquals(listOf(3_000L, 2_000L, 1_000L), revisions.map { it.timestampMillis })
        assertEquals(listOf("v3", "v2", "v1"), revisions.map { history.read(it) })
    }

    @Test
    fun `recording the same text again is a no-op`(@TempDir tmp: Path) {
        // The save path offers the pre-save baseline on every write, so this is the common case:
        // only the first offer after the content changes may store anything.
        val file = tmp.resolve("a.kt").toFile()
        val history = historyIn(tmp)

        assertNotNull(history.record(file, "same", at = 1_000))
        assertNull(history.record(file, "same", at = 2_000))
        assertEquals(1, history.revisions(file).size)

        // …but the same text returning after an edit is a genuine revision of its own.
        history.record(file, "other", at = 3_000)
        assertNotNull(history.record(file, "same", at = 4_000))
        assertEquals(3, history.revisions(file).size)
    }

    @Test
    fun `two saves in the same millisecond are both kept`(@TempDir tmp: Path) {
        val file = tmp.resolve("a.kt").toFile()
        val history = historyIn(tmp)

        val first = history.record(file, "v1", at = 1_000)
        val second = history.record(file, "v2", at = 1_000)

        assertEquals(1_000L, first?.timestampMillis)
        assertEquals(1_001L, second?.timestampMillis, "the collision is nudged forward, not overwritten")
        assertEquals(listOf("v2", "v1"), history.revisions(file).map { history.read(it) })
    }

    @Test
    fun `files with the same name in different directories keep separate histories`(@TempDir tmp: Path) {
        val a = tmp.resolve("one").toFile().apply { mkdirs() }.resolve("Main.kt")
        val b = tmp.resolve("two").toFile().apply { mkdirs() }.resolve("Main.kt")
        val history = historyIn(tmp)

        history.record(a, "from a", at = 1_000)
        history.record(b, "from b", at = 1_000)

        assertEquals(listOf("from a"), history.revisions(a).map { history.read(it) })
        assertEquals(listOf("from b"), history.revisions(b).map { history.read(it) })
    }

    @Test
    fun `revisionAt re-finds a revision from its timestamp`(@TempDir tmp: Path) {
        // This is how a restored tab gets back to the revision it was showing before the restart.
        val file = tmp.resolve("a.kt").toFile()
        val history = historyIn(tmp)
        history.record(file, "v1", at = 1_000)

        assertEquals("v1", history.revisionAt(file, 1_000)?.let { history.read(it) })
        assertNull(history.revisionAt(file, 999))
    }

    @Test
    fun `prunes past the per-file cap`(@TempDir tmp: Path) {
        val file = tmp.resolve("a.kt").toFile()
        val history = historyIn(tmp)
        val total = LocalHistory.MAX_REVISIONS_PER_FILE + 10
        for (i in 1..total) history.record(file, "v$i", at = i.toLong())

        val revisions = history.revisions(file)
        assertEquals(LocalHistory.MAX_REVISIONS_PER_FILE, revisions.size)
        assertEquals("v$total", history.read(revisions.first()), "the newest survives")
    }

    @Test
    fun `prunes revisions past the age window but never the newest`(@TempDir tmp: Path) {
        val file = tmp.resolve("a.kt").toFile()
        val history = historyIn(tmp)
        val old = 1_000L
        history.record(file, "ancient", at = old)
        val now = old + LocalHistory.MAX_AGE_MILLIS + 1

        history.record(file, "fresh", at = now)
        assertEquals(listOf("fresh"), history.revisions(file).map { history.read(it) })

        // Left alone, the survivor ages out too — but pruning it would leave the file looking as if
        // it had never been recorded, so the newest is exempt.
        history.record(file, "fresher", at = now + LocalHistory.MAX_AGE_MILLIS + 1)
        assertEquals(listOf("fresher"), history.revisions(file).map { history.read(it) })
    }

    @Test
    fun `skips files too large to keep copies of`(@TempDir tmp: Path) {
        val file = tmp.resolve("bundle.js").toFile()
        val history = historyIn(tmp)

        assertNull(history.record(file, "x".repeat(LocalHistory.MAX_SNAPSHOT_CHARS + 1), at = 1_000))
        assertTrue(history.revisions(file).isEmpty())
    }

    @Test
    fun `a file with no history reads as empty rather than failing`(@TempDir tmp: Path) {
        assertTrue(historyIn(tmp).revisions(tmp.resolve("never-touched.kt").toFile()).isEmpty())
    }
}
