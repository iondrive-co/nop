package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import iondrive.nop.git.ChangeKind
import iondrive.nop.git.FileChange
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * Confirms a per-file revert from the change list. Revert is destructive and — unlike a soft reset
 * or a stash — leaves nothing to recover, so we spell out exactly what will happen to this file
 * before doing it. The wording is keyed to the change kind so an untracked-file delete reads
 * differently from a modified-file rollback.
 */
@Composable
fun ConfirmRevertDialog(change: FileChange, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val detail = when (change.kind) {
        ChangeKind.UNTRACKED, ChangeKind.ADDED ->
            "This new file will be deleted from disk. It isn't in any commit, so this can't be undone."
        ChangeKind.REMOVED, ChangeKind.MISSING ->
            "The file will be restored from the last commit."
        ChangeKind.MODIFIED, ChangeKind.CONFLICT ->
            "Local changes will be discarded and the file restored to the last commit."
    }
    Popup(
        popupPositionProvider = RevertCenteredPositionProvider,
        onDismissRequest = onCancel,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(JewelTheme.globalColors.panelBackground)
                .width(420.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Revert file?")
            Text(change.path, color = ChangeColors.UNTRACKED)
            Text(detail, color = ChangeColors.REMOVED)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                DefaultButton(onClick = onConfirm) { Text("Revert") }
            }
        }
    }
}

private val RevertCenteredPositionProvider: PopupPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = (windowSize.width - popupContentSize.width) / 2,
        y = (windowSize.height - popupContentSize.height) / 3,
    )
}
