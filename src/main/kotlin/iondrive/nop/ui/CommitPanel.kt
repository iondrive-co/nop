package iondrive.nop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import iondrive.nop.git.FileChange
import iondrive.nop.git.GitStatus
import iondrive.nop.git.StashEntry
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextArea

@OptIn(ExperimentalFoundationApi::class)
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
    stashInFlight: Boolean = false,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    val messageState = remember { TextFieldState() }
    val inRepo = status.branch != null
    val anyChanges = !status.isClean

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
            OutlinedButton(onClick = onRefresh, enabled = !refreshing) {
                Text(if (refreshing) "Refreshing…" else "Refresh")
            }
        }

        if (inRepo && anyChanges) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextArea(
                    state = messageState,
                    placeholder = { Text("Commit message / stash description") },
                    modifier = Modifier.weight(1f).height(64.dp),
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
