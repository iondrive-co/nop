package iondrive.nop.ui

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The clipboard prefers the system clipboard and falls back to its in-memory copy, so on a
 * headless/CI run these exercise the fallback path and on a desktop run they exercise the
 * round-trip through AWT. Either way copy-then-read must return what was copied.
 */
class FileClipboardTest {
    @BeforeEach @AfterEach fun reset() = FileClipboard.clear()

    @Test fun `copy then read returns the copied files`(@TempDir tmp: Path) {
        val a = tmp.resolve("a.txt").createFile().toFile()
        val b = tmp.resolve("b.txt").createFile().toFile()

        FileClipboard.copy(listOf(a, b))

        assertEquals(listOf(a.absoluteFile, b.absoluteFile), FileClipboard.files())
        assertTrue(FileClipboard.hasFiles())
    }

    @Test fun `an empty copy leaves the previous contents in place`(@TempDir tmp: Path) {
        val a = tmp.resolve("a.txt").createFile().toFile()
        FileClipboard.copy(listOf(a))

        FileClipboard.copy(emptyList())

        assertEquals(listOf(a.absoluteFile), FileClipboard.files())
    }

    @Test fun `nothing copied means nothing to paste`() {
        // Only meaningful when the system clipboard isn't holding a file list from elsewhere.
        if (FileClipboard.files().isEmpty()) assertFalse(FileClipboard.hasFiles())
    }
}
