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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import iondrive.nop.TextFileRead
import iondrive.nop.TextFileRefusal
import iondrive.nop.TextFiles
import iondrive.nop.diff.DiffComputer
import iondrive.nop.diff.DiffResult
import iondrive.nop.diff.DiffRow
import iondrive.nop.git.CommitFileChange
import iondrive.nop.git.GitRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
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
    onTopLine: (Int) -> Unit = {},
    reloadKey: Int = 0,
    findTrigger: Int = 0,
) {
    var loading by remember(tab.id) { mutableStateOf(true) }
    var refusal by remember(tab.id) { mutableStateOf<TextFileRefusal?>(null) }
    var error by remember(tab.id) { mutableStateOf<String?>(null) }
    var result by remember(tab.id) { mutableStateOf<DiffResult?>(null) }
    var ignoreWhitespace by remember(tab.id) { mutableStateOf(false) }
    // Whether the file is absent from the parent's tree, so this commit is the one that created it.
    var absentBefore by remember(tab.id) { mutableStateOf(false) }

    // reloadKey re-reads both revisions. A commit's content is fixed, so this only matters after
    // the history was rewritten underneath the tab (amend, rebase) — but it also makes "open it
    // again / F5" mean the same thing on every diff surface.
    LaunchedEffect(tab.id, reloadKey, ignoreWhitespace) {
        try {
            val (oldRead, newRead) = withContext(Dispatchers.IO) {
                val parentRev = "${tab.sha}^"
                val old = when (tab.file.changeType) {
                    CommitFileChange.ADDED -> TextFileRead.Missing
                    else -> repo.readTextAt(parentRev, tab.file.path)
                }
                val new = when {
                    old is TextFileRead.Refused -> TextFileRead.Missing
                    tab.file.changeType == CommitFileChange.DELETED -> TextFileRead.Missing
                    else -> repo.readTextAt(tab.sha, tab.file.path)
                }
                old to new
            }
            val refused = (oldRead as? TextFileRead.Refused)?.reason ?: (newRead as? TextFileRead.Refused)?.reason
            if (refused != null) {
                refusal = refused
                result = null
                error = null
                loading = false
                return@LaunchedEffect
            }
            val unreadable = (oldRead as? TextFileRead.Unreadable)?.message ?: (newRead as? TextFileRead.Unreadable)?.message
            if (unreadable != null) {
                refusal = null
                error = unreadable
                loading = false
                return@LaunchedEffect
            }
            absentBefore = oldRead is TextFileRead.Missing && tab.file.changeType != CommitFileChange.DELETED
            val oldText = (oldRead as? TextFileRead.Content)?.text ?: ""
            val newText = (newRead as? TextFileRead.Content)?.text ?: ""
            result = withContext(Dispatchers.Default) { DiffComputer.compute(oldText, newText, ignoreWhitespace) }
            refusal = null
            error = null
            loading = false
        } catch (t: Throwable) {
            refusal = null
            error = t.message ?: t::class.simpleName
            loading = false
        }
    }

    val tokenize = remember(tab.id) { tokenizerForExtension(File(tab.file.path).extension) }
    // A diff opened from a blame line is recorded as a modification whatever the commit did, so the
    // parent's tree has the last word on whether the file is new here.
    val fileName = LocalFileNameBadge.current?.let { it.copy(isNew = it.isNew || absentBefore) }
    CompositionLocalProvider(LocalDiffTokenizer provides tokenize, LocalFileNameBadge provides fileName) {
        when {
            loading -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) { Text("Loading diff…") }
            refusal != null -> DiffRefusalView(
                refusal!!,
                file = File(tab.repoRoot, tab.file.path),
                showFileNameBadge = true,
            )
            error != null -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) { Text("Could not load diff: $error") }
            result != null -> ReadOnlyDiffList(
                result = result!!,
                splitRatio = splitRatio,
                onSplitRatioChange = onSplitRatioChange,
                onTopLine = onTopLine,
                searchKey = tab.id,
                findTrigger = findTrigger,
                oldHeader = "${tab.sha.take(8)}^",
                newHeader = tab.sha.take(8),
                ignoreWhitespace = ignoreWhitespace,
                onToggleWhitespace = { ignoreWhitespace = !ignoreWhitespace },
            )
        }
    }
}

/**
 * A file as one commit left it, against what the working tree holds now — the view behind
 * "Compare with Revision…". The revision is on the left, the file on disk is on the right, in the
 * same read-only side-by-side every other historic diff uses.
 *
 * The right side comes off disk rather than out of the editor's buffer, matching [LocalDiffView]:
 * a comparison is against the saved file, and an unsaved buffer is what the editor itself shows.
 */
@Composable
fun RevisionDiffView(
    repo: GitRepo,
    tab: Tab.RevisionDiff,
    splitRatio: Float = 0.5f,
    onSplitRatioChange: (Float) -> Unit = {},
    onTopLine: (Int) -> Unit = {},
    reloadKey: Int = 0,
    findTrigger: Int = 0,
) {
    var loading by remember(tab.id) { mutableStateOf(true) }
    var refusal by remember(tab.id) { mutableStateOf<TextFileRefusal?>(null) }
    var error by remember(tab.id) { mutableStateOf<String?>(null) }
    var result by remember(tab.id) { mutableStateOf<DiffResult?>(null) }
    var ignoreWhitespace by remember(tab.id) { mutableStateOf(false) }

    // reloadKey re-reads both sides. The left one is fixed by its sha, but the right is the live
    // file, so re-opening this tab (or F5) re-diffs the revision against work done since.
    LaunchedEffect(tab.id, reloadKey, ignoreWhitespace) {
        try {
            val rel = repoRelativePath(repo, tab.file)
            if (rel == null) {
                refusal = null
                error = "That file is outside this repository."
                loading = false
                return@LaunchedEffect
            }
            val (oldRead, newRead) = withContext(Dispatchers.IO) {
                val working = TextFiles.read(tab.file)
                // Absent from that commit's tree means the file didn't exist yet (or the commit is
                // the one that deleted it), so the whole of it reads as added — which is the truth.
                val old = if (working is TextFileRead.Refused) TextFileRead.Missing else repo.readTextAt(tab.sha, rel)
                old to working
            }
            val refused = (newRead as? TextFileRead.Refused)?.reason ?: (oldRead as? TextFileRead.Refused)?.reason
            if (refused != null) {
                refusal = refused
                result = null
                error = null
                loading = false
                return@LaunchedEffect
            }
            val unreadable = (oldRead as? TextFileRead.Unreadable)?.message ?: (newRead as? TextFileRead.Unreadable)?.message
            if (unreadable != null) {
                refusal = null
                error = unreadable
                loading = false
                return@LaunchedEffect
            }
            val oldText = (oldRead as? TextFileRead.Content)?.text ?: ""
            val newText = (newRead as? TextFileRead.Content)?.text ?: ""
            result = withContext(Dispatchers.Default) { DiffComputer.compute(oldText, newText, ignoreWhitespace) }
            refusal = null
            error = null
            loading = false
        } catch (t: Throwable) {
            refusal = null
            error = t.message ?: t::class.simpleName
            loading = false
        }
    }

    val tokenize = remember(tab.id) { tokenizerForExtension(tab.file.extension) }
    CompositionLocalProvider(LocalDiffTokenizer provides tokenize) {
        when {
            loading -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) { Text("Loading diff…") }
            refusal != null -> DiffRefusalView(refusal!!, file = tab.file, showFileNameBadge = true)
            error != null -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                Text("Could not load diff: $error")
            }
            result != null -> ReadOnlyDiffList(
                result = result!!,
                splitRatio = splitRatio,
                onSplitRatioChange = onSplitRatioChange,
                onTopLine = onTopLine,
                searchKey = tab.id,
                findTrigger = findTrigger,
                oldHeader = tab.sha.take(8),
                newHeader = "Working copy",
                ignoreWhitespace = ignoreWhitespace,
                onToggleWhitespace = { ignoreWhitespace = !ignoreWhitespace },
            )
        }
    }
}

/**
 * The read-only side-by-side list every historic diff is drawn with — a commit's revision of a file
 * (above), that revision against the working copy (see [RevisionDiffView]) and a local-history
 * revision of it (see [LocalDiffView]). No side is editable, so the only thing a caller has to
 * supply is the computed diff.
 */
@Composable
internal fun ReadOnlyDiffList(
    result: DiffResult,
    splitRatio: Float,
    onSplitRatioChange: (Float) -> Unit,
    onTopLine: (Int) -> Unit,
    searchKey: Any,
    findTrigger: Int,
    oldHeader: String? = null,
    newHeader: String? = null,
    ignoreWhitespace: Boolean = false,
    onToggleWhitespace: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()

    // Report the commit-side line at the top of the viewport so a "jump to source" (F4) lands the
    // working file on what's on screen. Same contract as the working-tree diff — see DiffRowsList.
    val rowsForTopLine by rememberUpdatedState(result.rows)
    LaunchedEffect(listState, onTopLine) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { idx -> onTopLine(newSideLineAt(rowsForTopLine, idx)) }
    }
    WrapWithinLimits(result.rows) {
        DiffListScaffold(
            rows = result.rows,
            kinds = result.rows.map { it.kind },
            listState = listState,
            ratio = splitRatio,
            onRatioChange = onSplitRatioChange,
            searchKey = searchKey,
            findTrigger = findTrigger,
            oldHeader = oldHeader,
            newHeader = newHeader,
            ignoreWhitespace = ignoreWhitespace,
            onToggleWhitespace = onToggleWhitespace,
        ) { listModifier ->
            // One SelectionContainer over the whole list so a drag spans rows — the user can select a
            // multi-line deleted block on the old (left) side and copy it. Gutters and the right column
            // opt out via DisableSelection (see ReadOnlyDiffHalf) so the copy is clean left-side text.
            SelectionContainer {
                LazyColumn(state = listState, modifier = listModifier) {
                    itemsIndexed(result.rows, key = { index, row ->
                        when {
                            row.newLineNumber != null -> "n${row.newLineNumber}"
                            row.oldLineNumber != null -> "o${row.oldLineNumber}"
                            else -> "x$index"
                        }
                    }) { index, row ->
                        ReadOnlyDiffRowView(row, index)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyDiffRowView(row: DiffRow, rowIndex: Int) {
    val diffColors = currentDiffColors()
    val (oldBg, newBg) = backgroundsFor(row, diffColors)
    // One line is one row's height — unless wrapping is on, in which case the row is as tall as
    // whichever half needed the most lines, and the tints behind both are painted by the row.
    val wrap = LocalWrapLines.current
    Row(
        modifier = Modifier.fillMaxWidth().then(
            if (wrap) Modifier.wrappedRowChrome(oldBg, newBg) else Modifier.height(rememberDiffLineHeight()),
        ),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        ReadOnlyDiffHalf(
            side = DiffSide.OLD,
            text = row.oldLine,
            spans = row.oldSpans,
            lineNumber = row.oldLineNumber,
            background = oldBg,
            inlineHighlight = diffColors.inlineWordBgOld,
            rowIndex = rowIndex,
            modifier = diffHalf(DiffSide.OLD),
        )
        DiffDivider()
        ReadOnlyDiffHalf(
            side = DiffSide.NEW,
            text = row.newLine,
            spans = row.newSpans,
            lineNumber = row.newLineNumber,
            background = newBg,
            inlineHighlight = diffColors.inlineWordBg,
            rowIndex = rowIndex,
            modifier = diffHalf(DiffSide.NEW),
            selectable = false,
        )
    }
}
