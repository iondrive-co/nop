package iondrive.nop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import iondrive.nop.git.FileChange
import org.jetbrains.jewel.ui.component.Text

/** A commit held back until the user confirms the [unreviewed] files nop ticked by itself. */
data class PendingCommit(val message: String, val included: List<FileChange>, val unreviewed: List<String>)

/**
 * Asks before committing files the user did not tick: ones that left the change list and came back,
 * and ones that appeared while the message was being written. See [CommitSelection].
 */
@Composable
fun ConfirmCommitDialog(pending: PendingCommit, returned: Set<String>, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ConfirmGitActionDialog(
        title = "Commit ${plural(pending.unreviewed.size, "file")} nop ticked?",
        confirmLabel = "Commit",
        onConfirm = onConfirm,
        onCancel = onCancel,
    ) {
        Text("Returned files left the list and came back. New files appeared while you wrote the message.")
        val shown = pending.unreviewed.take(MaxListedChanges)
        for (path in shown) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(commitMark(path in returned), color = ChangeColors.CONFLICT)
                Text(path, color = ChangeColors.UNTRACKED)
            }
        }
        if (pending.unreviewed.size > shown.size) {
            Text("…and ${pending.unreviewed.size - shown.size} more", color = ChangeColors.UNTRACKED)
        }
    }
}

/** The mark on a file nop ticked by itself: "returned" if it came back, "new" if it appeared. */
internal fun commitMark(returned: Boolean) = if (returned) "returned" else "new"
