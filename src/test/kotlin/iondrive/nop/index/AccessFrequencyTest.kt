package iondrive.nop.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AccessFrequencyTest {

    @Test
    fun `record increments a fresh path to one`() {
        val f = AccessFrequency().record("a/b.kt")
        assertEquals(1, f.counts["a/b.kt"])
    }

    @Test
    fun `record accumulates on repeated access`() {
        val f = AccessFrequency().record("a/b.kt").record("a/b.kt").record("a/b.kt")
        assertEquals(3, f.counts["a/b.kt"])
    }

    @Test
    fun `record leaves the original instance untouched`() {
        val original = AccessFrequency().record("a/b.kt")
        original.record("a/b.kt")
        assertEquals(1, original.counts["a/b.kt"])
    }

    @Test
    fun `save then load round-trips counts`(@TempDir tmp: Path) {
        val target = tmp.resolve("access-counts.tsv")
        val freq = AccessFrequency().record("a/b.kt").record("a/b.kt").record("c.md")
        AccessFrequency.save(target, freq)
        val loaded = AccessFrequency.load(target)
        assertEquals(2, loaded.counts["a/b.kt"])
        assertEquals(1, loaded.counts["c.md"])
    }

    @Test
    fun `load returns empty when the file is missing`(@TempDir tmp: Path) {
        assertEquals(emptyMap<String, Int>(), AccessFrequency.load(tmp.resolve("nope.tsv")).counts)
    }

    @Test
    fun `load skips malformed lines`(@TempDir tmp: Path) {
        val target = tmp.resolve("access-counts.tsv")
        Files.writeString(target, "3\ta/b.kt\ngarbage-no-tab\nx\tc.md\n\t\n2\td.kt")
        val loaded = AccessFrequency.load(target)
        assertEquals(2, loaded.counts.size)
        assertEquals(3, loaded.counts["a/b.kt"])
        assertEquals(2, loaded.counts["d.kt"])
    }
}
