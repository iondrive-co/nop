package iondrive.nop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import iondrive.nop.git.FileChange
import iondrive.nop.git.GitStatus
import iondrive.nop.git.StashEntry
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea
import org.jetbrains.jewel.ui.component.Tooltip
import java.awt.Cursor

@OptIn(ExperimentalFoundationApi::class, ExperimentalJewelApi::class)
@Composable
fun CommitPanel(
    status: GitStatus,
    stashes: List<StashEntry>,
    selectedPaths: Set<String>,
    onToggle: (String) -> Unit,
    onChangeClick: (FileChange) -> Unit,
    onCommit: (message: String, included: List<FileChange>) -> Unit,
    onStash: (message: String) -> Unit,
    onPopStash: (StashEntry) -> Unit,
    onDropStash: (StashEntry) -> Unit,
    commitInFlight: Boolean,
    messageHeight: Dp,
    onMessageHeightChange: (Dp) -> Unit,
    stashInFlight: Boolean = false,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    messageHistory: List<String> = emptyList(),
    canSoftReset: Boolean = false,
    resetInFlight: Boolean = false,
    onSoftReset: () -> Unit = {},
) {
    val messageState = remember { TextFieldState() }
    val inRepo = status.branch != null
    val anyChanges = !status.isClean
    val density = LocalDensity.current

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        val header = when {
            status.isClean && status.branch == null -> "Not a git repository"
            status.isClean -> "Commit — on ${status.branch} · no changes"
            else -> "Commit — on ${status.branch} · ${selectedPaths.size}/${status.changes.size} selected"
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(header, modifier = Modifier.weight(1f))
            if (inRepo) {
                Tooltip(tooltip = { Text("Undo the last commit, keeping its changes (git reset --soft HEAD~1)") }) {
                    OutlinedButton(
                        onClick = onSoftReset,
                        enabled = canSoftReset && !resetInFlight && !commitInFlight,
                    ) {
                        Text(if (resetInFlight) "Resetting…" else "Soft reset")
                    }
                }
            }
            OutlinedButton(onClick = onRefresh, enabled = !refreshing) {
                Text(if (refreshing) "Refreshing…" else "Refresh")
            }
        }

        if (inRepo && anyChanges) {
            if (messageHistory.isNotEmpty()) {
                RecentMessagesDropdown(
                    messages = messageHistory,
                    onPick = { messageState.setText(it) },
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(messageHeight),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextArea(
                    state = messageState,
                    placeholder = { Text("Commit message / stash description") },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DefaultButton(
                        onClick = {
                            val msg = messageState.text.toString().trim()
                            if (msg.isNotEmpty()) {
                                val included = status.changes.filter { it.path in selectedPaths }
                                onCommit(msg, included)
                                messageState.clearText()
                            }
                        },
                        enabled = !commitInFlight && messageState.text.toString().isNotBlank() && selectedPaths.isNotEmpty(),
                    ) {
                        Text(if (commitInFlight) "Committing…" else "Commit")
                    }
                    OutlinedButton(
                        onClick = {
                            val msg = messageState.text.toString().trim()
                            onStash(msg)
                            messageState.clearText()
                        },
                        enabled = !stashInFlight,
                    ) {
                        Text(if (stashInFlight) "Stashing…" else "Stash all")
                    }
                }
            }
            // Drag handle that resizes the commit-message row above. Coerced so it can't
            // shrink past usable text or grow past the surrounding pane.
            MessageResizeHandle(
                onDelta = { dy ->
                    val deltaDp = with(density) { dy.toDp() }
                    val next = (messageHeight + deltaDp).coerceIn(MIN_MESSAGE_HEIGHT, MAX_MESSAGE_HEIGHT)
                    if (next != messageHeight) onMessageHeightChange(next)
                },
            )
        }

        if (stashes.isNotEmpty()) {
            ShelfSection(
                stashes = stashes,
                busy = stashInFlight,
                onPop = onPopStash,
                onDrop = onDropStash,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            )
        }

        val changesListState = rememberLazyListState()
        ScrollableColumn(listState = changesListState, modifier = Modifier.fillMaxSize()) {
            items(status.changes) { change ->
                ChangeRow(
                    change = change,
                    checked = change.path in selectedPaths,
                    onToggle = { onToggle(change.path) },
                    onPathClick = { onChangeClick(change) },
                )
            }
        }
    }
}

private fun TextFieldState.clearText() {
    edit { replace(0, length, "") }
}

private fun TextFieldState.setText(text: String) {
    edit { replace(0, length, text) }
}

internal val MIN_MESSAGE_HEIGHT = 40.dp
internal val MAX_MESSAGE_HEIGHT = 600.dp
internal val DEFAULT_MESSAGE_HEIGHT = 64.dp

@Composable
private fun MessageResizeHandle(onDelta: (Float) -> Unit) {
    val color = if (JewelTheme.isDark) Color(0xFF2B2D30) else Color(0xFFD3D5DB)
    // 4dp-thick draggable bar with an N-resize cursor, matching the SplitPane dividers so
    // the affordance reads as "drag me to resize" without needing an explicit grip icon.
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .padding(vertical = 0.dp)
            .background(color)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .draggable(
                state = rememberDraggableState(onDelta = onDelta),
                orientation = Orientation.Vertical,
                startDragImmediately = true,
            )
    )
}

/**
 * "Recent messages ▾" button that opens a popup of previously used commit messages. Picking one
 * fills the message field via [onPick]. Each row shows the message's first non-blank line, trimmed
 * to one line so multi-line bodies stay scannable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentMessagesDropdown(
    messages: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = !expanded }) {
            Text("Recent messages ▾")
        }
        if (expanded) {
            Popup(
                onDismissRequest = { expanded = false },
                offset = IntOffset(0, 32),
                properties = PopupProperties(focusable = true),
            ) {
                val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
                val bg = JewelTheme.globalColors.panelBackground
                Column(
                    modifier = Modifier
                        .width(460.dp)
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(bg)
                        .border(1.dp, border, RoundedCornerShape(6.dp))
                        .padding(vertical = 4.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (msg in messages) {
                        MessageRow(
                            message = msg,
                            onClick = {
                                expanded = false
                                onPick(msg)
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(message: String, onClick: () -> Unit) {
    val firstLine = message.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: message
    // Truncate so a long subject line can't blow out the popup width or wrap onto two lines.
    val label = if (firstLine.length > 80) firstLine.take(79) + "…" else firstLine
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label)
    }
}

@Composable
private fun ChangeRow(
    change: FileChange,
    checked: Boolean,
    onToggle: () -> Unit,
    onPathClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CheckboxRow(checked = checked, onCheckedChange = { onToggle() }) {}
        Text(ChangeColors.prefixFor(change.kind), color = ChangeColors.forKind(change.kind))
        Text(
            change.path,
            modifier = Modifier.weight(1f).clickable { onPathClick() },
        )
    }
}

@Composable
private fun ShelfSection(
    stashes: List<StashEntry>,
    busy: Boolean,
    onPop: (StashEntry) -> Unit,
    onDrop: (StashEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            "Shelf — ${stashes.size}",
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        // Bound the shelf so a long shelf doesn't crowd out the changes list
        LazyColumn(modifier = Modifier.heightIn(max = 140.dp).fillMaxWidth()) {
            items(stashes) { entry ->
                StashRow(entry = entry, busy = busy, onPop = { onPop(entry) }, onDrop = { onDrop(entry) })
            }
        }
    }
}

@Composable
private fun StashRow(
    entry: StashEntry,
    busy: Boolean,
    onPop: () -> Unit,
    onDrop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("stash@{${entry.index}}", color = ChangeColors.UNTRACKED)
        Text(entry.message, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onPop, enabled = !busy) { Text("Pop") }
        OutlinedButton(onClick = onDrop, enabled = !busy) { Text("Drop") }
    }
}
