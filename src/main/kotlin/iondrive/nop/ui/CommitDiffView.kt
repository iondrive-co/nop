package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import iondrive.nop.diff.DiffComputer
import iondrive.nop.diff.DiffResult
import iondrive.nop.diff.DiffRow
import iondrive.nop.git.CommitFileChange
import iondrive.nop.git.GitRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Text
import java.io.File

/**
 * Read-only side-by-side diff of one file at one commit (commit^ vs commit), opened from the
 * history view. Rendering is shared with the working-tree [DiffView] via [DiffRendering] — the
 * difference here is that both sides are read-only (no editing, conflict resolution or revert).
 */
@Composable
fun CommitDiffView(
    repo: GitRepo,
    tab: Tab.CommitDiff,
    splitRatio: Float = 0.5f,
    onSplitRatioChange: (Float) -> Unit = {},
) {
    var loading by remember(tab.id) { mutableStateOf(true) }
    var error by remember(tab.id) { mutableStateOf<String?>(null) }
    var result by remember(tab.id) { mutableStateOf<DiffResult?>(null) }

    LaunchedEffect(tab.id) {
        try {
            val (oldText, newText) = withContext(Dispatchers.IO) {
                val parentRev = "${tab.sha}^"
                val old = when (tab.file.changeType) {
                    CommitFileChange.ADDED -> ""
                    else -> repo.readContentAt(parentRev, tab.file.path) ?: ""
                }
                val new = when (tab.file.changeType) {
                    CommitFileChange.DELETED -> ""
                    else -> repo.readContentAt(tab.sha, tab.file.path) ?: ""
                }
                old to new
            }
            result = withContext(Dispatchers.Default) { DiffComputer.compute(oldText, newText) }
            loading = false
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName
            loading = false
        }
    }

    val tokenize = remember(tab.id) { tokenizerForExtension(File(tab.file.path).extension) }
    CompositionLocalProvider(LocalDiffTokenizer provides tokenize) {
        when {
            loading -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) { Text("Loading diff…") }
            error != null -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) { Text("Could not load diff: $error") }
            result != null -> ReadOnlyDiffList(result!!, splitRatio, onSplitRatioChange)
        }
    }
}

@Composable
private fun ReadOnlyDiffList(result: DiffResult, splitRatio: Float, onSplitRatioChange: (Float) -> Unit) {
    val listState = rememberLazyListState()
    DiffListScaffold(
        rows = result.rows,
        kinds = result.rows.map { it.kind },
        listState = listState,
        ratio = splitRatio,
        onRatioChange = onSplitRatioChange,
    ) { listModifier ->
        // One SelectionContainer over the whole list so a drag spans rows — the user can select a
        // multi-line deleted block on the old (left) side and copy it. Gutters and the right column
        // opt out via DisableSelection (see ReadOnlyDiffHalf) so the copy is clean left-side text.
        SelectionContainer {
            LazyColumn(state = listState, modifier = listModifier) {
                items(result.rows, key = { row ->
                    when {
                        row.newLineNumber != null -> "n${row.newLineNumber}"
                        row.oldLineNumber != null -> "o${row.oldLineNumber}"
                        else -> "x${result.rows.indexOf(row)}"
                    }
                }) { row ->
                    ReadOnlyDiffRowView(row)
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyDiffRowView(row: DiffRow) {
    val (oldBg, newBg) = backgroundsFor(row)
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicMinHeightLine),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        ReadOnlyDiffHalf(
            side = DiffSide.OLD,
            text = row.oldLine,
            spans = row.oldSpans,
            lineNumber = row.oldLineNumber,
            background = oldBg,
            inlineHighlight = INLINE_WORD_BG_OLD,
            modifier = diffHalf(DiffSide.OLD),
        )
        DiffDivider()
        ReadOnlyDiffHalf(
            side = DiffSide.NEW,
            text = row.newLine,
            spans = row.newSpans,
            lineNumber = row.newLineNumber,
            background = newBg,
            inlineHighlight = INLINE_WORD_BG,
            modifier = diffHalf(DiffSide.NEW),
            selectable = false,
        )
    }
}
