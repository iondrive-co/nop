package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.diff.DiffRow
import iondrive.nop.diff.InlineSpan
import iondrive.nop.diff.RowKind
import iondrive.nop.index.JumpTarget
import java.io.File
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme

// Shared building blocks for every side-by-side diff renderer (the working-tree [DiffView] and the
// commit-history [CommitDiffView]). Keeping the read-only half, gutter, colours, selection rules
// and change-marker lane in one place means a change like "darker font" or "selectable text"
// applies to both views at once instead of having to be copied between near-identical files.

// Background tints — muted dark-theme palette.
internal val INSERT_BG = Color(0x30456E4E)  // Green tint for insertions
internal val DELETE_BG = Color(0x305E3D40)  // Red tint for deletions
internal val CHANGE_BG = Color(0x30374F6B)  // Blue tint for changes
internal val EMPTY_BG = Color(0x0AFFFFFF)   // Very subtle gray for filler
internal val INLINE_WORD_BG = Color(0x60456E4E)       // Stronger green for inline word changes
internal val INLINE_WORD_BG_OLD = Color(0x605E3D40)   // Stronger red for deleted words
internal val GUTTER_FG = Color(0xFF6F737A)

// Saturated marker colours for the scrollbar lane — must read at a glance on the dark panel.
internal val INSERT_MARK = Color(0xFF5FAD65)
internal val DELETE_MARK = Color(0xFFE05555)
internal val CHANGE_MARK = Color(0xFF6B9BD2)

internal val MARKER_LANE_W = 4.dp
internal val SCROLLBAR_W = 8.dp
internal val IntrinsicMinHeightLine = 18.dp

/** Breathing room after a line's last character, so text never butts against the centre divider. */
internal val LINE_END_PAD = 4.dp

/** The hairline between the halves, and the (invisible) band around it you can grab to drag it. */
private val DIVIDER_W = 1.dp
private val DIVIDER_GRAB_W = 9.dp

/** Neither half may be dragged narrower than this, so a gutter always has room to render. */
private val MIN_HALF_W = 80.dp

/** The face every diff line renders in; callers layer the row's foreground colour on top. */
internal val DIFF_TEXT_STYLE = TextStyle(fontFamily = NopFonts.Mono, fontSize = 12.sp)

// The tokenizer for the file a diff is showing, provided by [DiffView]/[CommitDiffView] and read by
// the diff halves so the comparison views get the same syntax colouring (and italic comments) as
// the editor tabs. null means "no highlighting for this file type" — render plain. Lines are
// tokenized individually; a block comment that spans lines only highlights the lines that carry its
// delimiters, which is an acceptable approximation for a read-only diff.
internal val LocalDiffTokenizer = compositionLocalOf<((String) -> List<Token>)?> { null }

/** Which half of a side-by-side diff a cell belongs to: HEAD on the left, working copy on the right. */
internal enum class DiffSide { OLD, NEW }

/**
 * The horizontal scroll one side of a diff shares across every one of its rows.
 *
 * Each line is its own composable — a LazyColumn slot that comes and goes as you scroll — so the
 * rows can't each own a scroll state and still stay lined up. They share one [state], and every
 * line cell is measured at [contentWidth], the width of this side's longest line in the *whole*
 * diff rather than whatever happens to be on screen. That makes each row's scroll node arrive at
 * the same extent, so a side moves as one piece and its scrollbar means the same thing at every
 * position.
 *
 * The two sides scroll separately because the divider between them moves: unequal halves are
 * unequal viewports, and a shared state can only carry one extent.
 */
internal class DiffSideScroll(val state: ScrollState, val contentWidth: Dp, val charWidth: Dp)

/**
 * What a diff row needs to know about the list around it: where the draggable divider currently
 * sits ([oldWidth], the width of the left half) and how each side scrolls sideways. null means the
 * row isn't inside a [DiffListScaffold], and the halves fall back to an even, unscrolled split.
 */
internal class DiffLayout(
    val oldWidth: Dp,
    val old: DiffSideScroll,
    val new: DiffSideScroll,
) {
    fun scroll(side: DiffSide): DiffSideScroll = if (side == DiffSide.OLD) old else new
}

internal val LocalDiffLayout = compositionLocalOf<DiffLayout?> { null }

/** Characters measured in one go to derive the monospace advance; amortises rounding to <0.01px. */
private const val ADVANCE_SAMPLE = 100

/**
 * Scroll state for one [side] of a diff over [rows], sized to that side's longest line.
 *
 * Width comes from a character count times the font's advance rather than from laying the line out:
 * every diff surface renders in [NopFonts.Mono], so the two agree, and a file whose longest line is
 * a minified 200k-character blob costs an integer scan instead of a text layout.
 */
@Composable
internal fun rememberDiffSideScroll(rows: List<DiffRow>, side: DiffSide): DiffSideScroll {
    val state = rememberScrollState()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val longest = remember(rows, side) { maxDiffLineLength(rows, side) }
    // The per-character advance is also what lets find-in-diff scroll a hit into view sideways:
    // a match's char offset times this width is its x position within the line.
    val charWidth = remember(measurer, density) {
        val sample = measurer.measure("0".repeat(ADVANCE_SAMPLE), DIFF_TEXT_STYLE).size.width
        with(density) { (sample / ADVANCE_SAMPLE.toFloat()).toDp() }
    }
    val contentWidth = remember(longest, charWidth) { charWidth * longest + LINE_END_PAD }
    return remember(state, contentWidth, charWidth) { DiffSideScroll(state, contentWidth, charWidth) }
}

/** Longest line of [side], in characters — the monospace stand-in for "widest". */
internal fun maxDiffLineLength(rows: List<DiffRow>, side: DiffSide): Int {
    var longest = 0
    for (row in rows) {
        val line = if (side == DiffSide.OLD) row.oldLine else row.newLine
        if (line != null && line.length > longest) longest = line.length
    }
    return longest
}

/**
 * The modifier one half of a diff row wears. The old side takes the width the divider was dragged
 * to; the new side takes whatever is left, so the two always add up to the row.
 */
@Composable
internal fun RowScope.diffHalf(side: DiffSide): Modifier {
    val layout = LocalDiffLayout.current
    return if (side == DiffSide.OLD && layout != null) Modifier.width(layout.oldWidth) else Modifier.weight(1f)
}

/** Makes this line cell a viewport onto [side]'s shared horizontal scroll. */
@Composable
internal fun Modifier.diffHorizontalScroll(side: DiffSide): Modifier {
    val layout = LocalDiffLayout.current ?: return this
    return horizontalScroll(layout.scroll(side).state)
}

/**
 * Sizes a line cell to the longest line on its [side], so every row scrolls over the same extent —
 * short lines simply trail off into empty space instead of stopping the scroll early.
 */
@Composable
internal fun Modifier.diffLineWidth(side: DiffSide): Modifier {
    val layout = LocalDiffLayout.current ?: return fillMaxWidth()
    return width(layout.scroll(side).contentWidth)
}

/** The hairline between the two halves. Its draggable hit area is overlaid by [DiffListScaffold]. */
@Composable
internal fun DiffDivider() {
    Box(Modifier.width(DIVIDER_W).fillMaxSize().background(Color(0x33FFFFFF)))
}

/**
 * Places this widget centred on [x] from the row's left edge whatever its own width — used to hang
 * the hunk-revert chip off the divider, which moves as the split is dragged.
 */
internal fun Modifier.centredAtX(x: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    layout(placeable.width, placeable.height) {
        placeable.place(x.roundToPx() - placeable.width / 2, 0)
    }
}

/** Body text colour: light grey on the dark theme, near-black on the light theme for contrast. */
@Composable
internal fun textColor(): Color =
    if (JewelTheme.isDark) Color(0xFFBCBEC4) else Color(0xFF000000)

internal fun backgroundsFor(row: DiffRow): Pair<Color, Color> = when (row.kind) {
    RowKind.EQUAL -> Color.Transparent to Color.Transparent
    RowKind.CHANGE -> CHANGE_BG to CHANGE_BG
    RowKind.INSERT -> EMPTY_BG to INSERT_BG
    RowKind.DELETE -> DELETE_BG to EMPTY_BG
}

internal fun annotateLine(
    text: String,
    spans: List<InlineSpan>,
    highlightColor: Color,
    tokens: List<Token> = emptyList(),
    palette: HighlightPalette? = null,
    find: LineFindHits? = null,
): AnnotatedString {
    val hasSyntax = palette != null && tokens.isNotEmpty()
    if (spans.isEmpty() && !hasSyntax && find == null) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        // Syntax colouring underneath, so the inline-change background (added next) layers over it.
        if (palette != null) {
            for (t in tokens) {
                val s = t.start.coerceIn(0, text.length)
                val e = t.endExclusive.coerceIn(s, text.length)
                if (e > s) addStyle(palette.styleFor(t.kind), s, e)
            }
        }
        for (s in spans) {
            if (!s.changed) continue
            // Defensive: clamp into the line. A malformed span (e.g. start > end after clamping,
            // or a negative start from a stray close sentinel) used to throw StringIndexOOB and
            // tear down the whole row during scroll.
            val start = s.startChar.coerceIn(0, text.length)
            val end = s.endCharExclusive.coerceIn(start, text.length)
            if (end > start) addStyle(SpanStyle(background = highlightColor), start, end)
        }
        // Find hits go on last so the search highlight reads over both the syntax colour and the
        // inline word-change tint, rather than being swallowed by them.
        if (find != null) addFindHits(find, text.length)
    }
}

/** Paints [find]'s ranges onto a line of [length] chars, the active hit in its own colour. */
private fun AnnotatedString.Builder.addFindHits(find: LineFindHits, length: Int) {
    for (r in find.ranges) {
        val start = r.first.coerceIn(0, length)
        val end = (r.last + 1).coerceIn(start, length)
        if (end > start) {
            addStyle(SpanStyle(background = if (r == find.active) find.activeColor else find.color), start, end)
        }
    }
}

/**
 * The find hits to paint on row [rowIndex]'s [side], or null when no search is running over this
 * list (or this cell has none). Reads [LocalDiffSearch], which the enclosing [DiffListScaffold]
 * provides — so a row only has to know its own index to join in.
 */
@Composable
internal fun findHitsFor(rowIndex: Int, side: DiffSide): LineFindHits? {
    val search = LocalDiffSearch.current
    if (search == null || rowIndex < 0) return null
    val ranges = search.rangesFor(rowIndex, side)
    if (ranges.isEmpty()) return null
    return LineFindHits(
        ranges = ranges,
        active = search.activeRangeFor(rowIndex, side),
        color = findMatchColor(),
        activeColor = findActiveMatchColor(),
    )
}

@Composable
internal fun GutterCell(lineNumber: Int?) {
    // Line numbers sit inside the list-wide SelectionContainer; keep them out of selections so a
    // copied deletion is source text only, no gutter digits.
    DisableSelection {
        BasicText(
            text = lineNumber?.toString()?.padStart(5) ?: "     ",
            style = DIFF_TEXT_STYLE.copy(color = GUTTER_FG),
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

/**
 * One read-only side of a diff line: a gutter cell plus the (optionally inline-highlighted) text.
 *
 * The half participates in the enclosing list-wide SelectionContainer when [selectable] is true —
 * the old (left) side is selectable so deleted text can be copied out, while the new (right) side
 * opts out so a top-to-bottom drag yields only the left column, not interleaved blank/duplicate
 * lines. When [currentFile]/[onResolveAt]/[onJump] are supplied, Ctrl-click resolves a symbol and
 * jumps; pass them only where jump-to-definition makes sense (the working-tree diff).
 *
 * [rowIndex] is this row's position in the list the enclosing scaffold is searching, so the half can
 * pick up its find-bar hits; -1 (the default) means "not searchable".
 */
@Composable
internal fun ReadOnlyDiffHalf(
    side: DiffSide,
    text: String?,
    spans: List<InlineSpan>,
    lineNumber: Int?,
    background: Color,
    inlineHighlight: Color,
    modifier: Modifier = Modifier,
    selectable: Boolean = true,
    rowIndex: Int = -1,
    currentFile: File? = null,
    onResolveAt: ((currentFile: File, text: String, offset: Int) -> JumpTarget?)? = null,
    onJump: ((File, Int) -> Unit)? = null,
) {
    val displayText = text ?: ""
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Same syntax palette the editor uses, so a diff colours identically to the file it compares.
    val tokenize = LocalDiffTokenizer.current
    val palette = if (JewelTheme.isDark) HighlightPalette.Dark else HighlightPalette.Light
    val tokens = remember(displayText, tokenize) { tokenize?.invoke(displayText) ?: emptyList() }
    val find = findHitsFor(rowIndex, side)
    Row(
        modifier = modifier.fillMaxSize().background(background),
        verticalAlignment = Alignment.Top,
    ) {
        GutterCell(lineNumber)
        val jumpModifier = if (currentFile != null && onResolveAt != null && onJump != null) {
            Modifier.ctrlClickJump(
                layoutProvider = { layout },
                textProvider = { displayText },
                currentFile = currentFile,
                onResolveAt = onResolveAt,
                onJump = onJump,
            )
        } else {
            Modifier
        }
        val body = @Composable {
            BasicText(
                text = annotateLine(
                    displayText,
                    spans,
                    inlineHighlight,
                    tokens,
                    if (tokenize != null) palette else null,
                    find,
                ),
                style = DIFF_TEXT_STYLE.copy(color = textColor()),
                softWrap = false,
                onTextLayout = { layout = it },
                modifier = Modifier.diffLineWidth(side).padding(end = LINE_END_PAD).then(jumpModifier),
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight().diffHorizontalScroll(side)) {
            if (selectable) body() else DisableSelection { body() }
        }
    }
}

/**
 * Chrome shared by every diff list: the change-marker lane and vertical scrollbar down the right
 * edge, a horizontal scrollbar under each half, and the drag band over the centre divider that
 * moves the split. [list] is handed the modifier its LazyColumn should wear so the lane's width
 * stays clear of the rows.
 *
 * [ratio] is the share of the row given to the old (left) half; it's hoisted so the caller can
 * persist it, and clamped here against the pane's real width.
 *
 * Ctrl+F opens a find bar over the list — the same one the file editor uses. [findTrigger] is the
 * window-level Ctrl+F counter, [searchKey] identifies the tab so switching diffs doesn't inherit
 * the previous one's query, and [rowToItem] maps an index in [rows] to the LazyColumn item that
 * renders it (they differ when the list interleaves non-row items, as the merge view does).
 */
@Composable
internal fun DiffListScaffold(
    rows: List<DiffRow>,
    kinds: List<RowKind>,
    listState: LazyListState,
    ratio: Float,
    onRatioChange: (Float) -> Unit,
    overrideColor: (Int) -> Color? = { null },
    searchKey: Any = Unit,
    findTrigger: Int = 0,
    rowToItem: (Int) -> Int = { it },
    list: @Composable (Modifier) -> Unit,
) {
    val oldScroll = rememberDiffSideScroll(rows, DiffSide.OLD)
    val newScroll = rememberDiffSideScroll(rows, DiffSide.NEW)
    val density = LocalDensity.current

    // In-diff find state, mirroring FileEditView's: the bar stays open per tab with its own query,
    // matches recompute off (rows, query), and currentMatch is the active hit Next/Prev walks.
    var searchOpen by remember(searchKey) { mutableStateOf(false) }
    // Keyed on the tab like the rest of this bar's state: only the selected tab is composed, so an
    // unkeyed query is handed to whichever diff is shown next instead of starting empty.
    val searchState = remember(searchKey) { TextFieldState() }
    var currentMatch by remember(searchKey) { mutableStateOf(0) }
    val searchFocusRequester = remember(searchKey) { FocusRequester() }
    // Bumped by genuine user find activity — opening the bar, and typing in the field — so the
    // "re-aim at the first match" effect can key off it rather than off the query text.
    var findRevision by remember(searchKey) { mutableStateOf(0) }
    // Snapshot the trigger as of this diff's first composition, so switching to a diff tab doesn't
    // inherit whatever Ctrl+F count a sibling tab racked up and pop the bar open unasked.
    val triggerBaseline = remember(searchKey) { findTrigger }
    LaunchedEffect(findTrigger) {
        if (findTrigger <= triggerBaseline) return@LaunchedEffect
        val wasOpen = searchOpen
        searchOpen = true
        // Wait a frame so the bar's field is composed and its FocusRequester attached — requesting
        // focus before it joins the focus tree throws and the keystrokes go to the old focus owner.
        withFrameNanos { }
        runCatching { searchFocusRequester.requestFocus() }
        val len = searchState.text.length
        if (len > 0) searchState.edit { selection = TextRange(0, len) }
        // Only aim at the first match when the bar opens fresh — re-pressing Ctrl+F on an open bar
        // just re-selects the query and leaves the user where they are.
        if (!wasOpen) findRevision++
    }

    val matches by remember(rows) {
        derivedStateOf {
            val q = searchState.text.toString()
            if (!searchOpen || q.isEmpty()) emptyList() else findDiffMatches(rows, q)
        }
    }
    LaunchedEffect(matches.size) {
        if (currentMatch >= matches.size) currentMatch = 0
    }
    val byCell = remember(matches) { groupDiffMatches(matches) }
    val search = remember(byCell, matches, currentMatch) {
        if (matches.isEmpty()) null else DiffSearch(byCell, activeDiffCells(matches.getOrNull(currentMatch)))
    }

    // Bring a hit into view in both axes, and only when it isn't already: stepping between two
    // matches already on screen shouldn't jolt the page. Horizontal position comes from the
    // monospace advance — a match's char offset is its x, no text layout needed.
    val searchScope = rememberCoroutineScope()
    suspend fun revealMatch(index: Int) {
        val m = matches.getOrNull(index) ?: return
        val item = rowToItem(m.rowIndex)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == item }) {
            listState.scrollToItem((item - CONTEXT_ROWS).coerceAtLeast(0))
        }
        val sideScroll = if (m.side == DiffSide.OLD) oldScroll else newScroll
        val viewport = sideScroll.state.viewportSize
        if (viewport <= 0) return
        with(density) {
            val advance = sideScroll.charWidth.toPx()
            val left = m.range.first * advance
            val right = (m.range.last + 1) * advance
            val viewLeft = sideScroll.state.value
            if (left < viewLeft || right > viewLeft + viewport) {
                sideScroll.state.scrollTo((left - viewport / 3f).toInt().coerceIn(0, sideScroll.state.maxValue))
            }
        }
    }
    // Re-aim at the first match on genuine find activity (opening the bar, typing a query). Keyed
    // on the revision counter rather than the query so a re-diff underneath never moves the user.
    LaunchedEffect(findRevision) {
        if (!searchOpen || findRevision == 0) return@LaunchedEffect
        currentMatch = 0
        revealMatch(0)
    }
    fun step(delta: Int) {
        if (matches.isEmpty()) return
        val next = (currentMatch + delta + matches.size) % matches.size
        currentMatch = next
        searchScope.launch { revealMatch(next) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Rows are inset by the marker lane and vertical scrollbar, so the split divides what's
        // left of the width, not the whole pane — otherwise the drag band and the rows' own
        // hairline would drift apart as the lane's share of a narrow pane grows.
        val lanePx = with(density) { (MARKER_LANE_W + SCROLLBAR_W + DIVIDER_W).toPx() }
        val available = (constraints.maxWidth - lanePx).coerceAtLeast(1f)
        val minRatio = (with(density) { MIN_HALF_W.toPx() } / available).coerceIn(0f, 1f)
        val maxRatio = (1f - minRatio).coerceIn(minRatio, 1f)
        val clamped = ratio.coerceIn(minRatio, maxRatio)
        val oldWidth = with(density) { (available * clamped).toDp() }

        Column(modifier = Modifier.fillMaxSize()) {
            if (searchOpen) {
                FindBar(
                    state = searchState,
                    focusRequester = searchFocusRequester,
                    matchCount = matches.size,
                    currentIndex = currentMatch,
                    onUserEdit = { findRevision++ },
                    onNext = { step(1) },
                    onPrev = { step(-1) },
                    onClose = { searchOpen = false },
                )
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                CompositionLocalProvider(
                    LocalDiffLayout provides DiffLayout(oldWidth, oldScroll, newScroll),
                    LocalDiffSearch provides search,
                ) {
                    list(Modifier.fillMaxSize().padding(end = MARKER_LANE_W + SCROLLBAR_W))
                }
                ChangeMarkerLane(kinds, listState, overrideColor)
                DividerGrabBand(
                    dividerX = oldWidth,
                    onDrag = { deltaPx ->
                        onRatioChange(((clamped * available + deltaPx) / available).coerceIn(minRatio, maxRatio))
                    },
                )
            }
            SideScrollbars(oldWidth, oldScroll, newScroll)
        }
    }
}

/** Rows of context left above a match when find has to scroll it into view. */
private const val CONTEXT_ROWS = 3

/**
 * The invisible band you grab to move the split. It's wider than the hairline it sits on so the
 * divider is catchable, and — like [SplitPane]'s dividers — starts dragging on the first press:
 * pointer events stop arriving once the cursor leaves the band, which it would while covering
 * Compose's drag slop.
 */
@Composable
private fun BoxScope.DividerGrabBand(dividerX: Dp, onDrag: (Float) -> Unit) {
    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset(x = dividerX - (DIVIDER_GRAB_W - DIVIDER_W) / 2)
            .width(DIVIDER_GRAB_W)
            .fillMaxHeight()
            .pointerHoverIcon(HorizontalResizeCursor)
            .draggable(
                state = rememberDraggableState(onDelta = onDrag),
                orientation = Orientation.Horizontal,
                startDragImmediately = true,
            ),
    )
}

/**
 * A horizontal scrollbar under each half, each one sized and positioned to the half it scrolls.
 * The strip is claimed only when a line actually overflows, so ordinary diffs keep the full height;
 * viewportSize gates on the rows having been laid out at least once, since a fresh ScrollState
 * reports Int.MAX_VALUE and every diff would otherwise flash a bar on its first frame.
 */
@Composable
private fun SideScrollbars(oldWidth: Dp, old: DiffSideScroll, new: DiffSideScroll) {
    val showOld = old.state.viewportSize > 0 && old.state.maxValue > 0
    val showNew = new.state.viewportSize > 0 && new.state.maxValue > 0
    if (!showOld && !showNew) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = MARKER_LANE_W + SCROLLBAR_W)
            .height(SCROLLBAR_W),
    ) {
        Box(Modifier.width(oldWidth).fillMaxHeight()) {
            if (showOld) {
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(old.state),
                    style = NopScrollbarStyle,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Box(Modifier.width(DIVIDER_W).fillMaxHeight())
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (showNew) {
                HorizontalScrollbar(
                    adapter = rememberScrollbarAdapter(new.state),
                    style = NopScrollbarStyle,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Ctrl-click on a word inside this widget calls [onResolveAt]; on a hit, [onJump] is invoked
 * with the resolved file/line. The event is consumed on the Initial pass so the host's text
 * field (when one exists) doesn't move the caret in response to the same click.
 */
internal fun Modifier.ctrlClickJump(
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

/**
 * The change-marker lane plus scrollbar pinned to the right edge of a diff list. [overrideColor]
 * lets a caller tint specific rows (e.g. conflict-control rows) regardless of their [RowKind].
 */
@Composable
internal fun BoxScope.ChangeMarkerLane(
    kinds: List<RowKind>,
    listState: LazyListState,
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
