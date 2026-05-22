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

    fun reloadFromDisk() {
        val text = runCatching { file.readText() }.getOrDefault("")
        state.edit { replace(0, length, text) }
        savedText = text
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
}
