package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import java.nio.file.Path
import org.jetbrains.jewel.foundation.theme.JewelTheme
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

    LaunchedEffect(focusTrigger) {
        if (focusTrigger > 0) focusRequester.requestFocus()
    }

    // Debounce typing so a typist doesn't kick off a full project scan on every keystroke; cancel
    // the prior coroutine on each new query via collectLatest so stale results never win the race.
    LaunchedEffect(files, projectRoot) {
        snapshotFlow { state.text.toString() }
            .debounce(150)
            .distinctUntilChanged()
            .collectLatest { query ->
                if (query.isEmpty()) {
                    results = emptyList()
                    truncated = false
                    searching = false
                    return@collectLatest
                }
                searching = true
                val hits = SearchEngine.search(projectRoot, files, query)
                results = hits
                truncated = hits.size >= SearchEngine.MAX_TOTAL_HITS
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
                searching -> StatusText("Searching…")
                results.isEmpty() -> StatusText("No matches")
                else -> ResultsList(
                    results = results,
                    query = query,
                    truncated = truncated,
                    onPick = onPick,
                )
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    val muted =if (JewelTheme.isDark) Color(0xFF6F737A) else Color(0xFF7A7E87)
    Text(text, color = muted)
}

/** Row chrome around a hit's text: the row padding plus the column's list scrollbar. */
private val HIT_ROW_CHROME = 28.dp

@Composable
private fun ResultsList(
    results: List<SearchHit>,
    query: String,
    truncated: Boolean,
    onPick: (path: String, line: Int) -> Unit,
) {
    val groups = remember(results) { PathGrouping.group(results) { it.path } }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Widest row text across the columns, so the grid can size them to fit the matched code. Both
    // row lines are monospaced, so the longest string by character count is also the widest — one
    // measure per line style per column instead of one per hit.
    val naturalTextPx = remember(groups) {
        var widest = 0
        for (group in groups) {
            val label = group.paths.maxByOrNull { it.length }?.let { "${group.labelFor(it)}:0000" }
            val code = group.items.maxByOrNull { it.lineText.length }?.lineText
            if (label != null) widest = maxOf(widest, measurer.measure(label, LOCATION_STYLE).size.width)
            if (code != null) widest = maxOf(widest, measurer.measure(code, CODE_STYLE).size.width)
        }
        widest
    }
    Column(modifier = Modifier.fillMaxSize()) {
        GroupColumnGrid(
            columns = groups.map { group ->
                GroupColumn(key = group.title, header = group.header) {
                    HitRows(group = group, onPick = onPick)
                }
            },
            naturalColumnWidth = with(density) { naturalTextPx.toDp() } + HIT_ROW_CHROME,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        if (truncated) {
            val muted = if (JewelTheme.isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
            Text(
                "(showing first ${SearchEngine.MAX_TOTAL_HITS} matches for \"$query\")",
                color = muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The hit rows filling one column of the grid, under the heading [GroupColumnGrid] draws. */
@Composable
private fun HitRows(group: PathGroup<SearchHit>, onPick: (path: String, line: Int) -> Unit) {
    val listState = rememberLazyListState()
    ScrollableColumn(listState = listState, modifier = Modifier.fillMaxSize()) {
        items(group.items) { hit ->
            HitRow(
                hit = hit,
                label = group.labelFor(hit.path),
                onClick = { onPick(hit.path, hit.line) },
            )
        }
    }
}

private val LOCATION_STYLE = TextStyle(fontFamily = NopFonts.Mono, fontSize = 11.sp)
private val CODE_STYLE = TextStyle(fontFamily = NopFonts.Mono, fontSize = 13.sp)

@Composable
private fun HitRow(hit: SearchHit, label: String, onClick: () -> Unit) {
    val muted = if (JewelTheme.isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
    val highlight = if (JewelTheme.isDark) Color(0x66629755) else Color(0x66629755)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text("$label:${hit.line}", color = muted, style = LOCATION_STYLE)
        Text(
            annotateLine(hit.lineText, hit.matchStart, hit.matchEnd, highlight),
            style = CODE_STYLE,
            softWrap = false,
        )
    }
}

private fun annotateLine(
    text: String,
    matchStart: Int,
    matchEnd: Int,
    highlight: Color,
): AnnotatedString {
    val start = matchStart.coerceIn(0, text.length)
    val end = matchEnd.coerceIn(start, text.length)
    if (end == start) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, start))
        withStyle(SpanStyle(background = highlight)) {
            append(text.substring(start, end))
        }
        append(text.substring(end))
    }
}
