package iondrive.nop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import iondrive.nop.agent.ConversationSummary
import iondrive.nop.agent.ConversationSummaryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A read-only snapshot; only opening it or pressing Refresh reads the conversation again. */
@Composable
internal fun AgentSummaryView(state: ConversationSummaryState, log: Path) {
    LaunchedEffect(state, state.revision) {
        if (state.snapshot == null && state.error == null) {
            val result = withContext(Dispatchers.IO) { runCatching { ConversationSummary.read(log) } }
            result.onSuccess {
                state.scrollIndex = it.messages.lastIndex.coerceAtLeast(0)
                state.snapshot = it
            }.onFailure {
                state.error = "Could not read this conversation. Return to the live view or try Refresh."
            }
        }
    }
    Column(
        Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)
            .padding(bottom = LocalTerminalBottomInset.current),
    ) {
        val snapshot = state.snapshot
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val at = snapshot?.capturedAt?.let {
                DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
            }
            Text(
                when {
                    state.error != null -> "Conversation snapshot"
                    at == null -> "Reading conversation…"
                    else -> "Snapshot at $at · Turn Summary off to type or answer questions"
                },
                color = AgentMuted,
                modifier = Modifier.weight(1f),
            )
            if (snapshot != null) {
                Text("Copy", color = AgentMuted, modifier = Modifier.clickable {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(snapshot.plainText()), null)
                })
            }
            Text("Refresh", color = AgentMuted, modifier = Modifier.clickable { state.refresh() })
        }
        when {
            state.error != null -> Box(Modifier.fillMaxSize().padding(16.dp)) { Text(state.error!!) }
            snapshot == null -> Unit
            snapshot.messages.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp)) {
                Text("No recorded messages yet. Refresh after the agent has written its reply.", color = AgentMuted)
            }
            else -> key(state.revision) { SummaryMessages(snapshot, state) }
        }
    }
}

@Composable
private fun SummaryMessages(snapshot: ConversationSummary, state: ConversationSummaryState) {
    val isDark = JewelTheme.isDark
    val userAccent = if (isDark) Color(0xFF98B7DF) else Color(0xFF4B6E9E)
    val incomingAccent = if (isDark) Color(0xFFC0A5D8) else Color(0xFF7C6199)
    val replyAccent = if (isDark) Color(0xFF95BDB0) else Color(0xFF476F64)
    val scroll = rememberLazyListState(state.scrollIndex, state.scrollOffset)
    val revision = state.revision
    DisposableEffect(scroll) {
        onDispose {
            if (state.revision == revision) {
                state.scrollIndex = scroll.firstVisibleItemIndex
                state.scrollOffset = scroll.firstVisibleItemScrollOffset
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        SelectionContainer {
            LazyColumn(
                state = scroll,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(snapshot.messages) { message ->
                    val accent = when {
                        message.assistant -> replyAccent
                        message.speaker == "You" -> userAccent
                        else -> incomingAccent
                    }
                    Column(
                        Modifier.fillMaxWidth()
                            .background(accent.copy(alpha = if (isDark) 0.09f else 0.055f), RoundedCornerShape(6.dp))
                            .drawBehind {
                                drawLine(
                                    color = accent.copy(alpha = 0.45f),
                                    start = Offset(0f, 8.dp.toPx()),
                                    end = Offset(0f, size.height - 8.dp.toPx()),
                                    strokeWidth = 2.dp.toPx(),
                                    cap = StrokeCap.Round,
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(message.speaker, fontWeight = FontWeight.SemiBold, color = accent)
                        if (message.assistant) MarkdownContent(message.text) else Text(message.text)
                    }
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd),
            style = NopScrollbarStyle,
        )
    }
}
