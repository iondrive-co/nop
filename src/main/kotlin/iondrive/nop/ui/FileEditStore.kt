package iondrive.nop.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import iondrive.nop.diff.ThreeWayMerge
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

    /** The write itself failed — permissions, a full disk, a directory that no longer exists. */
    data class Failed(val message: String) : SaveResult
}

/**
 * Why the buffer on screen is not on disk, or null when the last attempt got there.
 *
 * This exists because both of nop's save paths are silent by construction: the autosave nobody
 * asked for, and (before this) no explicit save at all. A [FileEdit.save] that declines to write
 * leaves the user typing into a buffer that will never reach disk, with nothing on screen saying
 * so — the file just quietly stops being saved for the rest of the session, because neither the
 * baseline nor the disk content changes on a refusal, so every later attempt refuses identically.
 * Held as observable state so the editor can keep a banner up for exactly as long as the condition
 * lasts, and drop it by itself the moment a save succeeds.
 */
sealed interface SaveBlock {
    /**
     * Disk moved under us and holds [diskText]; the buffer was left alone. Only the user can say
     * which version wins, so this stays put until they choose — see the resolutions on [FileEdit]:
     * [FileEdit.overwriteDisk], [FileEdit.adoptDiskText] and [FileEdit.mergeDiskIntoBuffer].
     */
    data class Conflict(val diskText: String) : SaveBlock

    /** The write threw. Retrying is worth offering — a full disk or a lock usually clears. */
    data class Failed(val message: String) : SaveBlock
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

    /**
     * How far this file is scrolled sideways, for the same reason [scroll] lives here. Only used
     * while word wrap is off — with it on there is nothing off to the right to scroll to.
     */
    val hScroll: ScrollState = ScrollState(0)
    val findQuery: TextFieldState = TextFieldState()
    val replaceWith: TextFieldState = TextFieldState()
    var savedText: String by mutableStateOf(initialText)
        private set

    /** Raw "the user has touched this buffer" latch. See [hasUserEdit], which qualifies it. */
    private var userEditLatch: Boolean by mutableStateOf(false)

    /**
     * True when the user has unsaved work in this buffer: they changed it *through the editor*
     * (typed, pasted, resolved a conflict, reverted a hunk) **and** it still differs from the
     * baseline. The autosave consults it so that buffer mutations nop makes on its own — adopting
     * an externally-changed file, re-seeding the diff view's per-line cells after a re-diff — can
     * never trigger a disk write. nop only ever writes back what the user actually edited; this is
     * what stops it reverting a file after a checkout/pull/merge it didn't make.
     *
     * Both halves are load-bearing, and the second was missing. As a bare latch this could only be
     * cleared by a save or an adopt, and neither runs when the buffer already equals [savedText]:
     * the autosave skips it (nothing to write) and reconcile is gated on this very flag. Typing a
     * character and deleting it again inside the autosave debounce was enough to reach that state,
     * and the buffer then ignored every external write for the rest of the session — no refresh,
     * no save attempt, and so no [saveBlock] and nothing on screen to explain it. Closing the tab
     * was the only way out.
     *
     * Qualifying the latch with [isModified] fixes that at the source and can't lose work: when the
     * buffer matches the baseline there is, by definition, nothing unsaved to protect. A buffer that
     * drifted *without* the user (the diff view's writeback echo) still reads false here, so it
     * still reloads from disk, which is why this is [isModified] on top of the latch rather than
     * instead of it.
     */
    val hasUserEdit: Boolean
        get() = userEditLatch && isModified

    /**
     * Why the last save didn't reach disk, or null if it did. Set and cleared by [save]; the
     * editor renders it as a banner over the file, which is the only thing that tells the user
     * their typing has stopped being persisted. See [SaveBlock].
     */
    var saveBlock: SaveBlock? by mutableStateOf(null)
        private set

    /** Record that the user changed the buffer via the editor. Call on the UI thread. */
    fun markUserEdit() {
        userEditLatch = true
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
            saveBlock = null
            return SaveResult.AlreadyOnDisk
        }
        if (disk != null && disk != savedText) {
            // The user's edit is still pending — leave hasUserEdit set so a later save can retry it
            // once the file stops moving under us; reconciliation handles the clean case. Nothing
            // here can un-stick it on its own, which is why the block is published to the UI.
            saveBlock = SaveBlock.Conflict(disk)
            return SaveResult.ExternalChange(disk)
        }
        return writeBuffer(text)
    }

    /**
     * Write the buffer over whatever is on disk, skipping the compare-and-swap in [save] — the
     * "keep mine" resolution, where the user has seen the conflict and chosen their own version.
     */
    fun overwriteDisk(): SaveResult = writeBuffer(state.text.toString())

    /**
     * Three-way merge the on-disk version back into the buffer: [savedText] is the common
     * ancestor, the buffer is ours, [diskText] is theirs. Edits to different parts of the file
     * both survive; overlapping ones come back as conflict markers, which the diff view already
     * knows how to resolve. Returns how many conflict blocks it had to leave behind.
     *
     * The baseline advances to [diskText] because that is genuinely what the file holds now, which
     * un-sticks the compare-and-swap: the merged buffer is a real user edit ([hasUserEdit] stays
     * set), so the next save writes it — markers and all, exactly as git leaves a conflicted merge
     * in the working tree. Call on the UI thread.
     */
    fun mergeDiskIntoBuffer(diskText: String): Int {
        val merged = ThreeWayMerge.merge(base = savedText, ours = state.text.toString(), theirs = diskText)
        val caret = state.selection.start.coerceIn(0, merged.text.length)
        state.edit {
            replace(0, length, merged.text)
            selection = TextRange(caret)
        }
        savedText = diskText
        userEditLatch = true
        saveBlock = null
        return merged.conflicts
    }

    /**
     * The one place bytes actually go to disk. The write is guarded because an IOException here —
     * a read-only file, a full disk, a directory deleted underneath us — used to propagate out of
     * the autosave's `collect`, cancelling the coroutine and taking autosave for that tab down for
     * good, silently. A failure is now just another [SaveBlock] the user can see and retry.
     */
    private fun writeBuffer(text: String): SaveResult {
        val failure = runCatching { file.writeText(text) }.exceptionOrNull()
        if (failure != null) {
            val message = failure.message ?: failure::class.simpleName ?: "write failed"
            saveBlock = SaveBlock.Failed(message)
            return SaveResult.Failed(message)
        }
        markSaved(text)
        saveBlock = null
        return SaveResult.Saved
    }

    /**
     * Advance the baseline to [written] — the exact text just put on disk — and clear the dirty
     * marker, but only while the buffer still holds that text.
     *
     * A save runs off the UI thread and takes a whole file read plus a write, so the user can type
     * between the snapshot [save] took and this bookkeeping. Those keystrokes are unsaved user work.
     * Clearing the edit latch for them leaves a buffer that looks clean yet sits ahead of disk, which
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
        if (state.text.toString() == written) userEditLatch = false
    }

    /**
     * Disk content to adopt when this buffer holds no genuine unsaved *user* edits and what we're
     * showing differs from the file on disk. Returns null otherwise — when the user has real pending
     * edits ([hasUserEdit], which must win), when the buffer already matches disk, and when the file
     * is missing/unreadable (a vanished file must not blank the buffer). Pure read: safe off the UI
     * thread; hand the result to [adoptDiskText] on the UI thread.
     *
     * The guard is [hasUserEdit] — which is the user's edit latch *and* [isModified] — rather than
     * either half alone, and both halves matter here. A buffer can drift off its baseline without the
     * user touching it: the diff view re-seeds its per-line cells into the shared buffer, a "writeback
     * echo" that leaves [isModified] true but the latch false. That drift is not work to protect, so
     * gating on [isModified] alone would strand such a buffer, freezing the working side of the diff
     * on stale content while the real on-disk change never shows. Equally, gating on the bare latch
     * stranded a buffer the user had touched and then returned to its baseline — see [hasUserEdit].
     * Comparing against the live [state] text (not [savedText]) also heals a pure drift with no
     * external write.
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
        userEditLatch = false
        // Taking the disk copy resolves any conflict by definition — there is nothing left of ours
        // to be blocked on.
        saveBlock = null
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
