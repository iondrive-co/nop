package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `diskTextIfDivergedAndClean surfaces external changes to a clean buffer`(@TempDir tmp: Path) {
        val f = tmp.resolve("a.txt").also { it.writeText("one\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f))
        assertFalse(edit.isModified)

        // The file changes underneath us (another editor, a branch switch, an agent).
        f.writeText("two\n")

        assertEquals("two\n", edit.diskTextIfDivergedAndClean(), "clean buffer should see the new disk text")
        // It is a pure read — the buffer itself is untouched until adoptDiskText.
        assertEquals("one\n", edit.state.text.toString())
    }

    @Test
    fun `diskTextIfDivergedAndClean leaves a modified buffer alone`(@TempDir tmp: Path) {
        val f = tmp.resolve("a.txt").also { it.writeText("one\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f))

        edit.state.edit { replace(0, length, "my edits\n") } // unsaved in-app work
        f.writeText("two\n")                                  // and the file also changed on disk

        assertNull(edit.diskTextIfDivergedAndClean(), "unsaved edits must win over the disk copy")
    }

    @Test
    fun `diskTextIfDivergedAndClean returns null when disk is unchanged`(@TempDir tmp: Path) {
        val f = tmp.resolve("a.txt").also { it.writeText("one\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f))
        assertNull(edit.diskTextIfDivergedAndClean(), "no divergence means nothing to reload")
    }

    @Test
    fun `diskTextIfDivergedAndClean returns null for a vanished file`(@TempDir tmp: Path) {
        val f = tmp.resolve("a.txt").also { it.writeText("one\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f))
        f.delete()
        assertNull(edit.diskTextIfDivergedAndClean(), "a missing file must not blank the buffer")
    }

    @Test
    fun `adoptDiskText replaces the buffer and resets the saved baseline`(@TempDir tmp: Path) {
        val f = tmp.resolve("a.txt").also { it.writeText("one\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f))

        edit.adoptDiskText("two\n")

        assertEquals("two\n", edit.state.text.toString())
        assertFalse(edit.isModified, "an adopted disk copy is in sync, not a pending edit")
    }

    @Test
    fun `reconcile flow refreshes clean buffers but preserves dirty ones`(@TempDir tmp: Path) {
        val clean = tmp.resolve("clean.txt").also { it.writeText("c1\n") }.toFile()
        val dirty = tmp.resolve("dirty.txt").also { it.writeText("d1\n") }.toFile()
        val store = FileEditStore()
        val cleanEdit = store.edit(Tab.FileView(clean))
        val dirtyEdit = store.edit(Tab.FileView(dirty))
        dirtyEdit.state.edit { replace(0, length, "d-local\n") } // unsaved in-app edit

        // Both files change on disk out from under the app.
        clean.writeText("c2\n")
        dirty.writeText("d2\n")

        // Mirror App.reconcileEdits: read divergence off each snapshot entry, then adopt the clean ones.
        for (edit in store.snapshot()) {
            edit.diskTextIfDivergedAndClean()?.let { if (!edit.isModified) edit.adoptDiskText(it) }
        }

        assertEquals("c2\n", cleanEdit.state.text.toString(), "clean buffer reloaded from disk")
        assertEquals("d-local\n", dirtyEdit.state.text.toString(), "dirty buffer kept the user's edits")
    }
}
