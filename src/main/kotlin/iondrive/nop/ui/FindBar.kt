package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

// The in-place find bar, shared by every surface that can be searched: the file editor
// (FileEditView) and the side-by-side diffs (DiffListScaffold). Keeping one implementation is what
// makes Ctrl+F feel the same wherever the caret happens to be — same key handling, same "n of m"
// chip, same highlight colours.

/** Background behind every hit of the current query. */
@Composable
internal fun findMatchColor(): Color =
    if (JewelTheme.isDark) Color(0xFF5A4A20) else Color(0xFFFFF38C)

/** Background behind the active hit — the one Next/Prev is parked on — so it stands out from the rest. */
@Composable
internal fun findActiveMatchColor(): Color =
    if (JewelTheme.isDark) Color(0xFF8A6D1A) else Color(0xFFFFB74D)

/**
 * Slim search bar pinned above the content being searched. Enter / Shift+Enter cycle through
 * matches; Esc closes the bar and clears the highlight. The count chip reads "n of m" so the user
 * can see both their position and how many total matches exist for the current query.
 */
@Composable
internal fun FindBar(
    state: TextFieldState,
    focusRequester: FocusRequester,
    matchCount: Int,
    currentIndex: Int,
    onUserEdit: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
) {
    val isDark = JewelTheme.isDark
    val barBg = if (isDark) Color(0xFF2B2D30) else Color(0xFFEDEEF2)
    val fg = if (isDark) Color(0xFFA9B7C6) else Color(0xFF000000)
    val mutedFg = if (isDark) Color(0xFF7A8290) else Color(0xFF6B7280)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(barBg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> { onClose(); true }
                    Key.Enter, Key.NumPadEnter -> {
                        if (event.isShiftPressed) onPrev() else onNext()
                        true
                    }
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            state = state,
            // Fires only for genuine user input, never for programmatic state.edit {} — so a
            // global-search seed that sets the query doesn't count as the user retyping it.
            inputTransformation = InputTransformation { onUserEdit() },
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            textStyle = TextStyle(fontFamily = NopFonts.Mono, fontSize = 13.sp, color = fg),
            cursorBrush = SolidColor(fg),
            lineLimits = TextFieldLineLimits.SingleLine,
        )
        val countText = when {
            state.text.isEmpty() -> ""
            matchCount == 0 -> "no matches"
            else -> "${currentIndex + 1} of $matchCount"
        }
        if (countText.isNotEmpty()) {
            Text(countText, color = mutedFg, style = TextStyle(fontSize = 12.sp))
        }
        Text(
            "▲",
            color = if (matchCount > 0) fg else mutedFg,
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Press) onPrev()
                        }
                    }
                },
        )
        Text(
            "▼",
            color = if (matchCount > 0) fg else mutedFg,
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Press) onNext()
                        }
                    }
                },
        )
        Text(
            "×",
            color = mutedFg,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Press) onClose()
                        }
                    }
                },
        )
    }
}
