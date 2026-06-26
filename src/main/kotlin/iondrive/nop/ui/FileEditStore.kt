package iondrive.nop.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
            savedText = text
            hasUserEdit = false
            return SaveResult.AlreadyOnDisk
        }
        if (disk != null && disk != savedText) {
            // The user's edit is still pending — leave hasUserEdit set so a later save can retry it
            // once the file stops moving under us; reconciliation handles the clean case.
            return SaveResult.ExternalChange(disk)
        }
        file.writeText(text)
        savedText = text
        hasUserEdit = false
        return SaveResult.Saved
    }

    /**
     * Disk content when this buffer has no unsaved in-app edits *and* the file on disk has diverged
     * from what we last loaded or saved (an external editor, a branch switch, a pull, an agent). Returns
     * null otherwise — including when the buffer is modified (the user's unsaved work must win) and when
     * the file is missing/unreadable (a vanished file must not blank the buffer). Pure read: safe off the
     * UI thread; hand the result to [adoptDiskText] on the UI thread.
     */
    fun diskTextIfDivergedAndClean(): String? {
        if (isModified) return null
        val disk = runCatching { file.readText() }.getOrNull() ?: return null
        return if (disk == savedText) null else disk
    }

    /** Replace the buffer with [diskText] and treat it as the new on-disk baseline. Call on the UI thread. */
    fun adoptDiskText(diskText: String) {
        state.edit { replace(0, length, diskText) }
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

    fun close(id: String) {
        edits.remove(id)
    }

    /** A stable copy of the live buffers, for reconciling them against disk. Call on the UI thread. */
    fun snapshot(): List<FileEdit> = edits.values.toList()
}
