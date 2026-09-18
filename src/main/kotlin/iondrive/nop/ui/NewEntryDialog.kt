package iondrive.nop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * Single-field prompt used by the project-tree "new file / new directory / new package" and "copy
 * file" actions, and for naming tabs and windows. Enter (or the confirm button) submits, Esc (or the
 * window's close button) cancels.
 *
 * A window of its own rather than a popup centred in nop's, because the middle of nop's window is
 * often the tool region, and a terminal there is drawn over any popup: the prompt came up with its
 * field and buttons missing. See [DialogFrame].
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

    // Its height follows the description and any error, so only the width is fixed.
    DialogFrame(title = title, onClose = onCancel, size = DpSize(460.dp, Dp.Unspecified), onSubmit = ::submit) {
        Text(title, fontWeight = FontWeight.SemiBold)
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

/** Centres a dialog horizontally, a third of the way down — shared by every centred prompt. */
internal val NewEntryPositionProvider: PopupPositionProvider = object : PopupPositionProvider {
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
