package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.diff.ConflictParser
import iondrive.nop.diff.DiffComputer
import iondrive.nop.diff.DiffResult
import iondrive.nop.diff.DiffRow
import iondrive.nop.diff.InlineSpan
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
import org.jetbrains.jewel.foundation.theme.JewelTheme
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
) {
    val workingFile = remember(tab.id) { File(tab.repoRoot, tab.change.path) }
    // Resolve a per-file FileEdit via FileEditStore — same instance an open Tab.FileView would
    // use, so edits in either view share the buffer and autosave coordinates through one place.
    // For untracked/added files this is still fine; for removed/missing files the working file
    // doesn't exist and the buffer starts empty.
    val edit = remember(tab.id) {
        if (workingFile.isFile) editStore.edit(Tab.FileView(workingFile)) else null
    }

    var loading by remember(tab.id) { mutableStateOf(true) }
    var error by remember(tab.id) { mutableStateOf<String?>(null) }
    var content by remember(tab.id) { mutableStateOf<DiffContent?>(null) }
    var headText by remember(tab.id) { mutableStateOf("") }

    val savedCallback by rememberUpdatedState(onFileSaved)

    LaunchedEffect(tab.id) {
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
                if (diskText != null && !edit.isModified) edit.adoptDiskText(diskText)
            }
            val workingNow = when (tab.change.kind) {
                ChangeKind.REMOVED, ChangeKind.MISSING -> ""
                else -> edit?.state?.text?.toString() ?: ""
            }
            content = withContext(Dispatchers.Default) { computeContent(head, workingNow) }
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
                    if (text != edit.savedText) {
                        // save() is a compare-and-swap: if the file changed under us (git
                        // checkout/pull, an agent), it refuses to overwrite and the background poll
                        // reconciles instead. Only refresh git status when we actually wrote.
                        if (withContext(Dispatchers.IO) { edit.save() } is SaveResult.Saved) savedCallback()
                    }
                }
        }
    }

    // Resolve conflict [regionId] by copying ours / theirs / both into the working buffer. We
    // re-parse the live buffer rather than trusting a snapshot so the index always lines up with
    // what's on screen; the re-diff + autosave effects above pick the write up from there.
    val resolveConflict: ((Int, ConflictParser.Choice) -> Unit)? = edit?.let {
        { regionId, choice ->
            val current = it.state.text.toString()
            val next = ConflictParser.resolve(ConflictParser.parse(current), regionId, choice)
            if (next != current) it.state.edit { replace(0, length, next) }
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
        )
        content is DiffContent.Ordinary -> {
            val result = (content as DiffContent.Ordinary).result
            // Reverting a hunk copies HEAD's (left) version of those lines over the working (right)
            // side. Reconstruct from the displayed rows and write the whole buffer back.
            val onRevertHunk: ((IntRange) -> Unit)? = edit?.let {
                { hunk ->
                    val current = it.state.text.toString()
                    val next = revertHunk(result.rows, hunk, current.endsWith("\n"))
                    if (next != current) it.state.edit { replace(0, length, next) }
                }
            }
            // Structural edits (Enter to split a line, Backspace/Delete to merge lines) the per-line
            // SingleLine fields can't express on their own. We rewrite the whole buffer and recompute
            // the diff synchronously so the new row exists this frame for focus to land on; the
            // debounced re-diff/autosave effects then pick the write up as usual. Returns the caret
            // landing spot (1-based line, char column) so the caller can move focus there.
            val onStructuralEdit: ((Int, Int, Int, StructuralEdit) -> Pair<Int, Int>?)? = edit?.let {
                { line, startCol, endCol, op ->
                    val current = it.state.text.toString()
                    val res = applyStructuralEdit(current, line, startCol, endCol, op)
                    if (res != null && res.first != current) {
                        it.state.edit { replace(0, length, res.first) }
                        content = computeContent(headText, res.first)
                        res.second
                    } else null
                }
            }
            DiffRowsList(
                result = result,
                edit = edit,
                currentFile = workingFile,
                onRevertHunk = onRevertHunk,
                onStructuralEdit = onStructuralEdit,
                onResolveAt = onResolveAt,
                onJump = onJump,
                onTopLine = onTopLine,
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
    currentFile: File,
    onRevertHunk: ((IntRange) -> Unit)?,
    onStructuralEdit: ((Int, Int, Int, StructuralEdit) -> Pair<Int, Int>?)?,
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget?,
    onJump: (File, Int) -> Unit,
    onTopLine: (Int) -> Unit = {},
) {
    val listState = rememberLazyListState()

    // Report the working-file line at the top of the viewport so a "jump to source" (F4) lands the
    // file on what's on screen. rememberUpdatedState keeps the rows current without restarting the
    // flow on every re-diff.
    val rowsForTopLine by rememberUpdatedState(result.rows)
    LaunchedEffect(listState, onTopLine) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { idx -> onTopLine(newSideLineAt(rowsForTopLine, idx)) }
    }
    // Per-line editable state, keyed by 1-based new-side line number. Hoisted here so scrolling
    // (which disposes off-screen LazyColumn slots) doesn't lose focus or in-flight edits.
    val rowStates = remember { mutableStateMapOf<Int, TextFieldState>() }
    // Where to move the caret after a structural edit reshapes the line numbering. The target row
    // claims it (matching on line number), focuses itself and clears it. Survives re-diff so the
    // landing row can be one that only exists after the buffer was rewritten.
    var pendingFocus by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val requestStructural: ((Int, Int, Int, StructuralEdit) -> Boolean)? = onStructuralEdit?.let { fn ->
        { line, startCol, endCol, op ->
            val focus = fn(line, startCol, endCol, op)
            if (focus != null) {
                pendingFocus = focus
                true
            } else false
        }
    }
    // The hunk each row belongs to (-1 = none) and the first row of each hunk, so we can hang a
    // single revert chip off a hunk's top line.
    val hunks = remember(result) { hunkRanges(result.rows) }
    val hunkOfRow = remember(hunks, result) {
        IntArray(result.rows.size) { -1 }.also { arr -> hunks.forEachIndexed { id, r -> for (i in r) arr[i] = id } }
    }
    val firstRowToHunk = remember(hunks) { hunks.indices.associateBy { hunks[it].first } }

    Box(modifier = Modifier.fillMaxSize()) {
        // One SelectionContainer over the whole list so a drag spans rows — the user can select a
        // multi-line deleted block on the old (left) side and copy it back. Gutters, the right
        // column and action chips opt out via DisableSelection so the copy is clean left-side text.
        SelectionContainer {
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = MARKER_LANE_W + SCROLLBAR_W),
        ) {
            itemsIndexed(
                items = result.rows,
                // Stable identity per line so re-diff doesn't remount cells (and lose focus or
                // caret position) when only the row's kind/spans change. For rows with no new
                // side we synthesize a key off the old line so identity stays unique.
                key = { _, row ->
                    when {
                        row.newLineNumber != null -> "n${row.newLineNumber}"
                        row.oldLineNumber != null -> "o${row.oldLineNumber}"
                        else -> "x${result.rows.indexOf(row)}"
                    }
                },
            ) { index, row ->
                val hunkId = firstRowToHunk[index]
                DiffRowView(
                    row = row,
                    edit = edit,
                    rowStates = rowStates,
                    currentFile = currentFile,
                    revertHunk = if (hunkId != null && onRevertHunk != null) {
                        { onRevertHunk(hunks[hunkId]) }
                    } else null,
                    onStructuralEdit = requestStructural,
                    pendingFocus = pendingFocus,
                    onFocusConsumed = { pendingFocus = null },
                    onResolveAt = onResolveAt,
                    onJump = onJump,
                )
            }
        }
        }
        ChangeMarkerLane(result.rows.map { it.kind }, listState)
    }
}

@Composable
private fun MergeRowsList(
    rows: List<MergeRow>,
    currentFile: File,
    onResolve: ((Int, ConflictParser.Choice) -> Unit)?,
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget?,
    onJump: (File, Int) -> Unit,
) {
    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        SelectionContainer {
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = MARKER_LANE_W + SCROLLBAR_W),
        ) {
            itemsIndexed(rows, key = { index, _ -> index }) { _, item ->
                when (item) {
                    is MergeRow.Control -> ConflictControlStrip(
                        enabled = onResolve != null,
                        onChoose = { choice -> onResolve?.invoke(item.regionId, choice) },
                    )
                    is MergeRow.Line -> MergeLineRow(
                        row = item.row,
                        currentFile = currentFile,
                        onResolveAt = onResolveAt,
                        onJump = onJump,
                    )
                }
            }
        }
        }
        val kinds = rows.map { if (it is MergeRow.Control) RowKind.CHANGE else (it as MergeRow.Line).row.kind }
        // Conflict control rows mark a region; tint their lane slot with the conflict colour.
        ChangeMarkerLane(kinds, listState) { idx -> if (rows[idx] is MergeRow.Control) CONFLICT_MARK else null }
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
        Box(Modifier.weight(1f)) {
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
                fontFamily = FontFamily.Monospace,
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
    currentFile: File,
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget?,
    onJump: (File, Int) -> Unit,
) {
    val (oldBg, newBg) = backgroundsFor(row)
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicMinHeightLine),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        ReadOnlyDiffHalf(
            text = row.oldLine,
            spans = row.oldSpans,
            lineNumber = row.oldLineNumber,
            background = oldBg,
            inlineHighlight = INLINE_WORD_BG_OLD,
            currentFile = currentFile,
            onResolveAt = onResolveAt,
            onJump = onJump,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(1.dp).fillMaxSize().background(Color(0x33FFFFFF)))
        ReadOnlyDiffHalf(
            text = row.newLine,
            spans = row.newSpans,
            lineNumber = row.newLineNumber,
            background = newBg,
            inlineHighlight = INLINE_WORD_BG,
            currentFile = currentFile,
            onResolveAt = onResolveAt,
            onJump = onJump,
            modifier = Modifier.weight(1f),
            selectable = false,
        )
    }
}

@Composable
private fun DiffRowView(
    row: DiffRow,
    edit: FileEdit?,
    rowStates: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, TextFieldState>,
    currentFile: File,
    revertHunk: (() -> Unit)?,
    onStructuralEdit: ((Int, Int, Int, StructuralEdit) -> Boolean)?,
    pendingFocus: Pair<Int, Int>?,
    onFocusConsumed: () -> Unit,
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget?,
    onJump: (File, Int) -> Unit,
) {
    val (oldBg, newBg) = backgroundsFor(row)

    // Fixed-height outer box so the (overlaid) revert chip can never grow a hunk-start row and
    // throw the left/right line alignment off.
    Box(modifier = Modifier.fillMaxWidth().height(IntrinsicMinHeightLine)) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            ReadOnlyDiffHalf(
                text = row.oldLine,
                spans = row.oldSpans,
                lineNumber = row.oldLineNumber,
                background = oldBg,
                inlineHighlight = INLINE_WORD_BG_OLD,
                currentFile = currentFile,
                onResolveAt = onResolveAt,
                onJump = onJump,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.width(1.dp).fillMaxSize().background(Color(0x33FFFFFF)))
            val newLineNumber = row.newLineNumber
            if (newLineNumber != null && edit != null && row.newLine != null) {
                EditableDiffHalf(
                    lineNumber = newLineNumber,
                    initialText = row.newLine!!,
                    spans = row.newSpans,
                    fullState = edit.state,
                    rowStates = rowStates,
                    background = newBg,
                    inlineHighlight = INLINE_WORD_BG,
                    currentFile = currentFile,
                    onStructuralEdit = onStructuralEdit,
                    pendingFocus = pendingFocus,
                    onFocusConsumed = onFocusConsumed,
                    onResolveAt = onResolveAt,
                    onJump = onJump,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ReadOnlyDiffHalf(
                    text = row.newLine,
                    spans = row.newSpans,
                    lineNumber = newLineNumber,
                    background = newBg,
                    inlineHighlight = INLINE_WORD_BG,
                    currentFile = currentFile,
                    onResolveAt = onResolveAt,
                    onJump = onJump,
                    modifier = Modifier.weight(1f),
                    selectable = false,
                )
            }
        }
        // Hunk action over the centre divider: copy HEAD's (left) lines over the working side.
        if (revertHunk != null) {
            ActionChip(
                label = "‹ revert",
                background = CHIP_BG,
                enabled = true,
                modifier = Modifier.align(Alignment.TopCenter),
                onClick = revertHunk,
            )
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun EditableDiffHalf(
    lineNumber: Int,
    initialText: String,
    spans: List<InlineSpan>,
    fullState: TextFieldState,
    rowStates: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, TextFieldState>,
    background: Color,
    inlineHighlight: Color,
    currentFile: File,
    onStructuralEdit: ((Int, Int, Int, StructuralEdit) -> Boolean)?,
    pendingFocus: Pair<Int, Int>?,
    onFocusConsumed: () -> Unit,
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget?,
    onJump: (File, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rowStates.getOrPut(lineNumber) { TextFieldState(initialText) }
    val focusRequester = remember { FocusRequester() }

    // A structural edit (above) rewrote the buffer and asked the caret to land on this line. Wait a
    // frame so the rebuilt row is laid out and its FocusRequester is attached, then grab focus and
    // place the cursor at the requested column. coerceIn guards against the reconcile effect not yet
    // having seeded this freshly-shifted cell with its new text.
    LaunchedEffect(pendingFocus) {
        if (pendingFocus?.first != lineNumber) return@LaunchedEffect
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
        val col = pendingFocus.second.coerceIn(0, state.text.length)
        state.edit { selection = TextRange(col) }
        onFocusConsumed()
    }

    // If the underlying buffer changed line N from outside (e.g. another diff cell wrote, or a
    // shared Tab.FileView edited), reflect that here. Comparing first avoids the snapshot loop
    // where echoing the same value back would re-fire our own writeback effect.
    LaunchedEffect(initialText, state) {
        if (state.text.toString() != initialText) {
            state.edit { replace(0, length, initialText) }
        }
    }

    // Forward edits in this cell back into the full buffer at line N. Drop the initial emission
    // so a freshly-scrolled-in cell doesn't immediately overwrite the buffer with the value we
    // just seeded it from. Equal-text guards keep this idempotent under external echoes.
    LaunchedEffect(state, lineNumber, fullState) {
        snapshotFlow { state.text.toString() }
            .drop(1)
            .distinctUntilChanged()
            .collect { rowText ->
                val current = fullState.text.toString()
                val next = replaceLine(current, lineNumber, rowText)
                if (next != current) {
                    fullState.edit { replace(0, length, next) }
                }
            }
    }

    // Same syntax palette as the read-only halves and the editor, so an edited line stays coloured
    // (and its comments italic) while the inline-change background layers on top.
    val tokenize = LocalDiffTokenizer.current
    val palette = if (JewelTheme.isDark) HighlightPalette.Dark else HighlightPalette.Light
    val transformation = remember(spans, inlineHighlight, tokenize, palette) {
        OutputTransformation {
            val text = asCharSequence().toString()
            if (tokenize != null) applyTokens(this, tokenize(text), palette)
            for (s in spans) {
                if (!s.changed) continue
                val start = s.startChar.coerceIn(0, text.length)
                val end = s.endCharExclusive.coerceIn(start, text.length)
                if (end > start) addStyle(SpanStyle(background = inlineHighlight), start, end)
            }
        }
    }

    val fg = textColor()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Row(
        modifier = modifier.fillMaxSize().background(background),
        verticalAlignment = Alignment.Top,
    ) {
        GutterCell(lineNumber)
        // The editable side keeps its own field selection/copy; DisableSelection stops the
        // list-wide SelectionContainer from also trying to select it.
        DisableSelection {
        BasicTextField(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 4.dp)
                .focusRequester(focusRequester)
                // Turn the per-line fields into a real editor: Enter splits this line at the caret,
                // Backspace at column 0 merges it into the line above, Delete at line end pulls the
                // next line up. Each rewrites the whole buffer via onStructuralEdit and consumes the
                // key so SingleLine never sees it. Everything else falls through to normal editing.
                .onPreviewKeyEvent { event ->
                    val structural = onStructuralEdit
                    if (structural == null || event.type != KeyEventType.KeyDown) {
                        return@onPreviewKeyEvent false
                    }
                    val sel = state.selection
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter ->
                            structural(lineNumber, sel.min, sel.max, StructuralEdit.SPLIT)
                        Key.Backspace ->
                            if (sel.collapsed && sel.min == 0) {
                                structural(lineNumber, 0, 0, StructuralEdit.MERGE_PREV)
                            } else false
                        Key.Delete ->
                            if (sel.collapsed && sel.min == state.text.length) {
                                structural(lineNumber, 0, 0, StructuralEdit.MERGE_NEXT)
                            } else false
                        else -> false
                    }
                }
                .ctrlClickJump(
                    layoutProvider = { layout },
                    textProvider = { state.text.toString() },
                    currentFile = currentFile,
                    onResolveAt = onResolveAt,
                    onJump = onJump,
                ),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = fg,
            ),
            cursorBrush = SolidColor(fg),
            // SingleLine rejects Enter from typing and strips newlines from paste, which matches
            // the v1 contract: edits within an existing line only — no structural changes.
            lineLimits = TextFieldLineLimits.SingleLine,
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
 * Replace the [lineNumber]-th (1-based) line of [full] with [newLine]. No-ops when the index is
 * out of range or the line already matches, so we can call it freely from observer loops
 * without producing spurious writes. Public for unit-testing the row → buffer plumbing.
 */
internal fun replaceLine(full: String, lineNumber: Int, newLine: String): String {
    val lines = full.split('\n').toMutableList()
    val idx = lineNumber - 1
    if (idx !in lines.indices) return full
    if (lines[idx] == newLine) return full
    lines[idx] = newLine
    return lines.joinToString("\n")
}

/** A line-structure edit the per-line diff fields can't make on their own. */
internal enum class StructuralEdit { SPLIT, MERGE_PREV, MERGE_NEXT }

/**
 * Apply a structural edit to [full] and report where the caret should land. Lines are 1-based; a
 * file is `split('\n')`, so a trailing newline shows up as a final empty element and round-trips.
 *
 *  - [StructuralEdit.SPLIT] breaks line [line] into two, dropping any text selected between
 *    [startCol] and [endCol] (collapsed caret ⇒ a plain split). The caret lands at the start of the
 *    new lower line.
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
    startCol: Int,
    endCol: Int,
    op: StructuralEdit,
): Pair<String, Pair<Int, Int>>? {
    val lines = full.split('\n').toMutableList()
    val idx = line - 1
    if (idx !in lines.indices) return null
    return when (op) {
        StructuralEdit.SPLIT -> {
            val s = lines[idx]
            val a = startCol.coerceIn(0, s.length)
            val b = endCol.coerceIn(a, s.length)
            lines[idx] = s.substring(0, a)
            lines.add(idx + 1, s.substring(b))
            lines.joinToString("\n") to (line + 1 to 0)
        }
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

