package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * Centered "find file by name" popup. Up/Down navigates results, Enter opens the highlighted
 * file via [onPick], Esc dismisses. The text field grabs focus on first composition so the user
 * can start typing immediately after the double-shift trigger.
 */
@Composable
fun FileSearchDialog(
    files: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTextFieldState()
    val focusRequester = remember { FocusRequester() }
    var selectedIndex by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    val query by remember { derivedStateOf { state.text.toString() } }
    val results by remember(files) {
        derivedStateOf { FileSearchRanking.rank(query, files) }
    }

    LaunchedEffect(results) {
        if (selectedIndex >= results.size) selectedIndex = 0
    }

    LaunchedEffect(selectedIndex, results) {
        if (results.isNotEmpty()) {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo.any { it.index == selectedIndex }
            if (!visible) listState.animateScrollToItem(selectedIndex.coerceAtLeast(0))
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Popup(
        popupPositionProvider = TopCenterPositionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, border, RoundedCornerShape(8.dp))
                .width(560.dp)
                .padding(12.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Escape -> { onDismiss(); true }
                        Key.DirectionDown -> {
                            if (results.isNotEmpty()) {
                                selectedIndex = (selectedIndex + 1).coerceAtMost(results.size - 1)
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            results.getOrNull(selectedIndex)?.let(onPick)
                            true
                        }
                        else -> false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                state = state,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            if (results.isEmpty()) {
                val muted = if (JewelTheme.isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
                Text(
                    if (query.isEmpty()) "Start typing to search files" else "No matches",
                    color = muted,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 380.dp).fillMaxWidth(),
                ) {
                    items(results) { path ->
                        val idx = results.indexOf(path)
                        ResultRow(
                            path = path,
                            selected = idx == selectedIndex,
                            onClick = { onPick(path) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(path: String, selected: Boolean, onClick: () -> Unit) {
    val name = path.substringAfterLast('/')
    val dir = if ('/' in path) path.substringBeforeLast('/') else ""
    val highlight = if (JewelTheme.isDark) Color(0xFF2E436E) else Color(0xFFD4E2FF)
    val muted = if (JewelTheme.isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
    val bg = if (selected) highlight else Color.Transparent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column {
            Text(name, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
            if (dir.isNotEmpty()) {
                Text(dir, color = muted, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp))
            }
        }
    }
}

private val TopCenterPositionProvider: PopupPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = (windowSize.width - popupContentSize.width) / 2,
        y = (windowSize.height / 6).coerceAtLeast(40),
    )
}
