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
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.io.File

@Composable
fun ConfirmDeleteDialog(targets: List<File>, onConfirm: () -> Unit, onCancel: () -> Unit) {
    if (targets.isEmpty()) return
    val single = targets.singleOrNull()
    val title = when {
        single != null && single.isDirectory -> "Delete directory?"
        single != null -> "Delete file?"
        else -> "Delete ${targets.size} items?"
    }
    Popup(
        popupPositionProvider = CenteredPositionProvider,
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
            Text(title)
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
}

private const val MaxListedPaths = 12

private val CenteredPositionProvider: PopupPositionProvider = object : PopupPositionProvider {
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
