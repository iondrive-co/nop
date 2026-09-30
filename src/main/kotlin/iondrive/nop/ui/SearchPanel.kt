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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.index.SearchEngine
import iondrive.nop.index.SearchHit
import iondrive.nop.index.SearchProgress
import iondrive.nop.index.SearchScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.nio.file.Path
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * Bottom-tab "Find in files" panel. Bumping [focusTrigger] grabs focus on the query input so
 * Ctrl+Shift+F can type-and-go without an extra click.
 *
 * The scan runs on every query change but uses `collectLatest`, so a fast typist's stale
 * queries are cancelled before they finish. Results are static once produced — clicking a row
 * jumps via [onPick] to the file and line.
 *
 * It reads [SearchScope]'s cut of [files] — what git lists, less Git LFS — not the whole index.
 * A search that runs long says how far it has got and can be stopped, and a stopped search keeps
 * the matches it had found.
 *
 * A change to [files] does not restart a search. In a project written to all day every write
 * rebuilds the index, and restarting on each rebuild would throw away the scan in progress again
 * and again, so a search would never finish. The one change that does re-run the query is the index
 * arriving at all, since a search of an empty list finds nothing for want of files, not matches.
 *
 * Hits are grouped by [PathGrouping] and shown in the same side-by-side columns the commit panel
 * groups changes into — source directories first, then tests, config and docs — so matches in tests
 * or config don't interleave with the ones in the implementation.
 */
@OptIn(FlowPreview::class)
@Composable
fun SearchPanel(
    projectRoot: Path,
    files: List<String>,
    onPick: (path: String, line: Int) -> Unit,
    focusTrigger: Int = 0,
    state: TextFieldState = rememberTextFieldState(),
) {
    val focusRequester = remember { FocusRequester() }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }
    // How far the running search has got, of how many files, shown while it runs.
    var scanned by remember { mutableStateOf(0) }
    var scopeSize by remember { mutableStateOf(0) }
    // The running search, for the Stop button, and — once one is stopped — how far it had got.
    var running by remember { mutableStateOf<Job?>(null) }
    var stoppedAt by remember { mutableStateOf<Int?>(null) }
    val currentFiles by rememberUpdatedState(files)
    val scope = remember(projectRoot) { ScopeCache(projectRoot) }

    LaunchedEffect(focusTrigger) {
        if (focusTrigger > 0) focusRequester.requestFocus()
    }

    // Debounce typing so a typist doesn't kick off a full project scan on every keystroke; cancel
    // the prior coroutine on each new query via collectLatest so stale results never win the race.
    LaunchedEffect(projectRoot, files.isEmpty()) {
        snapshotFlow { state.text.toString() }
            .debounce(150)
            .distinctUntilChanged()
            .collectLatest { query ->
                stoppedAt = null
                if (query.isEmpty()) {
                    results = emptyList()
                    truncated = false
                    searching = false
                    return@collectLatest
                }
                searching = true
                scanned = 0
                scopeSize = 0
                val searched = scope.narrow(currentFiles)
                scopeSize = searched.size
                val progress = SearchProgress()
                coroutineScope {
                    val job = launch {
                        val hits = SearchEngine.search(projectRoot, searched, query, progress = progress)
                        results = hits
                        truncated = hits.size >= SearchEngine.MAX_TOTAL_HITS
                    }
                    running = job
                    val ticker = launch {
                        while (true) {
                            scanned = progress.scanned.get()
                            delay(250)
                        }
                    }
                    job.join()
                    ticker.cancel()
                    // Only Stop cancels the search on its own; a new query cancels this whole block.
                    if (job.isCancelled) {
                        val found = progress.hitsSoFar()
                        results = found
                        truncated = found.size >= SearchEngine.MAX_TOTAL_HITS
                        stoppedAt = progress.scanned.get()
                    }
                }
                running = null
                searching = false
            }
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        TextField(
            state = state,
            placeholder = { Text("Find in files") },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )
        Box(Modifier.padding(top = 6.dp).fillMaxSize()) {
            val query = state.text.toString()
            when {
                query.isEmpty() -> StatusText("Type to search across project files")
                searching -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusText(
                        if (scopeSize > 0) "Searching… ${count(scanned)} of ${count(scopeSize)} files" else "Searching…",
                    )
                    OutlinedButton(onClick = { running?.cancel() }) { Text("Stop") }
                }
                results.isEmpty() && stoppedAt != null ->
                    StatusText("Stopped after ${count(stoppedAt ?: 0)} of ${count(scopeSize)} files, with no matches so far")
                results.isEmpty() -> StatusText("No matches")
                else -> HitResultsGrid(
                    results = results,
                    onPick = onPick,
                    footer = when {
                        stoppedAt != null ->
                            "(stopped after ${count(stoppedAt ?: 0)} of ${count(scopeSize)} files: these are the matches found so far)"
                        truncated -> "(showing first ${SearchEngine.MAX_TOTAL_HITS} matches for \"$query\")"
                        else -> null
                    },
                )
            }
        }
    }
}

private fun count(n: Int): String = "%,d".format(n)

/**
 * [SearchScope.narrow] of the file list the panel was last given, worked out again only when that
 * list changes. It costs a git run — well under a second even for a large repository — which is nothing beside
 * the search it saves, and too much to pay again on every keystroke.
 *
 * Only ever called from the panel's one search coroutine, one query at a time, so it needs no lock.
 */
private class ScopeCache(private val root: Path) {
    private var basis: List<String>? = null
    private var scope: List<String> = emptyList()

    suspend fun narrow(files: List<String>): List<String> {
        if (files !== basis) {
            scope = runInterruptible(Dispatchers.IO) { SearchScope.narrow(root, files) }
            basis = files
        }
        return scope
    }
}
