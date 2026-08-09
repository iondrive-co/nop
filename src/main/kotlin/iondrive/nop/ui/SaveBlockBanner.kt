package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

private val BlockedBackground = Color(0xFF6B3A1F)
private val BlockedBody = Color(0xFFF2D5B8)
private val BlockedHeading = Color(0xFFFFC98A)
private val NoteBackground = Color(0xFF2E4B33)
private val NoteBody = Color(0xFFD6EBD9)

/**
 * The strip above an editor whose buffer has stopped reaching disk, and the only signal the user
 * gets that this has happened. It stays up for exactly as long as [FileEdit.saveBlock] does — a
 * save that gets through clears it with no input, so a conflict that resolves itself (the other
 * writer putting the file back) disappears on its own.
 *
 * Deliberately inline rather than a modal: the block is raised by an autosave nobody asked for, so
 * a dialog stealing focus mid-keystroke — potentially every 400ms, for as long as the conflict
 * lasts — would be worse than the silence it replaces. Nothing here is destructive without a
 * click, and the buffer is never touched until the user picks a side.
 */
@Composable
fun SaveStatusStrip(edit: FileEdit, onSaved: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    // Survives the block being cleared: the merge that clears it is exactly when the user most
    // needs telling whether they now have conflict markers to deal with.
    var mergeNote by remember(edit) { mutableStateOf<String?>(null) }
    val block = edit.saveBlock

    if (block == null) {
        val note = mergeNote ?: return
        MergeNote(note, onDismiss = { mergeNote = null }, modifier = modifier)
        return
    }

    Column(
        modifier = modifier.fillMaxWidth()
            .background(BlockedBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (block) {
            is SaveBlock.Conflict -> {
                Text(
                    "Not saving — ${edit.file.name} changed on disk",
                    color = BlockedHeading,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Something else wrote this file after you started editing. Your changes are safe " +
                        "in the editor, but nothing will be written to disk until you choose.",
                    color = BlockedBody,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            if (withContext(Dispatchers.IO) { edit.overwriteDisk() } is SaveResult.Saved) onSaved()
                        }
                    }) { Text("Keep mine") }
                    OutlinedButton(onClick = {
                        edit.adoptDiskText(block.diskText)
                        onSaved()
                    }) { Text("Take theirs") }
                    OutlinedButton(onClick = {
                        val conflicts = edit.mergeDiskIntoBuffer(block.diskText)
                        mergeNote = if (conflicts == 0) {
                            "Merged cleanly — both sets of changes are in the file."
                        } else {
                            "Merged with $conflicts conflict${if (conflicts == 1) "" else "s"}. Look for " +
                                "<<<<<<< markers in the file, or open its diff to resolve them there."
                        }
                        onSaved()
                    }) { Text("Merge") }
                    Text("overwrite the file · discard my edits · combine both", color = BlockedBody)
                }
            }
            is SaveBlock.Failed -> {
                Text("Couldn't write ${edit.file.name}", color = BlockedHeading, fontWeight = FontWeight.Bold)
                Text(block.message, color = BlockedBody)
                OutlinedButton(onClick = {
                    scope.launch {
                        if (withContext(Dispatchers.IO) { edit.save() } is SaveResult.Saved) onSaved()
                    }
                }) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun MergeNote(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(text) {
        delay(15_000)
        onDismiss()
    }
    Row(
        modifier = modifier.fillMaxWidth()
            .background(NoteBackground)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = NoteBody, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
    }
}
