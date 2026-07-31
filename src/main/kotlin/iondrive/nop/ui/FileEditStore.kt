package iondrive.nop.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import java.io.File

/** Outcome of a [FileEdit.save]. */
sealed interface SaveResult {
    /** The buffer was written to disk and the baseline advanced. */
    data object Saved : SaveResult

    /** The buffer already matched what was on disk; nothing was written, baseline advanced. */
    data object AlreadyOnDisk : SaveResult

    /**
     * The file on disk diverged from our last-synced baseline since we loaded it — a git
     * checkout/pull, another editor, or an agent wrote it — so the buffer was NOT written, to
     * avoid silently reverting that external change. [diskText] is the current on-disk content
     * (null if the file is now unreadable) so the caller can reconcile.
     */
    data class ExternalChange(val diskText: String?) : SaveResult
}

/** Per-file editor state cached across tab switches. */
class FileEdit(initialText: String, val file: File) {
    val state: TextFieldState = TextFieldState(initialText)

    /**
     * Where this file is scrolled to, and what its find bar was searching for and replacing with.
     *
     * They live here, beside the buffer, because only one FileEditView is composed at a time — the
     * selected tab's — so anything remembered inside it either dies on a tab switch or, if hoisted
     * into Compose's saveable registry, is restored into whichever *other* file lands in that slot
     * next. Both were the latter: switching tabs dropped you at the previous file's scroll offset
     * with its query in your find bar. Keyed to the file and dropped when its tab closes.
     */
    val scroll: ScrollState = ScrollState(0)
    val findQuery: TextFieldState = TextFieldState()
    val replaceWith: TextFieldState = TextFieldState()
    var savedText: String by mutableStateOf(initialText)
        private set

    /**
     * True once the user changed the buffer *through the editor* (typed, pasted, resolved a conflict,
     * reverted a hunk) since it was last in sync with disk. The autosave consults this so that buffer
     * mutations nop makes on its own — adopting an externally-changed file, re-seeding the diff view's
     * per-line cells after a re-diff — can never trigger a disk write. nop only ever writes back what
     * the user actually edited; this is what stops it reverting a file after a checkout/pull/merge it
     * didn't make. Cleared whenever the buffer is brought back in sync with disk (save / adopt).
     */
    var hasUserEdit: Boolean by mutableStateOf(false)
        private set

    /** Record that the user changed the buffer via the editor. Call on the UI thread. */
    fun markUserEdit() {
        hasUserEdit = true
    }

    val isModified: Boolean
        get() = state.text.toString() != savedText

    /**
     * Persist the buffer to disk as a compare-and-swap against [savedText]. The autosave timer
     * fires this without the user asking, so it must never clobber a file that changed underneath
     * us: we only overwrite when the bytes on disk are still the version we last loaded or saved.
     *  - Buffer already equals disk → advance the baseline, write nothing (kills the phantom
     *    stat-only rewrite that just bumps mtime and confuses `git status`).
     *  - Disk still matches our baseline (or the file doesn't exist yet) → write the buffer.
     *  - Disk diverged from our baseline → an external writer won; leave both the file and the
     *    buffer alone and report it, so the caller can reconcile instead of reverting the file.
     */
    fun save(): SaveResult {
        val text = state.text.toString()
        val disk = runCatching { file.readText() }.getOrNull()
        if (text == disk) {
            markSaved(text)
            return SaveResult.AlreadyOnDisk
        }
        if (disk != null && disk != savedText) {
            // The user's edit is still pending — leave hasUserEdit set so a later save can retry it
            // once the file stops moving under us; reconciliation handles the clean case.
            return SaveResult.ExternalChange(disk)
        }
        file.writeText(text)
        markSaved(text)
        return SaveResult.Saved
    }

    /**
     * Advance the baseline to [written] — the exact text just put on disk — and clear the dirty
     * marker, but only while the buffer still holds that text.
     *
     * A save runs off the UI thread and takes a whole file read plus a write, so the user can type
     * between the snapshot [save] took and this bookkeeping. Those keystrokes are unsaved user work.
     * Clearing [hasUserEdit] for them leaves a buffer that looks clean yet sits ahead of disk, which
     * is precisely the state [diskTextIfDivergedAndClean] treats as programmatic drift: the very next
     * reconcile (a save triggers one, and the git poll runs another every few seconds) adopts the
     * disk copy over the buffer, silently dropping those characters and — because the adopt rewrites
     * the whole buffer — leaving the caret at the end of the document, so everything typed afterwards
     * lands at the bottom of the file.
     *
     * Internal rather than private because that race is only reachable by interleaving a real write
     * with real typing; the tests drive this bookkeeping directly to pin the invariant down.
     */
    internal fun markSaved(written: String) {
        savedText = written
        if (state.text.toString() == written) hasUserEdit = false
    }

    /**
     * Disk content to adopt when this buffer holds no genuine unsaved *user* edits and what we're
     * showing differs from the file on disk. Returns null otherwise — when the user has real pending
     * edits ([hasUserEdit], which must win), when the buffer already matches disk, and when the file
     * is missing/unreadable (a vanished file must not blank the buffer). Pure read: safe off the UI
     * thread; hand the result to [adoptDiskText] on the UI thread.
     *
     * The guard is [hasUserEdit], not [isModified], on purpose. A buffer can drift off its baseline
     * without the user touching it — the diff view re-seeds its per-line cells into the shared buffer,
     * a "writeback echo" that leaves [isModified] true but [hasUserEdit] false. That drift is not work
     * to protect: gating reload on [isModified] would strand such a buffer, freezing the working side
     * of the diff on stale content while the real on-disk change never shows. Comparing against the
     * live [state] text (not [savedText]) also heals a pure drift with no external write.
     */
    fun diskTextIfDivergedAndClean(): String? {
        if (hasUserEdit) return null
        val disk = runCatching { file.readText() }.getOrNull() ?: return null
        return if (disk == state.text.toString()) null else disk
    }

    /**
     * Replace the buffer with [diskText] and treat it as the new on-disk baseline. Call on the UI
     * thread.
     *
     * The caret is carried over (clamped into the new text) rather than left wherever a whole-buffer
     * replace puts it, which is the end of the document. An adopt fires while the user is looking at
     * the file — an agent or a checkout rewrote it underneath them — and dumping the caret at the
     * bottom of the file mid-sentence sends every keystroke after it to the wrong place.
     */
    fun adoptDiskText(diskText: String) {
        val caret = state.selection.start.coerceIn(0, diskText.length)
        state.edit {
            replace(0, length, diskText)
            selection = TextRange(caret)
        }
        savedText = diskText
        hasUserEdit = false
    }
}

class FileEditStore {
    private val edits = mutableStateMapOf<String, FileEdit>()

    fun edit(tab: Tab.FileView): FileEdit = edits.getOrPut(tab.id) {
        val text = runCatching { tab.file.readText() }.getOrDefault("")
        FileEdit(text, tab.file)
    }

    fun peek(id: String): FileEdit? = edits[id]

    /**
     * Open editor buffers backing [file] (matched by absolute path), or empty when no tab is open on
     * it. Callers that refresh the on-screen copy of a file after changing it on disk must consult
     * this *before* reading the file back: a file nothing is displaying — e.g. a large binary being
     * reverted — must never be pulled into heap just to update a buffer that isn't there.
     */
    fun editorsFor(file: File): List<FileEdit> {
        val path = file.absolutePath
        return edits.values.filter { it.file.absolutePath == path }
    }

    fun close(id: String) {
        edits.remove(id)
    }

    /** A stable copy of the live buffers, for reconciling them against disk. Call on the UI thread. */
    fun snapshot(): List<FileEdit> = edits.values.toList()
}
