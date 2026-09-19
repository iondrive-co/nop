package iondrive.nop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.io.File

/**
 * Confirms deleting files or directories from the project tree, listing exactly what is about to
 * go.
 *
 * A window of its own, not a popup centred in nop's, because the middle of nop's window is often the
 * tool region and a terminal there is drawn over any popup. See [DialogFrame]. Enter is deliberately
 * not a shortcut for deleting.
 */
@Composable
fun ConfirmDeleteDialog(targets: List<File>, onConfirm: () -> Unit, onCancel: () -> Unit) {
    if (targets.isEmpty()) return
    val single = targets.singleOrNull()
    val title = when {
        single != null && single.isDirectory -> "Delete directory?"
        single != null -> "Delete file?"
        else -> "Delete ${targets.size} items?"
    }
    DialogFrame(title = title, onClose = onCancel, size = DpSize(420.dp, Dp.Unspecified)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        // List the paths so it's unambiguous exactly what's about to go. Cap the list so a large
        // multi-selection can't grow the dialog past the screen; the count in the title still
        // conveys the full scope.
        val shown = targets.take(MaxListedPaths)
        for (t in shown) Text(t.absolutePath, color = ChangeColors.UNTRACKED)
        if (targets.size > shown.size) {
            Text("…and ${targets.size - shown.size} more", color = ChangeColors.UNTRACKED)
        }
        if (targets.any { it.isDirectory }) {
            Text(
                if (single != null) "All files inside will be removed."
                else "Any directories will be removed with all their contents.",
                color = ChangeColors.REMOVED,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            DefaultButton(onClick = onConfirm) { Text("Delete") }
        }
    }
}

private const val MaxListedPaths = 12
