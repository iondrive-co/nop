package iondrive.nop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import iondrive.nop.git.StashEntry
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * The Stash bottom tab: the shelf of stashed working-tree snapshots, each with Pop/Drop actions.
 * Lives in its own tab (rather than inline in the commit panel) so a long shelf doesn't crowd out
 * the change list. Creating a stash still happens from the commit panel's "Stash all" button —
 * this view manages the stashes that already exist.
 */
@Composable
fun StashPanel(
    stashes: List<StashEntry>,
    busy: Boolean,
    onPop: (StashEntry) -> Unit,
    onDrop: (StashEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        if (stashes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No stashes — use \"Stash all\" in the Commit tab to shelve your changes")
            }
            return
        }
        Text("Shelf — ${stashes.size}", modifier = Modifier.padding(bottom = 6.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
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
