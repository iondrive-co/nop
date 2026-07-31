package iondrive.nop.ui

import androidx.compose.ui.text.TextRange
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
    fun `editorsFor is empty when no tab is open on the file, so revert skips the heap read`(@TempDir tmp: Path) {
        val big = tmp.resolve("big.bin").also { it.writeText("x") }.toFile()
        val store = FileEditStore()
        // Nothing open at all: reverting big.bin must not read it back to refresh a nonexistent buffer.
        assertTrue(store.editorsFor(big).isEmpty())
        // An unrelated open tab must not make big.bin look open either.
        store.edit(Tab.FileView(tmp.resolve("other.txt").also { it.writeText("y") }.toFile()))
        assertTrue(store.editorsFor(big).isEmpty(), "an unrelated open tab is not an editor for big.bin")
    }

    @Test
    fun `editorsFor returns the open buffer backing a file`(@TempDir tmp: Path) {
        val f = tmp.resolve("a.txt").also { it.writeText("hi\n") }.toFile()
        val store = FileEditStore()
        val edit = store.edit(Tab.FileView(f))
        assertEquals(listOf(edit), store.editorsFor(f))
    }

    @Test
    fun `each file gets its own viewport and find state`(@TempDir tmp: Path) {
        // Only the selected tab's editor is composed, so this state can't live in a remember inside
        // it: the previous file's scroll offset and query used to be restored into the next tab.
        val store = FileEditStore()
        val a = store.edit(Tab.FileView(tmp.resolve("a.txt").also { it.writeText("a\n") }.toFile()))
        val b = store.edit(Tab.FileView(tmp.resolve("b.txt").also { it.writeText("b\n") }.toFile()))

        a.findQuery.edit { replace(0, length, "needle") }
        a.replaceWith.edit { replace(0, length, "thread") }

        assertEquals("", b.findQuery.text.toString(), "a sibling tab must not inherit the query")
        assertEquals("", b.replaceWith.text.toString(), "nor the replacement")
        assertNotSame(a.scroll, b.scroll, "nor the scroll position")
    }

    @Test
    fun `a file keeps its viewport and find state across a tab switch`(@TempDir tmp: Path) {
        // The store is what survives a switch away and back — edit() returns the same FileEdit, so
        // reopening the tab lands where the user left it with the query they last used.
        val store = FileEditStore()
        val tab = Tab.FileView(tmp.resolve("a.txt").also { it.writeText("a\n") }.toFile())
        store.edit(tab).findQuery.edit { replace(0, length, "needle") }

        assertEquals("needle", store.edit(tab).findQuery.text.toString())
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
    fun `diskTextIfDivergedAndClean leaves a genuinely user-edited buffer alone`(@TempDir tmp: Path) {
        val f = tmp.resolve("a.txt").also { it.writeText("one\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f))

        edit.state.edit { replace(0, length, "my edits\n") } // unsaved in-app work…
        edit.markUserEdit()                                   // …that the user actually typed
        f.writeText("two\n")                                  // and the file also changed on disk

        assertNull(edit.diskTextIfDivergedAndClean(), "unsaved user edits must win over the disk copy")
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
    fun `adoptDiskText keeps the caret instead of dropping it at the end of the document`(@TempDir tmp: Path) {
        val f = tmp.resolve("a.txt").also { it.writeText("alpha\nbeta\ngamma\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f))
        edit.state.edit { selection = TextRange(3) } // user is typing inside "alpha"

        // An agent / checkout appends to the file and reconcile adopts it.
        edit.adoptDiskText("alpha\nbeta\ngamma\ndelta\nepsilon\n")

        assertEquals(
            3,
            edit.state.selection.start,
            "adopting an external change must not teleport the caret to the bottom of the file",
        )
    }

    @Test
    fun `adoptDiskText clamps a caret that the shorter disk copy no longer has room for`(@TempDir tmp: Path) {
        val f = tmp.resolve("a.txt").also { it.writeText("a long line of text\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f))
        edit.state.edit { selection = TextRange(15) }

        edit.adoptDiskText("short\n")

        assertEquals(6, edit.state.selection.start, "caret clamps into the new text rather than throwing")
    }

    /**
     * The autosave race, replayed deterministically. save() snapshots the buffer, then spends a whole
     * file read plus a write off the UI thread before doing its bookkeeping; a keystroke landing in
     * that window is user work the write didn't include. [FileEdit.markSaved] is that bookkeeping, so
     * driving it with a buffer already ahead of the written text *is* the race.
     */
    private fun typeDuringSave(edit: FileEdit, written: String, typedMeanwhile: String) {
        edit.state.edit { replace(0, length, written) }
        edit.markUserEdit() // the editor's InputTransformation, on the keystrokes save() will capture
        // save() snapshots `written` here, then goes to disk…
        edit.state.edit { replace(0, length, typedMeanwhile) }
        edit.markUserEdit() // …and the user keeps typing while it's away
        edit.file.writeText(written) // …the write lands, carrying only the snapshot
        edit.markSaved(written)
    }

    @Test
    fun `keystrokes typed during a save stay pending instead of being reverted from disk`(@TempDir tmp: Path) {
        val f = tmp.resolve("a.txt").also { it.writeText("v1\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f))

        typeDuringSave(edit, written = "typed\n", typedMeanwhile = "typed more\n")

        assertTrue(edit.hasUserEdit, "keystrokes the save didn't capture are still pending user work")
        assertNull(
            edit.diskTextIfDivergedAndClean(),
            "so reconcile must not mistake them for drift and revert the buffer from disk",
        )
    }

    @Test
    fun `the next autosave picks up the keystrokes the previous one raced past`(@TempDir tmp: Path) {
        val f = tmp.resolve("a.txt").also { it.writeText("v1\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f))

        typeDuringSave(edit, written = "typed\n", typedMeanwhile = "typed more\n")

        assertEquals(SaveResult.Saved, autosaveTick(edit), "still dirty, so the timer saves again")
        assertEquals("typed more\n", f.readText(), "and the raced keystrokes reach disk")
        assertFalse(edit.hasUserEdit)
    }

    @Test
    fun `a save that captured everything still clears the marker`(@TempDir tmp: Path) {
        // The common case must not regress into permanently-dirty buffers: when nothing was typed
        // during the write, the marker clears and reconcile is free to reload the file later.
        val f = tmp.resolve("a.txt").also { it.writeText("v1\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f))

        edit.state.edit { replace(0, length, "v2\n") }
        edit.markUserEdit()

        assertEquals(SaveResult.Saved, edit.save())
        assertFalse(edit.hasUserEdit, "nothing raced the write, so the buffer is genuinely clean")
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
    fun `a buffer that drifted without a user edit is reloaded from disk (the stale-diff bug)`(@TempDir tmp: Path) {
        // Reproduces the reported bug: a working-tree diff shows a stale, wrong change (a "no-op in a
        // comment") instead of the real edit that's on disk. Root cause below.
        val f = tmp.resolve("vars").also { it.writeText("a\nb\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f)) // baseline = "a\nb\n", hasUserEdit = false

        // The diff view's per-line cells re-seed the shared buffer programmatically (a "stale
        // writeback echo" — see the revert-bug test above). That drifts state.text off the baseline
        // WITHOUT any genuine user edit, so isModified is true but hasUserEdit is false.
        edit.state.edit { replace(0, length, "a\nX\n") }
        assertTrue(edit.isModified, "programmatic drift reads as modified")
        assertFalse(edit.hasUserEdit, "but the user never typed anything")

        // Meanwhile the real edit lands on disk (an external editor / agent adds lines).
        f.writeText("a\nb\nc\nd\n")

        // There is no user work to protect, so reconcile must surface the disk copy: the drift is
        // discarded and the diff will reflect the real on-disk change. Gating this on !isModified
        // (instead of !hasUserEdit) strands the buffer forever and is exactly the bug.
        assertEquals(
            "a\nb\nc\nd\n",
            edit.diskTextIfDivergedAndClean(),
            "a drifted-but-not-user-edited buffer must reload from disk, not show stale content",
        )
    }

    @Test
    fun `pure drift with no external change still heals back to disk`(@TempDir tmp: Path) {
        // Even with no external write, a buffer that drifted without a user edit must be brought back
        // in line with disk — otherwise the diff keeps showing the phantom drift (the "no-op" change).
        val f = tmp.resolve("vars").also { it.writeText("a\nb\n") }.toFile()
        val edit = FileEditStore().edit(Tab.FileView(f))

        edit.state.edit { replace(0, length, "a\nX\n") } // drift, no markUserEdit
        assertEquals("a\nb\n", edit.diskTextIfDivergedAndClean(), "heal the phantom drift from disk")
    }

    @Test
    fun `reconcile flow refreshes clean buffers but preserves dirty ones`(@TempDir tmp: Path) {
        val clean = tmp.resolve("clean.txt").also { it.writeText("c1\n") }.toFile()
        val dirty = tmp.resolve("dirty.txt").also { it.writeText("d1\n") }.toFile()
        val store = FileEditStore()
        val cleanEdit = store.edit(Tab.FileView(clean))
        val dirtyEdit = store.edit(Tab.FileView(dirty))
        dirtyEdit.state.edit { replace(0, length, "d-local\n") } // unsaved in-app edit…
        dirtyEdit.markUserEdit()                                 // …the user actually made

        // Both files change on disk out from under the app.
        clean.writeText("c2\n")
        dirty.writeText("d2\n")

        // Mirror App.reconcileEdits: read divergence off each snapshot entry, then adopt everything the
        // user hasn't got pending edits in.
        for (edit in store.snapshot()) {
            edit.diskTextIfDivergedAndClean()?.let { if (!edit.hasUserEdit) edit.adoptDiskText(it) }
        }

        assertEquals("c2\n", cleanEdit.state.text.toString(), "clean buffer reloaded from disk")
        assertEquals("d-local\n", dirtyEdit.state.text.toString(), "dirty buffer kept the user's edits")
    }
}
