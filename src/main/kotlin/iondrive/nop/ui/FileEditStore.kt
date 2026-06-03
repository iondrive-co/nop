package iondrive.nop.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Per-file editor state cached across tab switches. */
class FileEdit(initialText: String, val file: File) {
    val state: TextFieldState = TextFieldState(initialText)
    var savedText: String by mutableStateOf(initialText)
        private set

    val isModified: Boolean
        get() = state.text.toString() != savedText

    fun save() {
        val text = state.text.toString()
        file.writeText(text)
        savedText = text
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
