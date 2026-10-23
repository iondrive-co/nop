package iondrive.nop.recovery

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class QuarantineTest {
    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun isolate() {
        Quarantine.fileOverride = dir.resolve("quarantine")
        Quarantine.clearForTest()
    }

    @AfterEach
    fun restore() {
        Quarantine.clearForTest()
        Quarantine.fileOverride = null
    }

    @Test
    fun `a held-back tab is still held back by the next launch`() {
        Quarantine.add(listOf("diff:/repo/docs/IDEAS.md"), "IllegalArgumentException: Can't represent a width of 546566", at = 42)
        Quarantine.clearForTest()
        Quarantine.load()
        val entry = checkNotNull(Quarantine.entry("diff:/repo/docs/IDEAS.md")) { "not held back" }
        assertEquals(42, entry.at)
        assertEquals("IllegalArgumentException: Can't represent a width of 546566", entry.reason)
    }

    @Test
    fun `trying again lets the tab be drawn, and that is remembered too`() {
        Quarantine.add(listOf("file:/a", "file:/b"), "boom")
        Quarantine.release("file:/a")
        Quarantine.clearForTest()
        Quarantine.load()
        assertNull(Quarantine.entry("file:/a"))
        assertNotNull(Quarantine.entry("file:/b"))
    }

    @Test
    fun `the first reason a tab was held back for is the one kept`() {
        Quarantine.add(listOf("file:/a"), "first", at = 1)
        Quarantine.add(listOf("file:/a"), "second", at = 2)
        assertEquals("first", Quarantine.entry("file:/a")!!.reason)
    }

    @Test
    fun `keys and reasons with tabs and newlines survive the file`() {
        val entries = listOf(Quarantine.Entry("file:/odd\tname\nhere", 7, "a\\b"))
        assertEquals(entries, Quarantine.decode(Quarantine.encode(entries)))
    }

    @Test
    fun `a multi-line reason is kept as one line`() {
        Quarantine.add(listOf("file:/a"), "line one\n\tat somewhere\nline three")
        assertEquals("line one at somewhere line three", Quarantine.entry("file:/a")!!.reason)
    }

    @Test
    fun `a damaged file is read as nothing held back, not as a reason not to start`() {
        Files.writeString(dir.resolve("quarantine"), "garbage\nmore\tgarbage\n\u0000")
        Quarantine.load()
        assertNull(Quarantine.entry("garbage"))
    }
}
