package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
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
import org.jetbrains.jewel.ui.component.TextField

/**
 * Centered single-field prompt used by the project-tree "new file / new directory / new package"
 * and "copy file" actions. Enter (or the confirm button) submits, Esc (or click-outside)
 * cancels.
 *
 * [onSubmit] performs the action and returns an error string to show in-place — keeping the
 * dialog open so the user can fix the name — or null on success. On success the caller is
 * expected to clear the dialog state, which is what removes this popup.
 */
@Composable
fun NewEntryDialog(
    title: String,
    description: String,
    initialText: String = "",
    confirmLabel: String = "Create",
    onSubmit: (String) -> String?,
    onCancel: () -> Unit,
) {
    // For a prefilled name (copy), preselect the base name so the user can retype it without
    // clobbering the extension; otherwise leave the caret at the (empty) end.
    val dotIdx = initialText.lastIndexOf('.')
    val initialSelection = when {
        initialText.isEmpty() -> TextRange.Zero
        dotIdx > 0 -> TextRange(0, dotIdx)
        else -> TextRange(0, initialText.length)
    }
    val state = rememberTextFieldState(initialText, initialSelection)
    val focusRequester = remember { FocusRequester() }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun submit() {
        error = onSubmit(state.text.toString())
    }

    Popup(
        popupPositionProvider = NewEntryPositionProvider,
        onDismissRequest = onCancel,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, border, RoundedCornerShape(8.dp))
                .width(460.dp)
                .padding(16.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> { onCancel(); true }
                        Key.Enter, Key.NumPadEnter -> { submit(); true }
                        else -> false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title)
            Text(description, color = ChangeColors.UNTRACKED)
            TextField(
                state = state,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            error?.let { Text(it, color = ChangeColors.REMOVED) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                DefaultButton(onClick = ::submit) { Text(confirmLabel) }
            }
        }
    }
}

private val NewEntryPositionProvider: PopupPositionProvider = object : PopupPositionProvider {
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
