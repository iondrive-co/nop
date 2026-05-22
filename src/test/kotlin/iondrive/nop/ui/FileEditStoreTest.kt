package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class FileEditStoreTest {
    @Test
    fun `edit caches state across lookups for the same tab`(@TempDir tmp: Path) {
        val f = tmp.resolve("a.txt").also { it.writeText("hello\n") }.toFile()
        val store = FileEditStore()
        val tab = Tab.FileView(f)

        val e1 = store.edit(tab)
        val e2 = store.edit(tab)
        assertSame(e1, e2, "same tab id should produce same edit state")
        assertEquals("hello\n", e1.state.text.toString())
        assertFalse(e1.isModified)
    }

    @Test
    fun `save writes buffer to disk and clears modified marker`(@TempDir tmp: Path) {
        val f = tmp.resolve("note.txt").also { it.writeText("v1\n") }.toFile()
        val store = FileEditStore()
        val edit = store.edit(Tab.FileView(f))

        edit.state.edit { replace(0, length, "v2\n") }
        assertTrue(edit.isModified, "buffer differs from savedText")

        edit.save()

        assertFalse(edit.isModified, "after save buffer matches savedText")
        assertEquals("v2\n", f.readText())
    }

    @Test
    fun `close evicts so re-opening rereads from disk`(@TempDir tmp: Path) {
        val f = tmp.resolve("x.txt").also { it.writeText("one\n") }.toFile()
        val store = FileEditStore()
        val tab = Tab.FileView(f)

        val first = store.edit(tab)
        first.state.edit { replace(0, length, "dirty\n") }
        store.close(tab.id)

        // External change while tab was closed
        f.writeText("from disk\n")

        val second = store.edit(tab)
        assertNotSame(first, second, "re-opening should produce a fresh edit state")
        assertEquals("from disk\n", second.state.text.toString())
    }
}
