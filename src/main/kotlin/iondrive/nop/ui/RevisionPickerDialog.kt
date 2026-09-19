package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.git.CommitInfo
import iondrive.nop.git.GitRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import java.io.File
import java.time.Instant

/**
 * "Compare with revision" picker: the commits that touched one file, newest first, filtered as the
 * user types. Enter (or a click) picks one, which the caller opens as a [Tab.RevisionDiff] — that
 * revision of the file against what's on disk now.
 *
 * Same shape as [FileSearchDialog] — a window of its own, Up/Down to move, Esc to dismiss — because
 * it answers the same kind of question: pick one thing out of a list nop already knows. A window and
 * not a popup over nop's for the same reason too: a terminal in the tool region is drawn over any
 * popup. See [DialogFrame].
 */
@Composable
fun RevisionPickerDialog(
    repo: GitRepo,
    file: File,
    onPick: (CommitInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTextFieldState()
    val focusRequester = remember { FocusRequester() }
    var selectedIndex by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    var loading by remember(file) { mutableStateOf(true) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var commits by remember(file) { mutableStateOf<List<CommitInfo>>(emptyList()) }

    LaunchedEffect(file) {
        try {
            val rel = repoRelativePath(repo, file)
            commits = withContext(Dispatchers.IO) { repo.history(rel) }
            loading = false
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName
            loading = false
        }
    }

    val query by remember { derivedStateOf { state.text.toString() } }
    val results by remember(commits) { derivedStateOf { RevisionSearch.filter(query, commits) } }

    LaunchedEffect(results) {
        if (selectedIndex >= results.size) selectedIndex = 0
    }

    LaunchedEffect(selectedIndex, results) {
        if (results.isNotEmpty()) {
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == selectedIndex }
            if (!visible) listState.animateScrollToItem(selectedIndex.coerceAtLeast(0))
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val title = "Compare ${file.name} with revision"
    DialogFrame(title = title, onClose = onDismiss, size = DpSize(620.dp, 500.dp)) {
        val muted = if (JewelTheme.isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
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
            Text(title, fontWeight = FontWeight.SemiBold)
            TextField(
                state = state,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            when {
                loading -> Text("Loading log…", color = muted)
                error != null -> Text("Could not load history: $error", color = muted)
                commits.isEmpty() -> Text("No commits touch this file.", color = muted)
                results.isEmpty() -> Text("No matches", color = muted)
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    itemsIndexed(results, key = { _, c -> c.sha }) { idx, c ->
                        RevisionRow(
                            commit = c,
                            selected = idx == selectedIndex,
                            onClick = { onPick(c) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RevisionRow(commit: CommitInfo, selected: Boolean, onClick: () -> Unit) {
    val highlight = if (JewelTheme.isDark) Color(0xFF2E436E) else Color(0xFFCFE3F9)
    val date = COMMIT_DATE_FMT.format(Instant.ofEpochSecond(commit.whenEpochSeconds))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) highlight else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(commit.shortSha, color = SHA_FG, fontFamily = NopFonts.Mono, fontSize = 12.sp)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(commit.shortMessage, fontSize = 13.sp)
            Text("$date · ${commit.author}", color = META_FG, fontSize = 11.sp)
        }
    }
}

/**
 * How the picker narrows a file's log as the user types. Every whitespace-separated term has to
 * appear somewhere in the commit — sha, message or author — so "fix parser" finds the parser fix
 * whichever order the words came in, and a pasted sha finds exactly one commit.
 */
object RevisionSearch {
    fun filter(query: String, commits: List<CommitInfo>): List<CommitInfo> {
        val terms = query.trim().split(' ', '\t').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return commits
        return commits.filter { c ->
            val haystack = "${c.sha} ${c.shortMessage} ${c.author}"
            terms.all { haystack.contains(it, ignoreCase = true) }
        }
    }
}
