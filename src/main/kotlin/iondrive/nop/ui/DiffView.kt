package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.diff.ConflictParser
import iondrive.nop.diff.DiffComputer
import iondrive.nop.diff.DiffResult
import iondrive.nop.diff.DiffRow
import iondrive.nop.diff.RowKind
import iondrive.nop.git.ChangeKind
import iondrive.nop.git.GitRepo
import iondrive.nop.index.JumpTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import java.io.File
import org.jetbrains.jewel.ui.component.Text

// Diff colours, the read-only half, gutter, change-marker lane and text selection rules live in
// DiffRendering.kt and are shared with the history diff (CommitDiffView). Only the bits unique to
// the editable working-tree diff — conflict/hunk action affordances — are declared here.
private val CONFLICT_MARK = ChangeColors.CONFLICT

// Hunk/conflict action affordances. The chip sits over the centre divider, IntelliJ-style.
private val CHIP_BG = Color(0x33FFFFFF)
private val CONFLICT_STRIP_BG = Color(0x22CC7832)
private val CONFLICT_CHIP_BG = Color(0x44CC7832)
private val CHIP_SHAPE = RoundedCornerShape(3.dp)

/** How long to wait for typing to settle before re-running the diff and saving the buffer. */
private const val DIFF_DEBOUNCE_MS = 400L

/**
 * One rendered diff, in one of two modes. An [Ordinary] diff is HEAD (left, read-only) vs the
 * working tree (right, editable). When the working buffer carries git conflict markers we switch
 * to [Merge]: the two conflicting versions side by side (ours left, theirs right), resolved a
 * region at a time via the control strips.
 */
private sealed interface DiffContent {
    data class Ordinary(val result: DiffResult) : DiffContent
    data class Merge(val rows: List<MergeRow>) : DiffContent
}

/** A row in a [DiffContent.Merge] render: either a diff line or the action strip above a conflict. */
private sealed interface MergeRow {
    /** [regionId] is the 0-based conflict index when this line sits inside a conflict block, else null. */
    data class Line(val row: DiffRow, val regionId: Int?) : MergeRow
    data class Control(val regionId: Int) : MergeRow
}

@OptIn(FlowPreview::class)
@Composable
fun DiffView(
    repo: GitRepo,
    tab: Tab.Diff,
    editStore: FileEditStore,
    onFileSaved: () -> Unit = {},
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget? = { _, _, _ -> null },
    onJump: (File, Int) -> Unit = { _, _ -> },
    onTopLine: (Int) -> Unit = {},
    splitRatio: Float = 0.5f,
    onSplitRatioChange: (Float) -> Unit = {},
    reloadKey: Int = 0,
    findTrigger: Int = 0,
) {
    val workingFile = remember(tab.id) { File(tab.repoRoot, tab.change.path) }
    // Resolve a per-file FileEdit via FileEditStore — same instance an open Tab.FileView would
    // use, so edits in either view share the buffer and autosave coordinates through one place.
    // For untracked/added files this is still fine; for removed/missing files the working file
    // doesn't exist and the buffer starts empty. Re-keyed on reloadKey so a file that has since
    // appeared on disk (created by a checkout, a pull, an agent) gets picked up on a reload.
    val edit = remember(tab.id, reloadKey) {
        if (workingFile.isFile) editStore.edit(Tab.FileView(workingFile)) else null
    }

    var loading by remember(tab.id) { mutableStateOf(true) }
    var error by remember(tab.id) { mutableStateOf<String?>(null) }
    var content by remember(tab.id) { mutableStateOf<DiffContent?>(null) }
    var headText by remember(tab.id) { mutableStateOf("") }
    // Bumped whenever the working buffer is rewritten by something *other* than the diff's own
    // editable blocks: a (re)load, a hunk revert, a line merged across a block boundary, an external
    // change adopted off disk. The blocks re-read their lines from the buffer when this moves, and
    // only then — re-reading on every re-diff would fight the user, since a diff computed a debounce
    // ago describes the buffer as it was, not as it is.
    var bufferReset by remember(tab.id) { mutableStateOf(0) }

    val savedCallback by rememberUpdatedState(onFileSaved)

    // Loads both sides from scratch. Re-runs on reloadKey — bumped when the user opens this diff
    // again or hits F5 — because HEAD is otherwise read once and never again, so a commit, branch
    // switch or pull would leave the left side frozen on whatever it said when the tab opened.
    // `loading` is deliberately keyed on tab.id alone: a reload updates in place rather than
    // flashing the placeholder and dropping the user's scroll position.
    LaunchedEffect(tab.id, reloadKey) {
        try {
            val head = withContext(Dispatchers.IO) {
                when (tab.change.kind) {
                    ChangeKind.UNTRACKED, ChangeKind.ADDED -> ""
                    else -> repo.readHeadContent(tab.change.path) ?: ""
                }
            }
            headText = head
            // The working side renders the shared edit buffer, which is cached for the whole session
            // and reused across tab close/reopen. If the file changed on disk since the buffer was last
            // loaded (external editor, branch switch, agent), reload it so reopening the diff doesn't
            // show a stale snapshot. A modified buffer is left alone — unsaved in-app edits win.
            if (edit != null) {
                val diskText = withContext(Dispatchers.IO) { edit.diskTextIfDivergedAndClean() }
                if (diskText != null && !edit.hasUserEdit) edit.adoptDiskText(diskText)
            }
            val workingNow = when (tab.change.kind) {
                ChangeKind.REMOVED, ChangeKind.MISSING -> ""
                else -> edit?.state?.text?.toString() ?: ""
            }
            content = withContext(Dispatchers.Default) { computeContent(head, workingNow) }
            bufferReset++
            error = null
            loading = false
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName
            loading = false
        }
    }

    // Live re-diff: as the user edits a row (or resolves a conflict), recompute on a debounce so the
    // kind tints, inline highlights and — for conflicts — the remaining regions track the buffer.
    // Skip the initial value (drop(1)) so opening a diff doesn't immediately rebuild on the seed.
    if (edit != null) {
        LaunchedEffect(edit, headText) {
            snapshotFlow { edit.state.text.toString() }
                .drop(1)
                .debounce(DIFF_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { text ->
                    content = withContext(Dispatchers.Default) { computeContent(headText, text) }
                }
        }

        // Autosave mirrors FileEditView's pattern so an open Tab.FileView isn't required for the
        // user's edits to persist. Lives on a longer debounce than the diff so the disk and the
        // visualization update together.
        LaunchedEffect(edit) {
            snapshotFlow { edit.state.text.toString() }
                .drop(1)
                .debounce(DIFF_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { text ->
                    // Only persist buffer changes the user actually made (hasUserEdit). The diff
                    // view rewrites the shared buffer programmatically — re-seeding per-line cells
                    // after a re-diff, adopting an externally-changed file — and none of that must
                    // reach disk, or nop would revert a checkout/pull/merge it didn't make. save()
                    // is also a compare-and-swap as a second line of defence; refresh git status
                    // only on a real write.
                    if (edit.hasUserEdit && text != edit.savedText) {
                        if (withContext(Dispatchers.IO) { edit.save() } is SaveResult.Saved) savedCallback()
                    }
                }
        }

        // A write to the buffer from outside the diff — the reconcile adopting a file an agent or a
        // checkout changed — advances the saved baseline, and so does a save of our own. Treat both
        // as a reset: making the blocks re-read a buffer they already agree with costs nothing, and
        // missing the adopt would freeze them on text the file no longer holds.
        LaunchedEffect(edit) {
            snapshotFlow { edit.savedText }.drop(1).collect { bufferReset++ }
        }
    }

    // Resolve conflict [regionId] by copying ours / theirs / both into the working buffer. We
    // re-parse the live buffer rather than trusting a snapshot so the index always lines up with
    // what's on screen; the re-diff + autosave effects above pick the write up from there.
    val resolveConflict: ((Int, ConflictParser.Choice) -> Unit)? = edit?.let {
        { regionId, choice ->
            val current = it.state.text.toString()
            val next = ConflictParser.resolve(ConflictParser.parse(current), regionId, choice)
            if (next != current) {
                it.markUserEdit()
                it.state.edit { replace(0, length, next) }
            }
        }
    }

    val tokenize = remember(tab.id) { tokenizerForExtension(workingFile.extension) }
    CompositionLocalProvider(LocalDiffTokenizer provides tokenize) {
    when {
        loading -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
            Text("Loading diff…")
        }
        error != null -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
            Text("Could not load diff: $error")
        }
        content is DiffContent.Merge -> MergeRowsList(
            rows = (content as DiffContent.Merge).rows,
            currentFile = workingFile,
            onResolve = resolveConflict,
            onResolveAt = onResolveAt,
            onJump = onJump,
            splitRatio = splitRatio,
            onSplitRatioChange = onSplitRatioChange,
            searchKey = tab.id,
            findTrigger = findTrigger,
        )
        content is DiffContent.Ordinary -> {
            val result = (content as DiffContent.Ordinary).result
            // Reverting a hunk copies HEAD's (left) version of those lines over the working (right)
            // side. Reconstruct from the displayed rows and write the whole buffer back.
            val onRevertHunk: ((IntRange) -> Unit)? = edit?.let {
                { hunk ->
                    val current = it.state.text.toString()
                    val next = revertHunk(result.rows, hunk, current.endsWith("\n"))
                    if (next != current) {
                        it.markUserEdit()
                        it.state.edit { replace(0, length, next) }
                        content = computeContent(headText, next)
                        bufferReset++
                    }
                }
            }
            // Merging two lines across a block boundary — Backspace at the very top of a block,
            // Delete at the very bottom — is the one line-structure edit a block's own text field
            // can't make, because the line it has to join sits in a different field. We rewrite the
            // whole buffer and recompute the diff synchronously so the merged row exists this frame
            // for the caret to land on. Returns that landing spot as (1-based line, char column).
            val onStructuralEdit: ((Int, StructuralEdit) -> Pair<Int, Int>?)? = edit?.let {
                { line, op ->
                    val current = it.state.text.toString()
                    val res = applyStructuralEdit(current, line, op)
                    if (res != null && res.first != current) {
                        it.markUserEdit()
                        it.state.edit { replace(0, length, res.first) }
                        content = computeContent(headText, res.first)
                        bufferReset++
                        res.second
                    } else null
                }
            }
            // A block that gains or loses lines renumbers every block below it, and those line
            // numbers are what each block writes back through — so this one can't wait out the
            // debounce. Ordinary typing leaves the line count alone and still takes the debounced
            // path; only Enter, a multi-line paste and the like pay for a synchronous re-diff.
            val onLineCountChanged: (() -> Unit)? = edit?.let {
                { content = computeContent(headText, it.state.text.toString()) }
            }
            DiffRowsList(
                result = result,
                edit = edit,
                bufferReset = bufferReset,
                currentFile = workingFile,
                onRevertHunk = onRevertHunk,
                onStructuralEdit = onStructuralEdit,
                onLineCountChanged = onLineCountChanged,
                onResolveAt = onResolveAt,
                onJump = onJump,
                onTopLine = onTopLine,
                splitRatio = splitRatio,
                onSplitRatioChange = onSplitRatioChange,
                searchKey = tab.id,
                findTrigger = findTrigger,
            )
        }
    }
    }
}

/**
 * Builds the render model for a diff. When the working buffer carries conflict markers we surface
 * the two sides for resolution; otherwise it's the ordinary HEAD-vs-working line diff.
 */
private fun computeContent(head: String, working: String): DiffContent {
    if (ConflictParser.hasConflicts(working)) {
        return DiffContent.Merge(buildMergeRows(working))
    }
    return DiffContent.Ordinary(DiffComputer.compute(head, working))
}

/**
 * Turns a conflict-marked buffer into merge rows: stable text becomes EQUAL lines, and each
 * conflict block becomes a [MergeRow.Control] strip followed by an ours-vs-theirs diff of that
 * block (reusing [DiffComputer] for the inline word highlights). Line numbers run continuously
 * down each side as if ours/theirs were whole files.
 */
private fun buildMergeRows(working: String): List<MergeRow> {
    val segments = ConflictParser.parse(working)
    val rows = ArrayList<MergeRow>()
    var oursNo = 1
    var theirsNo = 1
    var regionId = 0
    for (seg in segments) {
        when (seg) {
            is ConflictParser.MergeSegment.Stable -> {
                for (line in displayLines(seg.text)) {
                    rows.add(
                        MergeRow.Line(
                            DiffRow(RowKind.EQUAL, line, line, emptyList(), emptyList(), oursNo, theirsNo),
                            regionId = null,
                        ),
                    )
                    oursNo++
                    theirsNo++
                }
            }
            is ConflictParser.MergeSegment.Conflict -> {
                rows.add(MergeRow.Control(regionId))
                val block = DiffComputer.compute(seg.ours, seg.theirs)
                for (r in block.rows) {
                    rows.add(
                        MergeRow.Line(
                            r.copy(
                                oldLineNumber = r.oldLineNumber?.plus(oursNo - 1),
                                newLineNumber = r.newLineNumber?.plus(theirsNo - 1),
                            ),
                            regionId = regionId,
                        ),
                    )
                }
                oursNo += block.rows.count { it.oldLineNumber != null }
                theirsNo += block.rows.count { it.newLineNumber != null }
                regionId++
            }
        }
    }
    return rows
}

/** Splits stable text into display lines the way [DiffComputer] does: drop the trailing newline's empty. */
private fun displayLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val parts = text.split("\n")
    val trimmed = if (text.endsWith("\n") && parts.lastOrNull() == "") parts.dropLast(1) else parts
    return trimmed.map { it.removeSuffix("\r") }
}

@Composable
private fun DiffRowsList(
    result: DiffResult,
    edit: FileEdit?,
    bufferReset: Int,
    currentFile: File,
    onRevertHunk: ((IntRange) -> Unit)?,
    onStructuralEdit: ((Int, StructuralEdit) -> Pair<Int, Int>?)?,
    onLineCountChanged: (() -> Unit)?,
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget?,
    onJump: (File, Int) -> Unit,
    onTopLine: (Int) -> Unit = {},
    splitRatio: Float,
    onSplitRatioChange: (Float) -> Unit,
    searchKey: Any,
    findTrigger: Int,
) {
    val listState = rememberLazyListState()
    val lineHeightPx = rememberDiffLineHeightPx()
    val density = LocalDensity.current

    // Rows are rendered a run at a time rather than one at a time — see [diffBlocks]. That grouping
    // is what makes the working side editable as *text* rather than as a column of isolated lines:
    // a block is one multi-line text field, so selecting, dragging, cutting and pasting across the
    // lines inside it are the field's own behaviour and cost us nothing.
    val editable = edit != null
    // The working line the block holding the caret starts on — see [diffBlocks]' splitAtLine.
    var caretBlockStart by remember { mutableStateOf<Int?>(null) }
    val blocks = remember(result, editable, caretBlockStart) {
        diffBlocks(result.rows, editable, splitAtLine = caretBlockStart)
    }
    val blockRows = remember(result, blocks) {
        blocks.map { result.rows.subList(it.range.first, it.range.last + 1) }
    }
    val itemOfRow = remember(result, blocks) {
        IntArray(result.rows.size).also { arr ->
            blocks.forEachIndexed { item, b -> for (r in b.range) arr[r] = item }
        }
    }

    // Report the working-file line at the top of the viewport so a "jump to source" (F4) lands the
    // file on what's on screen. A list item can be hundreds of rows tall, so the scroll offset
    // inside it decides the line as much as which item is first. rememberUpdatedState keeps these
    // current without restarting the flow on every re-diff.
    val rowsForTopLine by rememberUpdatedState(result.rows)
    val blocksForTopLine by rememberUpdatedState(blocks)
    LaunchedEffect(listState, onTopLine, lineHeightPx) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (item, offset) ->
                val block = blocksForTopLine.getOrNull(item)
                val row = if (block == null) item else {
                    (block.range.first + (offset / lineHeightPx).toInt()).coerceAtMost(block.range.last)
                }
                onTopLine(newSideLineAt(rowsForTopLine, row))
            }
    }

    // Per-block editable state, keyed by the 1-based working-file line the block starts on. Hoisted
    // here so scrolling (which disposes off-screen LazyColumn slots) doesn't lose focus, undo
    // history or in-flight edits.
    val blockStates = remember { mutableStateMapOf<Int, TextFieldState>() }
    // Blocks are named by the line they start on, so inserting a line renames every block below it.
    // Drop what the old names left behind — otherwise a long editing session parks a buffer's worth
    // of abandoned fields (text and undo history each) in here.
    LaunchedEffect(blocks) {
        if (blockStates.isEmpty()) return@LaunchedEffect
        val live = blocks.mapNotNullTo(HashSet()) { result.rows[it.range.first].newLineNumber }
        blockStates.keys.retainAll(live)
    }
    // Where to put the caret next: a (1-based working line, column) the block owning that line
    // claims, focuses itself and clears. Survives a re-diff, so the landing line can be one that
    // only exists after the buffer was rewritten.
    var pendingFocus by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val lastNewLine = remember(result) {
        result.rows.lastOrNull { it.newLineNumber != null }?.newLineNumber ?: 0
    }
    // Arrowing off the top or bottom of a block hands the caret to the neighbouring block, so the
    // gaps left by deleted lines don't stop the arrow keys walking the file. Reports whether the
    // line exists, so the key is only swallowed when there was somewhere to go.
    val focusLine: (Int, Int) -> Boolean = { line, col ->
        if (line in 1..lastNewLine) {
            pendingFocus = line to col
            true
        } else false
    }
    val requestStructural: ((Int, StructuralEdit) -> Boolean)? = onStructuralEdit?.let { fn ->
        { line, op -> fn(line, op)?.also { pendingFocus = it } != null }
    }
    // The first row of each hunk, so we can hang a single revert chip off a hunk's top line.
    val hunks = remember(result) { hunkRanges(result.rows) }
    val firstRowToHunk = remember(hunks) { hunks.indices.associateBy { hunks[it].first } }
    val revertAt: (Int) -> (() -> Unit)? = { rowIndex ->
        val hunkId = firstRowToHunk[rowIndex]
        if (hunkId != null && onRevertHunk != null) ({ onRevertHunk(hunks[hunkId]) }) else null
    }

    DiffListScaffold(
        rows = result.rows,
        kinds = result.rows.map { it.kind },
        listState = listState,
        ratio = splitRatio,
        onRatioChange = onSplitRatioChange,
        searchKey = searchKey,
        findTrigger = findTrigger,
        rowLocation = { row ->
            val item = itemOfRow.getOrElse(row) { 0 }
            val first = blocks.getOrNull(item)?.range?.first ?: row
            RowLocation(item, ((row - first) * lineHeightPx).toInt())
        },
    ) { listModifier ->
        // One SelectionContainer over the whole list so a drag spans blocks — the user can select a
        // multi-line deleted region on the old (left) side and copy it back. Gutters, the editable
        // column and action chips opt out via DisableSelection so the copy is clean left-side text.
        SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = listModifier,
        ) {
            itemsIndexed(
                items = blocks,
                // Stable identity per block so a re-diff doesn't remount the field (and lose focus,
                // caret or undo history) when only the rows' kinds and spans changed. A block is
                // named by the file line it starts on, which its own edits never move.
                key = { i, _ -> blockKey(blockRows[i]) },
            ) { i, block ->
                val rows = blockRows[i]
                DiffBlockView(
                    rows = rows,
                    firstRowIndex = block.range.first,
                    lineHeightPx = lineHeightPx,
                    density = density,
                    editor = if (block.editable && edit != null) {
                        BlockEditor(
                            edit = edit,
                            blockStates = blockStates,
                            bufferReset = bufferReset,
                            pendingFocus = pendingFocus,
                            onFocusConsumed = { pendingFocus = null },
                            onFocusLine = focusLine,
                            onFocusGained = { caretBlockStart = it },
                            onStructuralEdit = requestStructural,
                            onLineCountChanged = onLineCountChanged,
                        )
                    } else null,
                    currentFile = currentFile,
                    revertAt = revertAt,
                    onResolveAt = onResolveAt,
                    onJump = onJump,
                )
            }
        }
        }
    }
}

/** Everything a block needs to be editable; null on the halves that only display text. */
private class BlockEditor(
    val edit: FileEdit,
    val blockStates: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, TextFieldState>,
    val bufferReset: Int,
    val pendingFocus: Pair<Int, Int>?,
    val onFocusConsumed: () -> Unit,
    val onFocusLine: (Int, Int) -> Boolean,
    val onFocusGained: (Int) -> Unit,
    val onStructuralEdit: ((Int, StructuralEdit) -> Boolean)?,
    val onLineCountChanged: (() -> Unit)?,
)

/** Identity of a block in the list: the file line it starts on, on whichever side it has one. */
private fun blockKey(rows: List<DiffRow>): String {
    val first = rows.first()
    return first.newLineNumber?.let { "n$it" } ?: first.oldLineNumber?.let { "o$it" } ?: "x"
}

@Composable
private fun MergeRowsList(
    rows: List<MergeRow>,
    currentFile: File,
    onResolve: ((Int, ConflictParser.Choice) -> Unit)?,
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget?,
    onJump: (File, Int) -> Unit,
    splitRatio: Float,
    onSplitRatioChange: (Float) -> Unit,
    searchKey: Any,
    findTrigger: Int,
) {
    val listState = rememberLazyListState()
    val kinds = rows.map { if (it is MergeRow.Control) RowKind.CHANGE else (it as MergeRow.Line).row.kind }
    val diffRows = remember(rows) { rows.mapNotNull { (it as? MergeRow.Line)?.row } }
    // The conflict control strips are items in the list but not diff rows, so the two index spaces
    // drift apart. Find works over diffRows; these translate between it and the LazyColumn — one
    // way so a row can claim its highlights, the other so scrolling to a match lands on the right
    // item. A control strip maps to -1: it holds no searchable text.
    val diffIndexOfItem = remember(rows) {
        var next = 0
        IntArray(rows.size) { i -> if (rows[i] is MergeRow.Line) next++ else -1 }
    }
    val itemOfDiffIndex = remember(diffIndexOfItem) {
        IntArray(diffRows.size).also { arr ->
            diffIndexOfItem.forEachIndexed { item, diffIndex -> if (diffIndex >= 0) arr[diffIndex] = item }
        }
    }
    DiffListScaffold(
        rows = diffRows,
        kinds = kinds,
        listState = listState,
        ratio = splitRatio,
        onRatioChange = onSplitRatioChange,
        // Conflict control rows mark a region; tint their lane slot with the conflict colour.
        overrideColor = { idx -> if (rows[idx] is MergeRow.Control) CONFLICT_MARK else null },
        searchKey = searchKey,
        findTrigger = findTrigger,
        rowLocation = { RowLocation(itemOfDiffIndex.getOrElse(it) { 0 }, 0) },
    ) { listModifier ->
        SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = listModifier,
        ) {
            itemsIndexed(rows, key = { index, _ -> index }) { index, item ->
                when (item) {
                    is MergeRow.Control -> ConflictControlStrip(
                        enabled = onResolve != null,
                        onChoose = { choice -> onResolve?.invoke(item.regionId, choice) },
                    )
                    is MergeRow.Line -> MergeLineRow(
                        row = item.row,
                        rowIndex = diffIndexOfItem.getOrElse(index) { -1 },
                        currentFile = currentFile,
                        onResolveAt = onResolveAt,
                        onJump = onJump,
                    )
                }
            }
        }
        }
    }
}

/**
 * The "‹ Use ours · Use both · Use theirs ›" strip rendered above each conflict region. Ours sits
 * over the left pane and theirs over the right, mirroring the two diff halves below it.
 */
@Composable
private fun ConflictControlStrip(
    enabled: Boolean,
    onChoose: (ConflictParser.Choice) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CONFLICT_STRIP_BG)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Ours sits over the left pane and theirs over the right, so the strip follows the split
        // rather than the strip's own midpoint.
        Box(diffHalf(DiffSide.OLD)) {
            // Arrows point inward, toward the merged result: ours (left) → , ← theirs (right).
            ActionChip("Use ours →", CONFLICT_CHIP_BG, enabled, Modifier.align(Alignment.CenterStart)) {
                onChoose(ConflictParser.Choice.OURS)
            }
        }
        ActionChip("↔ Use both", CONFLICT_CHIP_BG, enabled) { onChoose(ConflictParser.Choice.BOTH) }
        Box(Modifier.weight(1f)) {
            ActionChip("← Use theirs", CONFLICT_CHIP_BG, enabled, Modifier.align(Alignment.CenterEnd)) {
                onChoose(ConflictParser.Choice.THEIRS)
            }
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    background: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // Chip labels live inside the list-wide SelectionContainer; exclude them so they're never
    // swept into a text selection.
    DisableSelection {
        BasicText(
            text = label,
            style = TextStyle(
                fontFamily = NopFonts.Mono,
                fontSize = 11.sp,
                color = if (enabled) textColor() else GUTTER_FG,
            ),
            modifier = modifier
                .background(background, CHIP_SHAPE)
                .let { if (enabled) it.clickable(onClick = onClick) else it }
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun MergeLineRow(
    row: DiffRow,
    rowIndex: Int,
    currentFile: File,
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget?,
    onJump: (File, Int) -> Unit,
) {
    val (oldBg, newBg) = backgroundsFor(row)
    Row(
        modifier = Modifier.fillMaxWidth().height(rememberDiffLineHeight()),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        ReadOnlyDiffHalf(
            side = DiffSide.OLD,
            text = row.oldLine,
            spans = row.oldSpans,
            lineNumber = row.oldLineNumber,
            background = oldBg,
            inlineHighlight = INLINE_WORD_BG_OLD,
            rowIndex = rowIndex,
            currentFile = currentFile,
            onResolveAt = onResolveAt,
            onJump = onJump,
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
            rowIndex = rowIndex,
            currentFile = currentFile,
            onResolveAt = onResolveAt,
            onJump = onJump,
            modifier = diffHalf(DiffSide.NEW),
            selectable = false,
        )
    }
}

/**
 * One list item: a run of consecutive diff rows, HEAD's lines on the left and the working file's on
 * the right, each half laid out as a single paragraph of [rows].size lines.
 *
 * Rendering a run as one paragraph rather than a stack of one-line cells is what keeps the two
 * halves on the same grid: both are laid out by the same text engine at the same line height, so
 * line *i* is at the same *y* on both sides by construction, at any display scale. Everything drawn
 * beside the text — the row tints, the line numbers, a hunk's revert chip — is positioned off
 * [lineHeightPx], the step that layout actually uses.
 */
@Composable
private fun DiffBlockView(
    rows: List<DiffRow>,
    firstRowIndex: Int,
    lineHeightPx: Float,
    density: Density,
    editor: BlockEditor?,
    currentFile: File,
    revertAt: (Int) -> (() -> Unit)?,
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget?,
    onJump: (File, Int) -> Unit,
) {
    val height = with(density) { (lineHeightPx * rows.size).toDp() }
    // Fixed-height outer box so the (overlaid) revert chip can never grow a hunk-start block and
    // throw the left/right line alignment off.
    Box(modifier = Modifier.fillMaxWidth().height(height)) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            ReadOnlyBlockHalf(
                side = DiffSide.OLD,
                rows = rows,
                firstRowIndex = firstRowIndex,
                lineHeightPx = lineHeightPx,
                currentFile = currentFile,
                onResolveAt = onResolveAt,
                onJump = onJump,
                modifier = diffHalf(DiffSide.OLD),
            )
            DiffDivider()
            if (editor != null) {
                EditableBlockHalf(
                    rows = rows,
                    firstRowIndex = firstRowIndex,
                    startLine = rows.first().newLineNumber ?: 1,
                    lineHeightPx = lineHeightPx,
                    editor = editor,
                    currentFile = currentFile,
                    onResolveAt = onResolveAt,
                    onJump = onJump,
                    modifier = diffHalf(DiffSide.NEW),
                )
            } else {
                ReadOnlyBlockHalf(
                    side = DiffSide.NEW,
                    rows = rows,
                    firstRowIndex = firstRowIndex,
                    lineHeightPx = lineHeightPx,
                    currentFile = currentFile,
                    onResolveAt = onResolveAt,
                    onJump = onJump,
                    modifier = diffHalf(DiffSide.NEW),
                    selectable = false,
                )
            }
        }
        // Hunk action over the centre divider: copy HEAD's (left) lines over the working side. It
        // rides the divider rather than the block's midpoint, so it stays put as the split moves.
        val dividerX = LocalDiffLayout.current?.oldWidth
        rows.indices.forEach { i ->
            val revert = revertAt(firstRowIndex + i) ?: return@forEach
            key(i) {
                val y = with(density) { (i * lineHeightPx).toDp() }
                ActionChip(
                    label = "‹ revert",
                    background = CHIP_BG,
                    enabled = true,
                    modifier = if (dividerX != null) {
                        Modifier.align(Alignment.TopStart).offset(y = y).centredAtX(dividerX)
                    } else {
                        Modifier.align(Alignment.TopCenter).offset(y = y)
                    },
                    onClick = revert,
                )
            }
        }
    }
}

/** One half of a block that only displays text: gutter, row tints, and the lines as one paragraph. */
@Composable
private fun ReadOnlyBlockHalf(
    side: DiffSide,
    rows: List<DiffRow>,
    firstRowIndex: Int,
    lineHeightPx: Float,
    currentFile: File,
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget?,
    onJump: (File, Int) -> Unit,
    modifier: Modifier = Modifier,
    selectable: Boolean = true,
) {
    val tokenize = LocalDiffTokenizer.current
    val palette = diffPalette()
    val find = rememberFindHits(firstRowIndex, rows.size, side)
    val text = remember(rows, side, tokenize, palette, find) {
        annotateBlock(
            rows.map { it.lineOn(side) },
            rows.map { if (side == DiffSide.OLD) it.oldSpans else it.newSpans },
            if (side == DiffSide.OLD) INLINE_WORD_BG_OLD else INLINE_WORD_BG,
            tokenize,
            palette,
            find,
        )
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BlockHalfFrame(side, rows, lineHeightPx, modifier) {
        val body = @Composable {
            BasicText(
                text = text,
                style = DIFF_TEXT_STYLE.copy(color = textColor()),
                softWrap = false,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .diffLineWidth(side)
                    .padding(end = LINE_END_PAD)
                    // Ctrl-click resolves against the whole block's text — JumpResolver reads the
                    // word straddling an offset, and a newline is as good a word boundary as any.
                    .ctrlClickJump(
                        layoutProvider = { layout },
                        textProvider = { text.text },
                        currentFile = currentFile,
                        onResolveAt = onResolveAt,
                        onJump = onJump,
                    ),
            )
        }
        if (selectable) body() else DisableSelection { body() }
    }
}

/**
 * The working side of a block: one multi-line text field over the file lines the block covers.
 *
 * This is where multi-line editing comes from. The field holds real newlines, so selecting across
 * lines, dragging a selection, Enter, and cut/copy/paste of multi-line text are all its own
 * behaviour — nothing here reimplements them. What this does own is the seam with the rest of the
 * file: the block's text is written back into the shared buffer as exactly the lines it stands for,
 * and the caret walks off either end into the neighbouring block (see [BlockEditor.onFocusLine]),
 * so the gaps the diff leaves where lines were deleted don't fence the editor in.
 */
@OptIn(FlowPreview::class)
@Composable
private fun EditableBlockHalf(
    rows: List<DiffRow>,
    firstRowIndex: Int,
    startLine: Int,
    lineHeightPx: Float,
    editor: BlockEditor,
    currentFile: File,
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget?,
    onJump: (File, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fullState = editor.edit.state
    val state = editor.blockStates.getOrPut(startLine) {
        TextFieldState(rows.joinToString("\n") { it.newLine ?: "" })
    }
    // How many *buffer* lines this block currently stands for. It starts as the block's row count
    // and then follows the field: typing a newline in here makes the block one buffer line longer,
    // and the write-back has to replace that many lines, not the count the last diff reported.
    var owned by remember(state) { mutableStateOf(rows.size) }
    val focusRequester = remember { FocusRequester() }

    // Re-read this block's lines out of the shared buffer. Runs when the block first appears, when
    // a re-diff re-pairs it over a different number of lines, and when something outside the blocks
    // rewrote the buffer (revert, cross-block merge, adopted external change) — but never on a plain
    // re-diff, which would race the user's typing and drop the keystrokes made since it was computed.
    LaunchedEffect(state, startLine, rows.size, editor.bufferReset) {
        val want = linesAt(fullState.text.toString(), startLine, rows.size) ?: return@LaunchedEffect
        owned = rows.size
        if (want != state.text.toString()) {
            // Carry the caret over rather than letting a whole-text replace park it at the end —
            // this fires while the user is looking at the block, sometimes while they're in it.
            val caret = state.selection
            state.edit {
                replace(0, length, want)
                selection = TextRange(
                    caret.start.coerceAtMost(want.length),
                    caret.end.coerceAtMost(want.length),
                )
            }
        }
    }

    // Forward this block's edits into the shared buffer, replacing exactly the lines it owns. Drop
    // the initial emission so a freshly-composed block doesn't write back the value it was just
    // seeded with; the equal-text guard keeps the rest idempotent under echoes.
    LaunchedEffect(state, startLine) {
        snapshotFlow { state.text.toString() }
            .drop(1)
            .distinctUntilChanged()
            .collect { blockText ->
                val full = fullState.text.toString()
                val span = lineSpanOf(full, startLine, owned) ?: return@collect
                if (full.substring(span.start, span.endExclusive) == blockText) return@collect
                fullState.edit { replace(span.start, span.endExclusive, blockText) }
                val lines = lineCountOf(blockText)
                if (lines != owned) {
                    owned = lines
                    editor.onLineCountChanged?.invoke()
                }
            }
    }

    // The caret was handed to this block — by a cross-block merge, or by arrowing off the end of a
    // neighbour. Wait a frame so a block the edit rebuilt is laid out and its requester attached.
    LaunchedEffect(editor.pendingFocus) {
        val target = editor.pendingFocus ?: return@LaunchedEffect
        if (target.first !in startLine until startLine + owned) return@LaunchedEffect
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
        val offset = offsetOfLineCol(state.text.toString(), target.first - startLine, target.second)
        state.edit { selection = TextRange(offset) }
        editor.onFocusConsumed()
    }

    // Same syntax palette as the read-only halves, so an edited line stays coloured (and its
    // comments italic) while the inline-change background layers on top. Tokenizing is cached off
    // the text — the transformation below re-runs on every keystroke, and re-lexing the block's
    // hundreds of lines inside it would be the typing cost that matters.
    val tokenize = LocalDiffTokenizer.current
    val palette = diffPalette()
    val lineTokens by remember(state, tokenize) {
        derivedStateOf {
            val fn = tokenize ?: return@derivedStateOf emptyList()
            state.text.toString().split('\n').map(fn)
        }
    }
    // Find hits are painted here rather than by annotateBlock, because an editable half renders
    // through an OutputTransformation instead of an AnnotatedString — same colours, same order
    // (last, so the highlight reads over the syntax colour and the inline word tint).
    val find = rememberFindHits(firstRowIndex, rows.size, DiffSide.NEW)
    val spans = remember(rows) { rows.map { it.newSpans } }
    val transformation = remember(spans, lineTokens, palette, find) {
        OutputTransformation {
            forEachLine(asCharSequence().toString()) { index, start, end ->
                for (t in lineTokens.getOrElse(index) { emptyList() }) {
                    val s = (start + t.start).coerceIn(start, end)
                    val e = (start + t.endExclusive).coerceIn(s, end)
                    if (e > s) addStyle(palette.styleFor(t.kind), s, e)
                }
                for (span in spans.getOrElse(index) { emptyList() }) {
                    if (!span.changed) continue
                    val s = (start + span.startChar).coerceIn(start, end)
                    val e = (start + span.endCharExclusive).coerceIn(s, end)
                    if (e > s) addStyle(SpanStyle(background = INLINE_WORD_BG), s, e)
                }
                val hits = find.getOrElse(index) { null } ?: return@forEachLine
                for (r in hits.ranges) {
                    val s = (start + r.first).coerceIn(start, end)
                    val e = (start + r.last + 1).coerceIn(s, end)
                    val bg = if (r == hits.active) hits.activeColor else hits.color
                    if (e > s) addStyle(SpanStyle(background = bg), s, e)
                }
            }
        }
    }

    val fg = textColor()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BlockHalfFrame(DiffSide.NEW, rows, lineHeightPx, modifier) {
        // The editable side keeps its own field selection/copy; DisableSelection stops the
        // list-wide SelectionContainer from also trying to select it.
        DisableSelection {
            BasicTextField(
                state = state,
                // Genuine user input marks the shared buffer as user-edited so the autosave will
                // persist it. Programmatic re-seeds of this block go through state.edit {} and never
                // reach here, so they can't trigger a write.
                inputTransformation = InputTransformation { editor.edit.markUserEdit() },
                modifier = Modifier
                    // Sized to the widest line on this side — the same extent the read-only halves
                    // are measured at, so the two sides slide together under the shared horizontal
                    // scroll. It also has to be *at least* that wide: a multi-line field soft-wraps
                    // whatever doesn't fit, and a wrapped line would put every row below it half a
                    // line out from the left half. The end padding the read-only half wears is left
                    // off here so that slack stays inside the field.
                    .diffLineWidth(DiffSide.NEW)
                    .fillMaxHeight()
                    .focusRequester(focusRequester)
                    // Tell the list which block the caret is in, so a re-diff can't dissolve this
                    // one underneath it — see [diffBlocks]' splitAtLine.
                    .onFocusChanged { if (it.isFocused) editor.onFocusGained(startLine) }
                    .onPreviewKeyEvent { event -> onBlockKey(event, state, startLine, owned, editor) }
                    .ctrlClickJump(
                        layoutProvider = { layout },
                        textProvider = { state.text.toString() },
                        currentFile = currentFile,
                        onResolveAt = onResolveAt,
                        onJump = onJump,
                    ),
                textStyle = DIFF_TEXT_STYLE.copy(color = fg),
                cursorBrush = SolidColor(fg),
                lineLimits = TextFieldLineLimits.MultiLine(),
                outputTransformation = transformation,
                onTextLayout = { getResult ->
                    val r = getResult()
                    if (r != null) layout = r
                },
            )
        }
    }
}

/**
 * Keys a block's field can't handle alone, all of them at its edges: Backspace on the first line and
 * Delete on the last have to join a line that lives in another field, and the arrow keys have to
 * carry the caret across the gap the diff leaves where lines were deleted. Everything else — Enter
 * included, which is why there's no split case here — is ordinary text editing and falls through.
 *
 * Returns true when the key was consumed. Shift-arrows are deliberately left alone: extending a
 * selection stops at the block edge rather than teleporting the caret out of it.
 */
private fun onBlockKey(
    event: KeyEvent,
    state: TextFieldState,
    startLine: Int,
    owned: Int,
    editor: BlockEditor,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val text = state.text.toString()
    val sel = state.selection
    val atStart = sel.collapsed && sel.min == 0
    val atEnd = sel.collapsed && sel.min == text.length
    val line = lineIndexOf(text, sel.min)
    val col = sel.min - lineStartOf(text, line)
    val structural = editor.onStructuralEdit
    val plainArrow = sel.collapsed && !event.isShiftPressed
    return when (event.key) {
        Key.Backspace -> atStart && structural != null && structural(startLine, StructuralEdit.MERGE_PREV)
        Key.Delete -> atEnd && structural != null &&
            structural(startLine + owned - 1, StructuralEdit.MERGE_NEXT)
        Key.DirectionUp -> plainArrow && line == 0 && editor.onFocusLine(startLine - 1, col)
        Key.DirectionDown -> plainArrow && line == lineCountOf(text) - 1 &&
            editor.onFocusLine(startLine + owned, col)
        // Stepping off either end lands at the far end of the neighbouring line, as it would in a
        // single-buffer editor. Int.MAX_VALUE is clamped to that line's length.
        Key.DirectionLeft -> plainArrow && atStart && editor.onFocusLine(startLine - 1, Int.MAX_VALUE)
        Key.DirectionRight -> plainArrow && atEnd && editor.onFocusLine(startLine + owned, 0)
        else -> false
    }
}

/** Gutter, row tints and the shared horizontal scroll — the frame both kinds of half render into. */
@Composable
private fun BlockHalfFrame(
    side: DiffSide,
    rows: List<DiffRow>,
    lineHeightPx: Float,
    modifier: Modifier,
    body: @Composable BoxScope.() -> Unit,
) {
    val backgrounds = remember(rows, side) {
        rows.map { backgroundsFor(it).let { (old, new) -> if (side == DiffSide.OLD) old else new } }
    }
    val numbers = remember(rows, side) {
        rows.map { if (side == DiffSide.OLD) it.oldLineNumber else it.newLineNumber }
    }
    Row(
        modifier = modifier
            .fillMaxSize()
            .drawBehind { drawLineBackgrounds(backgrounds, lineHeightPx) },
        verticalAlignment = Alignment.Top,
    ) {
        BlockGutter(numbers)
        Box(Modifier.weight(1f).fillMaxHeight().diffHorizontalScroll(side), content = body)
    }
}

/** This row's text on [side]; null where the row has no line on that side (a diff filler slot). */
private fun DiffRow.lineOn(side: DiffSide): String? = if (side == DiffSide.OLD) oldLine else newLine

/**
 * Walks the lines of [text], handing each one's index and its `[start, end)` char range to [action].
 * Matches the `split('\n')` line model used throughout: a trailing newline yields a final empty line.
 */
private inline fun forEachLine(text: String, action: (index: Int, start: Int, end: Int) -> Unit) {
    var start = 0
    var index = 0
    while (true) {
        val nl = text.indexOf('\n', start)
        val end = if (nl < 0) text.length else nl
        action(index, start, end)
        if (nl < 0) return
        start = nl + 1
        index++
    }
}

/**
 * How many consecutive rows one list item — and so one editable text field — may cover.
 *
 * The cap is what keeps a diff of a large file cheap: only the blocks on screen are laid out, so the
 * work per frame is bounded by the viewport rather than by the file. It's set high because it also
 * bounds how far a single selection, drag or paste can reach; a few hundred lines is past what any
 * of those are used for in practice, while still small enough that re-laying a block out on each
 * keystroke is not something you can feel.
 */
internal const val MAX_BLOCK_LINES = 300

/** A run of consecutive diff rows rendered as one list item. */
internal data class DiffBlock(val range: IntRange, val editable: Boolean)

/**
 * Groups [rows] into the runs the list renders, splitting wherever the working side starts or stops
 * having lines, every [maxLines] rows, and at [splitAtLine].
 *
 * The split at the working side's gaps is forced: a run becomes one text field over a contiguous
 * stretch of the file, and the rows where HEAD had a line the working file doesn't are exactly the
 * places where that stretch ends. [editable] is false when there's no buffer to edit at all (the
 * file is gone), in which case every block is display-only.
 *
 * [splitAtLine] keeps a block starting on a given working line even when the gap that used to
 * separate it from the one above has closed. Editing moves those gaps around — a keystroke can make
 * a line pair up with HEAD where it didn't before — and without this the block being typed into
 * would be absorbed by its neighbour, taking its text field, and with it the caret and the focus,
 * out of the list mid-word. Callers pass the block the caret is in; a stale value costs one extra
 * block boundary and nothing else.
 */
internal fun diffBlocks(
    rows: List<DiffRow>,
    editable: Boolean,
    maxLines: Int = MAX_BLOCK_LINES,
    splitAtLine: Int? = null,
): List<DiffBlock> {
    val blocks = ArrayList<DiffBlock>()
    var start = 0
    while (start < rows.size) {
        val hasNewSide = rows[start].newLineNumber != null
        var end = start + 1
        while (end < rows.size &&
            (rows[end].newLineNumber != null) == hasNewSide &&
            end - start < maxLines &&
            !(splitAtLine != null && rows[end].newLineNumber == splitAtLine)
        ) {
            end++
        }
        blocks.add(DiffBlock(start until end, editable && hasNewSide))
        start = end
    }
    return blocks
}

/** The `[start, endExclusive)` char range of a run of lines — see [lineSpanOf]. */
internal data class LineSpan(val start: Int, val endExclusive: Int)

/**
 * Char range of [count] lines of [text] starting at the 1-based [startLine], or null when that runs
 * off either end. The range covers the lines' text and the newlines between them, but not the one
 * after the last — replacing it swaps those lines and leaves the file's shape alone.
 *
 * Lines are `split('\n')`, so a trailing newline is a final empty line that round-trips. Pure, for
 * unit-testing the block → buffer plumbing.
 */
internal fun lineSpanOf(text: String, startLine: Int, count: Int): LineSpan? {
    if (startLine < 1 || count < 1) return null
    var start = 0
    repeat(startLine - 1) {
        val nl = text.indexOf('\n', start)
        if (nl < 0) return null
        start = nl + 1
    }
    var end = start
    repeat(count - 1) {
        val nl = text.indexOf('\n', end)
        if (nl < 0) return null
        end = nl + 1
    }
    val last = text.indexOf('\n', end)
    return LineSpan(start, if (last < 0) text.length else last)
}

/** The [count] lines of [text] from the 1-based [startLine], joined, or null when out of range. */
internal fun linesAt(text: String, startLine: Int, count: Int): String? =
    lineSpanOf(text, startLine, count)?.let { text.substring(it.start, it.endExclusive) }

/** Lines in [text] under the `split('\n')` model: a trailing newline counts one more, empty line. */
internal fun lineCountOf(text: String): Int = text.count { it == '\n' } + 1

/** 0-based index of the line [offset] falls on. */
internal fun lineIndexOf(text: String, offset: Int): Int {
    var count = 0
    var i = 0
    val limit = offset.coerceIn(0, text.length)
    while (i < limit) {
        if (text[i] == '\n') count++
        i++
    }
    return count
}

/** Char offset the 0-based [line] starts at, clamped to the end of [text] past the last line. */
internal fun lineStartOf(text: String, line: Int): Int {
    var start = 0
    repeat(line.coerceAtLeast(0)) {
        val nl = text.indexOf('\n', start)
        if (nl < 0) return text.length
        start = nl + 1
    }
    return start
}

/**
 * Char offset of column [col] on the 0-based [line]; the column is clamped to that line's length.
 * Clamping the column before adding it, rather than clamping the sum, is deliberate: callers pass
 * `Int.MAX_VALUE` for "the end of that line" and adding it to the line's start would overflow.
 */
internal fun offsetOfLineCol(text: String, line: Int, col: Int): Int {
    val start = lineStartOf(text, line)
    val nl = text.indexOf('\n', start)
    val end = if (nl < 0) text.length else nl
    return start + col.coerceIn(0, end - start)
}

/** A line-structure edit a block's own text field can't make, because it spans two of them. */
internal enum class StructuralEdit { MERGE_PREV, MERGE_NEXT }

/**
 * Apply a structural edit to [full] and report where the caret should land. Lines are 1-based; a
 * file is `split('\n')`, so a trailing newline shows up as a final empty element and round-trips.
 *
 *  - [StructuralEdit.MERGE_PREV] joins line [line] onto the end of the line above; the caret lands
 *    at the seam. No-op on line 1.
 *  - [StructuralEdit.MERGE_NEXT] pulls the line below onto the end of line [line]; the caret stays
 *    at the seam. No-op on the last line.
 *
 * Returns the rewritten text paired with the (1-based line, char column) caret target, or null when
 * the edit can't apply. Pure, for unit-testing the keystroke → buffer plumbing.
 */
internal fun applyStructuralEdit(
    full: String,
    line: Int,
    op: StructuralEdit,
): Pair<String, Pair<Int, Int>>? {
    val lines = full.split('\n').toMutableList()
    val idx = line - 1
    if (idx !in lines.indices) return null
    return when (op) {
        StructuralEdit.MERGE_PREV -> {
            if (idx == 0) return null
            val joinCol = lines[idx - 1].length
            lines[idx - 1] = lines[idx - 1] + lines[idx]
            lines.removeAt(idx)
            lines.joinToString("\n") to (line - 1 to joinCol)
        }
        StructuralEdit.MERGE_NEXT -> {
            if (idx + 1 !in lines.indices) return null
            val joinCol = lines[idx].length
            lines[idx] = lines[idx] + lines[idx + 1]
            lines.removeAt(idx + 1)
            lines.joinToString("\n") to (line to joinCol)
        }
    }
}

/**
 * Working-file (new-side) line number for the row at [index]. Deletion rows carry no new-side
 * number, so we scan forward to the next row that has one (then backward as a fallback). Returns 1
 * for an empty diff or when nothing has a new side. Used to map the diff's top-of-viewport row to a
 * file line for "jump to source". Public for unit-testing.
 */
internal fun newSideLineAt(rows: List<DiffRow>, index: Int): Int {
    if (rows.isEmpty()) return 1
    val start = index.coerceIn(0, rows.lastIndex)
    for (i in start until rows.size) rows[i].newLineNumber?.let { return it }
    for (i in start downTo 0) rows[i].newLineNumber?.let { return it }
    return 1
}

/** Index ranges of maximal runs of non-EQUAL rows — one per visible hunk. */
internal fun hunkRanges(rows: List<DiffRow>): List<IntRange> {
    val ranges = ArrayList<IntRange>()
    var start = -1
    rows.forEachIndexed { i, r ->
        if (r.kind != RowKind.EQUAL) {
            if (start < 0) start = i
        } else if (start >= 0) {
            ranges.add(start until i)
            start = -1
        }
    }
    if (start >= 0) ranges.add(start until rows.size)
    return ranges
}

/**
 * Reconstruct the working buffer with [hunk]'s lines reverted to HEAD: rows inside [hunk] take the
 * old (HEAD) side, every other row keeps its working side. [trailingNewline] reapplies the file's
 * final newline since the line join drops it. Public for unit-testing.
 */
internal fun revertHunk(rows: List<DiffRow>, hunk: IntRange, trailingNewline: Boolean): String {
    val out = ArrayList<String>()
    rows.forEachIndexed { i, r ->
        val line = if (i in hunk) r.oldLine else r.newLine
        if (line != null) out.add(line)
    }
    return out.joinToString("\n") + if (trailingNewline) "\n" else ""
}

