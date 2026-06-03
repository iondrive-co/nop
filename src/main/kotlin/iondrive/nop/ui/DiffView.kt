package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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

// Background tints — muted dark-theme palette
private val INSERT_BG = Color(0x33629755) // green
private val DELETE_BG = Color(0x33B35E5E) // red
private val CHANGE_BG = Color(0x33547B9D) // blue
private val EMPTY_BG = Color(0x14FFFFFF)  // subtle gray (filler for missing side)
private val INLINE_WORD_BG = Color(0x66629755)
private val INLINE_WORD_BG_OLD = Color(0x66B35E5E)
private val GUTTER_FG = Color(0xFF808080)

// Saturated marker colours for the scrollbar lane — must read at a glance on the dark panel.
private val INSERT_MARK = Color(0xFF7DBE6E)
private val DELETE_MARK = Color(0xFFD96B6B)
private val CHANGE_MARK = Color(0xFF6FA8DC)
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
                        withContext(Dispatchers.IO) { edit.save() }
                        savedCallback()
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
            DiffRowsList(
                result = result,
                edit = edit,
                currentFile = workingFile,
                onRevertHunk = onRevertHunk,
                onResolveAt = onResolveAt,
                onJump = onJump,
            )
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
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget?,
    onJump: (File, Int) -> Unit,
) {
    val listState = rememberLazyListState()
    // Per-line editable state, keyed by 1-based new-side line number. Hoisted here so scrolling
    // (which disposes off-screen LazyColumn slots) doesn't lose focus or in-flight edits.
    val rowStates = remember { mutableStateMapOf<Int, TextFieldState>() }
    // The hunk each row belongs to (-1 = none) and the first row of each hunk, so we can hang a
    // single revert chip off a hunk's top line.
    val hunks = remember(result) { hunkRanges(result.rows) }
    val hunkOfRow = remember(hunks, result) {
        IntArray(result.rows.size) { -1 }.also { arr -> hunks.forEachIndexed { id, r -> for (i in r) arr[i] = id } }
    }
    val firstRowToHunk = remember(hunks) { hunks.indices.associateBy { hunks[it].first } }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    onResolveAt = onResolveAt,
                    onJump = onJump,
                )
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
        val kinds = rows.map { if (it is MergeRow.Control) RowKind.CHANGE else (it as MergeRow.Line).row.kind }
        // Conflict control rows mark a region; tint their lane slot with the conflict colour.
        ChangeMarkerLane(kinds, listState) { idx -> if (rows[idx] is MergeRow.Control) CONFLICT_MARK else null }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ChangeMarkerLane(
    kinds: List<RowKind>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    overrideColor: (Int) -> Color? = { null },
) {
    // Marker lane sits just to the left of the scrollbar, so the markers stay readable even while
    // the user is dragging the (translucent) scrollbar thumb across them.
    Row(
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(MARKER_LANE_W).fillMaxHeight()) {
            drawChangeMarkers(kinds, overrideColor)
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            style = NopScrollbarStyle,
            modifier = Modifier.width(SCROLLBAR_W).fillMaxHeight(),
        )
    }
}

private val MARKER_LANE_W = 4.dp
private val SCROLLBAR_W = 10.dp

private fun DrawScope.drawChangeMarkers(kinds: List<RowKind>, overrideColor: (Int) -> Color?) {
    val n = kinds.size
    if (n == 0) return
    val markerH = (size.height / n).coerceAtLeast(3f)
    val w = size.width
    kinds.forEachIndexed { idx, kind ->
        val color = overrideColor(idx) ?: when (kind) {
            RowKind.EQUAL -> return@forEachIndexed
            RowKind.INSERT -> INSERT_MARK
            RowKind.DELETE -> DELETE_MARK
            RowKind.CHANGE -> CHANGE_MARK
        }
        val y = (idx.toFloat() / n) * size.height
        drawRect(color = color, topLeft = Offset(0f, y), size = Size(w, markerH))
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

@Composable
private fun ReadOnlyDiffHalf(
    text: String?,
    spans: List<InlineSpan>,
    lineNumber: Int?,
    background: Color,
    inlineHighlight: Color,
    currentFile: File,
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget?,
    onJump: (File, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayText = text ?: ""
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Row(
        modifier = modifier.fillMaxSize().background(background),
        verticalAlignment = Alignment.Top,
    ) {
        GutterCell(lineNumber)
        BasicText(
            text = annotateLine(displayText, spans, inlineHighlight),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = textColor(),
            ),
            softWrap = false,
            onTextLayout = { layout = it },
            modifier = Modifier
                .padding(end = 4.dp)
                .ctrlClickJump(
                    layoutProvider = { layout },
                    textProvider = { displayText },
                    currentFile = currentFile,
                    onResolveAt = onResolveAt,
                    onJump = onJump,
                ),
        )
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
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget?,
    onJump: (File, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rowStates.getOrPut(lineNumber) { TextFieldState(initialText) }

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

    val transformation = remember(spans, inlineHighlight) {
        OutputTransformation {
            val text = asCharSequence().toString()
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
        BasicTextField(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 4.dp)
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

/**
 * Ctrl-click on a word inside this widget calls [onResolveAt]; on a hit, [onJump] is invoked
 * with the resolved file/line. The event is consumed on the Initial pass so the host's text
 * field (when one exists) doesn't move the caret in response to the same click.
 */
private fun Modifier.ctrlClickJump(
    layoutProvider: () -> TextLayoutResult?,
    textProvider: () -> String,
    currentFile: File,
    onResolveAt: (currentFile: File, text: String, offset: Int) -> JumpTarget?,
    onJump: (File, Int) -> Unit,
): Modifier = this.pointerInput(currentFile) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Press) continue
            if (!event.keyboardModifiers.isCtrlPressed) continue
            val change = event.changes.firstOrNull() ?: continue
            val tl = layoutProvider() ?: continue
            val text = textProvider()
            val offset = tl.getOffsetForPosition(change.position)
            val target = onResolveAt(currentFile, text, offset)
            if (target != null) {
                change.consume()
                onJump(target.file, target.line)
            }
        }
    }
}

@Composable
private fun GutterCell(lineNumber: Int?) {
    BasicText(
        text = lineNumber?.toString()?.padStart(5) ?: "     ",
        style = TextStyle(
            color = GUTTER_FG,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        ),
        softWrap = false,
        modifier = Modifier.padding(horizontal = 6.dp),
    )
}

@Composable
private fun textColor(): Color =
    if (JewelTheme.isDark) Color(0xFFA9B7C6) else Color(0xFF1F2329)

private fun annotateLine(
    text: String,
    spans: List<InlineSpan>,
    highlightColor: Color,
): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        for (s in spans) {
            // Defensive: clamp into the line. A malformed span (e.g. start > end after clamping,
            // or a negative start from a stray close sentinel) used to throw StringIndexOOB and
            // tear down the whole row during scroll.
            val start = s.startChar.coerceIn(0, text.length)
            val end = s.endCharExclusive.coerceIn(start, text.length)
            if (end == start) continue
            val piece = text.substring(start, end)
            if (s.changed) {
                withStyle(SpanStyle(background = highlightColor)) { append(piece) }
            } else {
                append(piece)
            }
        }
    }
}

private fun backgroundsFor(row: DiffRow): Pair<Color, Color> = when (row.kind) {
    RowKind.EQUAL -> Color.Transparent to Color.Transparent
    RowKind.CHANGE -> CHANGE_BG to CHANGE_BG
    RowKind.INSERT -> EMPTY_BG to INSERT_BG
    RowKind.DELETE -> DELETE_BG to EMPTY_BG
}

private val IntrinsicMinHeightLine = 18.dp
