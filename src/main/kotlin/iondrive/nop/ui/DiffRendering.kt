package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.diff.DiffRow
import iondrive.nop.diff.InlineSpan
import iondrive.nop.diff.RowKind
import iondrive.nop.index.JumpTarget
import java.io.File
import org.jetbrains.jewel.foundation.theme.JewelTheme

// Shared building blocks for every side-by-side diff renderer (the working-tree [DiffView] and the
// commit-history [CommitDiffView]). Keeping the read-only half, gutter, colours, selection rules
// and change-marker lane in one place means a change like "darker font" or "selectable text"
// applies to both views at once instead of having to be copied between near-identical files.

// Background tints — muted dark-theme palette.
internal val INSERT_BG = Color(0x33629755) // green
internal val DELETE_BG = Color(0x33B35E5E) // red
internal val CHANGE_BG = Color(0x33547B9D) // blue
internal val EMPTY_BG = Color(0x14FFFFFF)  // subtle gray (filler for missing side)
internal val INLINE_WORD_BG = Color(0x66629755)
internal val INLINE_WORD_BG_OLD = Color(0x66B35E5E)
internal val GUTTER_FG = Color(0xFF808080)

// Saturated marker colours for the scrollbar lane — must read at a glance on the dark panel.
internal val INSERT_MARK = Color(0xFF7DBE6E)
internal val DELETE_MARK = Color(0xFFD96B6B)
internal val CHANGE_MARK = Color(0xFF6FA8DC)

internal val MARKER_LANE_W = 4.dp
internal val SCROLLBAR_W = 10.dp
internal val IntrinsicMinHeightLine = 18.dp

// The tokenizer for the file a diff is showing, provided by [DiffView]/[CommitDiffView] and read by
// the diff halves so the comparison views get the same syntax colouring (and italic comments) as
// the editor tabs. null means "no highlighting for this file type" — render plain. Lines are
// tokenized individually; a block comment that spans lines only highlights the lines that carry its
// delimiters, which is an acceptable approximation for a read-only diff.
internal val LocalDiffTokenizer = compositionLocalOf<((String) -> List<Token>)?> { null }

/** Body text colour: light grey on the dark theme, near-black on the light theme for contrast. */
@Composable
internal fun textColor(): Color =
    if (JewelTheme.isDark) Color(0xFFA9B7C6) else Color(0xFF000000)

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
): AnnotatedString {
    val hasSyntax = palette != null && tokens.isNotEmpty()
    if (spans.isEmpty() && !hasSyntax) return AnnotatedString(text)
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
    }
}

@Composable
internal fun GutterCell(lineNumber: Int?) {
    // Line numbers sit inside the list-wide SelectionContainer; keep them out of selections so a
    // copied deletion is source text only, no gutter digits.
    DisableSelection {
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
}

/**
 * One read-only side of a diff line: a gutter cell plus the (optionally inline-highlighted) text.
 *
 * The half participates in the enclosing list-wide SelectionContainer when [selectable] is true —
 * the old (left) side is selectable so deleted text can be copied out, while the new (right) side
 * opts out so a top-to-bottom drag yields only the left column, not interleaved blank/duplicate
 * lines. When [currentFile]/[onResolveAt]/[onJump] are supplied, Ctrl-click resolves a symbol and
 * jumps; pass them only where jump-to-definition makes sense (the working-tree diff).
 */
@Composable
internal fun ReadOnlyDiffHalf(
    text: String?,
    spans: List<InlineSpan>,
    lineNumber: Int?,
    background: Color,
    inlineHighlight: Color,
    modifier: Modifier = Modifier,
    selectable: Boolean = true,
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
                ),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = textColor(),
                ),
                softWrap = false,
                onTextLayout = { layout = it },
                modifier = Modifier.padding(end = 4.dp).then(jumpModifier),
            )
        }
        if (selectable) body() else DisableSelection { body() }
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
