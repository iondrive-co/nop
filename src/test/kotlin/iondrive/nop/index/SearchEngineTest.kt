package iondrive.nop.index

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SearchEngineTest {

    private fun search(tmp: Path, query: String): List<SearchHit> = runBlocking {
        val idx = FileIndex.build(tmp)
        SearchEngine.search(tmp, idx.files, query)
    }

    @Test
    fun `empty query returns no hits`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("a.txt"), "hello\n")
        assertEquals(emptyList<SearchHit>(), search(tmp, ""))
    }

    @Test
    fun `literal match is case insensitive`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("a.txt"), "Hello World\n")
        val hits = search(tmp, "hello")
        assertEquals(1, hits.size)
        val h = hits.single()
        assertEquals("a.txt", h.path)
        assertEquals(1, h.line)
        assertEquals(0, h.matchStart)
        assertEquals(5, h.matchEnd)
        assertEquals("Hello World", h.lineText)
    }

    @Test
    fun `multiple hits per file are reported with correct offsets`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("a.txt"), "foo bar foo\nfoo\n")
        val hits = search(tmp, "foo")
        assertEquals(3, hits.size)
        assertEquals(1, hits[0].line)
        assertEquals(0, hits[0].matchStart)
        assertEquals(1, hits[1].line)
        assertEquals(8, hits[1].matchStart)
        assertEquals(2, hits[2].line)
        assertEquals(0, hits[2].matchStart)
    }

    @Test
    fun `no matches yields empty list`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("a.txt"), "alpha\nbeta\n")
        assertEquals(emptyList<SearchHit>(), search(tmp, "gamma"))
    }

    @Test
    fun `respects ignored dirs via FileIndex`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("node_modules"))
        Files.writeString(tmp.resolve("node_modules/index.js"), "needle\n")
        Files.writeString(tmp.resolve("kept.txt"), "needle\n")
        val hits = search(tmp, "needle")
        assertEquals(1, hits.size)
        assertEquals("kept.txt", hits.single().path)
    }

    @Test
    fun `skips files larger than the cutoff`(@TempDir tmp: Path) {
        // Pad past the 2 MiB cutoff with content that contains the needle.
        val big = tmp.resolve("big.txt")
        val sb = StringBuilder()
        repeat(120_000) { sb.append("needle padding line\n") }
        Files.writeString(big, sb.toString())
        assertTrue(Files.size(big) > 2L * 1024 * 1024, "test setup should exceed cutoff")

        Files.writeString(tmp.resolve("small.txt"), "needle\n")
        val hits = search(tmp, "needle")
        assertEquals(1, hits.size)
        assertEquals("small.txt", hits.single().path)
    }

    @Test
    fun `skips files with a known-binary extension`(@TempDir tmp: Path) {
        // A "PNG" whose bytes happen to contain the needle as text — must not be scanned.
        Files.writeString(tmp.resolve("logo.png"), "needle\n")
        Files.writeString(tmp.resolve("kept.txt"), "needle\n")
        val hits = search(tmp, "needle")
        assertEquals(1, hits.size)
        assertEquals("kept.txt", hits.single().path)
    }

    @Test
    fun `skips files whose content looks binary`(@TempDir tmp: Path) {
        // No telltale extension, but a NUL byte near the start marks it binary.
        Files.write(tmp.resolve("blob.xyz"), byteArrayOf(0) + "needle here".toByteArray())
        Files.writeString(tmp.resolve("kept.txt"), "needle\n")
        val hits = search(tmp, "needle")
        assertEquals(1, hits.size)
        assertEquals("kept.txt", hits.single().path)
    }

    @Test
    fun `results are ordered by path then line then column`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("b.txt"), "needle\n")
        Files.writeString(tmp.resolve("a.txt"), "x needle\nneedle\n")
        val hits = search(tmp, "needle")
        assertEquals(3, hits.size)
        assertEquals(listOf("a.txt", "a.txt", "b.txt"), hits.map { it.path })
        assertEquals(listOf(1, 2, 1), hits.map { it.line })
        assertEquals(listOf(2, 0, 0), hits.map { it.matchStart })
    }

    @Test
    fun `handles CRLF line endings without including the carriage return`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("a.txt"), "foo\r\nbar\r\n")
        val hits = search(tmp, "foo")
        assertEquals(1, hits.size)
        val h = hits.single()
        assertEquals("foo", h.lineText)
        assertEquals(1, h.line)
    }

    @Test
    fun `reports the second hit on the next line`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("a.txt"), "line one\nhit here\nline three\n")
        val hits = search(tmp, "hit")
        assertEquals(1, hits.size)
        val h = hits.single()
        assertEquals(2, h.line)
        assertEquals(0, h.matchStart)
        assertEquals(3, h.matchEnd)
    }
}
