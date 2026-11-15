package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.agent.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ConversationBranchDialog(
    source: AgentSession,
    click: ConversationClick,
    sessions: AgentSessions,
    accounts: List<Account>,
    onClose: () -> Unit,
) {
    var point by remember { mutableStateOf<ConversationBranchPoint?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var question by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(source, click) {
        runCatching {
            withContext(Dispatchers.IO) {
                ConversationBranch.nearest(ConversationBranch.points(source, accounts), click)
            }
        }.onSuccess { point = it }.onFailure { error = it.message ?: "Could not read this conversation." }
    }
    DialogFrame("Branch Here", onClose = { if (!sending) onClose() }, size = DpSize(640.dp, 570.dp)) {
        Text("Conversation before the branch")
        val scroll = rememberScrollState()
        LaunchedEffect(point, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
        SelectionContainer(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(Modifier.fillMaxSize().border(1.dp, AgentMuted.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                .verticalScroll(scroll).padding(12.dp)) {
                Text(point?.preview ?: error ?: "Finding the nearest conversation boundary…")
            }
        }
        Text("Your question")
        val foreground = JewelTheme.globalColors.text.normal
        BasicTextField(
            value = question,
            onValueChange = { question = it },
            enabled = !sending,
            textStyle = TextStyle(color = foreground, fontSize = 14.sp),
            cursorBrush = SolidColor(foreground),
            modifier = Modifier.fillMaxWidth().height(170.dp)
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, AgentMuted.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(10.dp).focusRequester(focus),
        )
        LaunchedEffect(Unit) { focus.requestFocus() }
        Text("The original conversation is kept in history. Project files stay as they are.", color = AgentMuted)
        if (point != null) error?.let { Text(it, color = ChangeColors.REMOVED) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClose, enabled = !sending) { Text("Cancel") }
            Tooltip(
                tooltip = { Text("Starts a new conversation from this point with your question and keeps the original conversation.") },
                tooltipPlacement = BranchTooltipPlacement,
            ) {
                DefaultButton(enabled = point != null && question.isNotBlank() && !sending, onClick = {
                    val selected = point ?: return@DefaultButton
                    val submitted = question.trim()
                    sending = true
                    error = null
                    scope.launch {
                        runCatching {
                            val native = withContext(Dispatchers.IO) { ConversationBranch.create(selected) }
                            sessions.branch(source, selected, native, submitted)
                        }.onSuccess { onClose() }.onFailure {
                            error = it.cause?.message ?: it.message ?: "Could not create this branch."
                            sending = false
                        }
                    }
                }) { Text(if (sending) "Creating branch…" else "Send Branch request") }
            }
        }
    }
}

/** Keeps the requested explanation above Send, leaving the button available to click. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object BranchTooltipPlacement : TooltipPlacement {
    @Composable
    override fun positionProvider(cursorPosition: Offset): PopupPositionProvider {
        val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
        return remember(gap) {
            object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset(
                    anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0),
                )
            }
        }
    }
}
