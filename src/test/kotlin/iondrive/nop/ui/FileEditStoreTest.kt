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
    fun `save reports Saved and persists when disk still matches the baseline`(@TempDir tmp: Path) {
        val f = tmp.resolve("note.txt").also { it.writeText("v1\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f))

        edit.state.edit { replace(0, length, "v2\n") }
        val result = edit.save()

        assertEquals(SaveResult.Saved, result)
        assertEquals("v2\n", f.readText())
        assertFalse(edit.isModified)
    }

    @Test
    fun `save refuses to overwrite a file that diverged under us (the revert bug)`(@TempDir tmp: Path) {
        val f = tmp.resolve("deploy.sh").also { it.writeText("committed\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f)) // baseline = "committed\n"

        // The buffer drifts so it counts as "modified" (e.g. a stale diff-view writeback echo) —
        // this is exactly the state in which the old autosave would clobber the file.
        edit.state.edit { replace(0, length, "stale-buffer\n") }
        assertTrue(edit.isModified)

        // A git checkout/pull in a terminal rewrites the file to a different version.
        f.writeText("from-git\n")

        val result = edit.save()

        assertEquals(SaveResult.ExternalChange("from-git\n"), result)
        assertEquals("from-git\n", f.readText(), "the external change must survive — no silent revert")
        // save() is non-destructive to the buffer too; reconciliation (not save) adopts disk.
        assertEquals("stale-buffer\n", edit.state.text.toString())
        assertTrue(edit.isModified)
    }

    @Test
    fun `save advances the baseline without rewriting when the buffer already matches disk`(@TempDir tmp: Path) {
        val f = tmp.resolve("a.txt").also { it.writeText("v1\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f)) // baseline = "v1\n"

        // The file changes on disk and the buffer independently catches up to the same content, so
        // the buffer is "modified" relative to its (now stale) baseline yet identical to disk.
        f.writeText("v2\n")
        edit.state.edit { replace(0, length, "v2\n") }
        assertTrue(edit.isModified, "baseline is stale, so the buffer reads as modified")

        val result = edit.save()

        assertEquals(SaveResult.AlreadyOnDisk, result, "no phantom rewrite when bytes already match")
        assertEquals("v2\n", f.readText())
        assertFalse(edit.isModified, "baseline advanced to the on-disk content")
    }

    @Test
    fun `save creates a brand-new file that does not yet exist on disk`(@TempDir tmp: Path) {
        val f = tmp.resolve("new.txt").toFile() // never written
        val edit = FileEditStore().edit(Tab.FileView(f))

        edit.state.edit { replace(0, length, "hello\n") }
        val result = edit.save()

        assertEquals(SaveResult.Saved, result)
        assertEquals("hello\n", f.readText())
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

    // The autosave collectors in FileEditView / DiffView all gate on this exact condition before
    // touching disk. Modelled here so the invariant is tested without a Compose harness: nop persists
    // a buffer only when the user actually edited it through the editor.
    private fun autosaveTick(edit: FileEdit): SaveResult? =
        if (edit.hasUserEdit && edit.state.text.toString() != edit.savedText) edit.save() else null

    @Test
    fun `a buffer nop changed on its own is never autosaved (the merge-revert bug)`(@TempDir tmp: Path) {
        val f = tmp.resolve("app.kt").also { it.writeText("working\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f)) // baseline = "working\n", hasUserEdit = false

        // The user commits + merges in a terminal; the file on disk is now the merged version. Nothing
        // was typed in nop, so the buffer is stale but hasUserEdit stays false.
        f.writeText("merged\n")

        // An autosave fires (the diff view re-seeded the buffer, a poll ran, whatever). Because the
        // user never edited through the editor, it must not write — the merged file must survive.
        val result = autosaveTick(edit)

        assertNull(result, "no user edit ⇒ no write")
        assertEquals("merged\n", f.readText(), "nop must not revert a merge it didn't make")
    }

    @Test
    fun `a genuine user edit is autosaved`(@TempDir tmp: Path) {
        val f = tmp.resolve("a.txt").also { it.writeText("v1\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f))

        // The editor's InputTransformation marks the buffer on real input; mirror that here.
        edit.state.edit { replace(0, length, "v2\n") }
        edit.markUserEdit()

        val result = autosaveTick(edit)

        assertEquals(SaveResult.Saved, result)
        assertEquals("v2\n", f.readText())
        assertFalse(edit.hasUserEdit, "a successful save clears the user-edit marker")
    }

    @Test
    fun `save and adoptDiskText both clear the user-edit marker`(@TempDir tmp: Path) {
        val f = tmp.resolve("a.txt").also { it.writeText("v1\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f))

        edit.markUserEdit()
        edit.state.edit { replace(0, length, "v2\n") }
        edit.save()
        assertFalse(edit.hasUserEdit, "save clears it")

        edit.markUserEdit()
        edit.adoptDiskText("from-disk\n")
        assertFalse(edit.hasUserEdit, "adopting disk content clears it")
    }

    @Test
    fun `a pending user edit blocked by an external change stays pending`(@TempDir tmp: Path) {
        val f = tmp.resolve("a.txt").also { it.writeText("base\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f))

        edit.state.edit { replace(0, length, "my-edit\n") }
        edit.markUserEdit()
        f.writeText("from-git\n") // the file moves under us before the save lands

        val result = autosaveTick(edit)

        assertEquals(SaveResult.ExternalChange("from-git\n"), result)
        assertEquals("from-git\n", f.readText(), "external change survives")
        assertTrue(edit.hasUserEdit, "the user's edit is still pending, not silently dropped")
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
