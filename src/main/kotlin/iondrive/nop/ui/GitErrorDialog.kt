package iondrive.nop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text

/**
 * Reports a failed git operation (commit, stash, …). Git mutations can fail for reasons outside the
 * app's control — a file too large to add, a locked index, a stash that won't apply cleanly — and
 * before this these failures escaped their launched coroutine and crashed the window. Now the
 * underlying message is shown here (selectable, so it can be copied into a bug report) and dismissed.
 *
 * A window of its own, not a popup centred in nop's: these come from the commit panel, beside the
 * agent pane, and a terminal there is drawn over any popup. See [DialogFrame].
 */
@Composable
fun GitErrorDialog(title: String, detail: String, onDismiss: () -> Unit) {
    DialogFrame(title = title, onClose = onDismiss, size = DpSize(420.dp, Dp.Unspecified), onSubmit = onDismiss) {
        Text(title, fontWeight = FontWeight.SemiBold)
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    detail.ifBlank { "The operation failed for an unknown reason." },
                    color = ChangeColors.REMOVED,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            DefaultButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
