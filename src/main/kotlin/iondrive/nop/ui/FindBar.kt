package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

// The in-place find bar, shared by every surface that can be searched: the file editor
// (FileEditView) and the side-by-side diffs (DiffListScaffold). Keeping one implementation is what
// makes Ctrl+F feel the same wherever the caret happens to be — same key handling, same "n of m"
// chip, same highlight colours.

/**
 * Background behind every hit of the current query. Amber, deliberately: green and red are spoken
 * for by the diff views' insert/delete tints, and a green highlight on a diff line reads as "this
 * line was added" rather than "your search matched here".
 */
@Composable
internal fun findMatchColor(): Color =
    if (JewelTheme.isDark) Color(0xFF5A4A20) else Color(0xFFFFF38C)

/** Background behind the active hit — the one Next/Prev is parked on — so it stands out from the rest. */
@Composable
internal fun findActiveMatchColor(): Color =
    if (JewelTheme.isDark) Color(0xFF8A6D1A) else Color(0xFFFFB74D)

// Saturated lane colours for the find markers, chosen like the diff's change marks: the tints used
// behind the text itself are too washed out to read as a 3px stripe, so the lane gets its own,
// louder pair. Amber for a hit, a hotter orange for the one Next/Prev is parked on, so the user can
// pick their position out of a column of hits. Fixed rather than per-theme — both read against the
// light and the dark editor background, and the lane is chrome, not content.
internal val FIND_MARK = Color(0xFFD9A441)
internal val FIND_ACTIVE_MARK = Color(0xFFFF7A1A)

/**
 * The lane colour for a syntax error, picked to sit clearly apart from the amber find marks — a
 * stripe here means "this file is broken here", which is a different question from "this is what you
 * searched for". Fixed across themes for the same reason the pair above is.
 */
internal val PROBLEM_MARK = Color(0xFFE05252)

/** How tall one hit's stripe is drawn in the lane, whatever share of the file the line really is. */
private val FIND_MARK_H = 3.dp

/** A find marker as drawn in the scrollbar lane: how far down it starts, and how tall it is (px). */
internal data class MarkerBar(val top: Float, val height: Float)

/**
 * Places one stripe per hit down a lane [laneHeight] px tall. [fractions] are where each hit sits in
 * the document as a share of its full height, in document order.
 *
 * [markerHeight] is a floor as much as a size: one line's true share of a long file is a fraction of
 * a pixel, so a marker scaled to the text it points at would be invisible. Bars are clamped to the
 * lane, so the last hit in a file lands inside the track instead of half off the bottom, and hits
 * that round onto the same pixel row collapse to one — a 5000-hit query would otherwise redraw
 * thousands of identical rects every frame to no visible effect.
 */
internal fun markerBars(fractions: List<Float>, laneHeight: Float, markerHeight: Float): List<MarkerBar> {
    if (laneHeight <= 0f || fractions.isEmpty()) return emptyList()
    val h = markerHeight.coerceIn(1f, laneHeight)
    val out = mutableListOf<MarkerBar>()
    var lastTop = 0f
    for (f in fractions) {
        val top = (f.coerceIn(0f, 1f) * laneHeight).coerceIn(0f, laneHeight - h)
        if (out.isNotEmpty() && top - lastTop < 1f) continue
        out += MarkerBar(top, h)
        lastTop = top
    }
    return out
}

/**
 * The vertical scrollbar for a searchable text surface, with a find-marker lane down its left the
 * way the diff views carry their change markers: every hit of the current query gets a stripe at the
 * point in the file it sits at, so a query's spread through the document reads at a glance instead
 * of only through the "n of m" chip.
 *
 * [fractions] is one 0..1 document position per hit and [current] indexes the active one, which is
 * drawn last (and in the louder colour) so it is never buried under the hits around it.
 *
 * The lane is [MARKER_LANE_W] wide and the scrollbar [SCROLLBAR_W], matching the diff's lane exactly
 * — together they fit the gap the editor already leaves to the right of its text, so markers appear
 * and disappear with the find bar without ever moving the text or the scrollbar.
 */
@Composable
internal fun BoxScope.FindMarkerScrollbar(
    scrollState: ScrollState,
    fractions: List<Float>,
    current: Int,
    /**
     * Where the file's syntax errors sit down the document, 0..1 — drawn in the error colour beside
     * the find marks. This is what makes a broken line below the fold visible: without it, the only
     * evidence of an error twelve screens down is that the file doesn't compile.
     */
    problemFractions: List<Float> = emptyList(),
) {
    val markerH = with(LocalDensity.current) { FIND_MARK_H.toPx() }
    Row(
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(MARKER_LANE_W).fillMaxHeight()) {
            // Errors first, so a find hit landing on the same pixel row still reads as a hit —
            // the user is actively looking for those, and the error isn't going anywhere.
            for (bar in markerBars(problemFractions, size.height, markerH)) {
                drawRect(PROBLEM_MARK, Offset(0f, bar.top), Size(size.width, bar.height))
            }
            if (fractions.isEmpty()) return@Canvas
            for (bar in markerBars(fractions, size.height, markerH)) {
                drawRect(FIND_MARK, Offset(0f, bar.top), Size(size.width, bar.height))
            }
            // Drawn on its own rather than picked out of the deduped list above: the active hit has
            // to show even when a neighbouring one rounds onto the same pixel row and swallowed it.
            val active = fractions.getOrNull(current) ?: return@Canvas
            val activeBar = markerBars(listOf(active), size.height, markerH).firstOrNull() ?: return@Canvas
            drawRect(FIND_ACTIVE_MARK, Offset(0f, activeBar.top), Size(size.width, activeBar.height))
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            style = NopScrollbarStyle,
            modifier = Modifier.width(SCROLLBAR_W).fillMaxHeight(),
        )
    }
}

/**
 * The replace half of the bar, or null for a find-only bar.
 *
 * Passing it splits the bar's single query field into two equal halves — the query on the left, the
 * text it will be replaced with on the right — and adds the buttons that apply it. Surfaces that
 * can't write back to what they're showing (both diff views) pass null and are unaffected.
 */
internal class ReplaceFields(
    val state: TextFieldState,
    val focusRequester: FocusRequester,
    /** Replace the hit the user is parked on, then move to the next one. */
    val onReplace: () -> Unit,
    /** Replace every hit of the current query in one edit. */
    val onReplaceAll: () -> Unit,
)

/**
 * Slim search bar pinned above the content being searched. Enter / Shift+Enter cycle through
 * matches; Esc closes the bar and clears the highlight. The count chip reads "n of m" so the user
 * can see both their position and how many total matches exist for the current query.
 *
 * With [replace] supplied the bar also does find-and-replace: Enter from the replacement field
 * replaces the current hit, Ctrl+Enter replaces them all, and both are also plain buttons so the
 * feature is discoverable without knowing the keys.
 */
@Composable
internal fun FindBar(
    state: TextFieldState,
    focusRequester: FocusRequester,
    matchCount: Int,
    currentIndex: Int,
    onUserEdit: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
    replace: ReplaceFields? = null,
) {
    val isDark = JewelTheme.isDark
    // The bar sits above the content it searches, so it takes the panel-chrome background rather
    // than the editor's — it should read as a strip of chrome, not as another line of the file.
    val barBg = if (isDark) Color(0xFF1E1F22) else Color(0xFFF7F8FA)
    val fg = if (isDark) Color(0xFFBCBEC4) else Color(0xFF000000)
    val mutedFg = if (isDark) Color(0xFF6F737A) else Color(0xFF6B7280)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(barBg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            // Only Escape is handled bar-wide. Enter belongs to whichever field has focus — it steps
            // through matches from the query side and replaces from the replacement side — and a
            // preview handler here would swallow it before either field could tell them apart.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.Escape) {
                    onClose()
                    true
                } else {
                    false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            state = state,
            // Fires only for genuine user input, never for programmatic state.edit {} — so a
            // global-search seed that sets the query doesn't count as the user retyping it.
            inputTransformation = InputTransformation { onUserEdit() },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            if (event.isShiftPressed) onPrev() else onNext()
                            true
                        }
                        else -> false
                    }
                },
            textStyle = TextStyle(fontFamily = NopFonts.Mono, fontSize = 13.sp, color = fg),
            cursorBrush = SolidColor(fg),
            lineLimits = TextFieldLineLimits.SingleLine,
        )
        if (replace != null) {
            // The arrow reads the split as "find → replace with", which is the only label the two
            // otherwise-identical halves get.
            Text("→", color = mutedFg, style = TextStyle(fontSize = 12.sp))
            BasicTextField(
                state = replace.state,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(replace.focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                if (event.isCtrlPressed) replace.onReplaceAll() else replace.onReplace()
                                true
                            }
                            else -> false
                        }
                    },
                textStyle = TextStyle(fontFamily = NopFonts.Mono, fontSize = 13.sp, color = fg),
                cursorBrush = SolidColor(fg),
                lineLimits = TextFieldLineLimits.SingleLine,
            )
            val actionFg = if (matchCount > 0) fg else mutedFg
            BarAction("Replace", actionFg, replace.onReplace)
            BarAction("All", actionFg, replace.onReplaceAll)
        }
        val countText = when {
            state.text.isEmpty() -> ""
            matchCount == 0 -> "no matches"
            else -> "${currentIndex + 1} of $matchCount"
        }
        if (countText.isNotEmpty()) {
            Text(countText, color = mutedFg, style = TextStyle(fontSize = 12.sp))
        }
        BarAction("▲", if (matchCount > 0) fg else mutedFg, onPrev)
        BarAction("▼", if (matchCount > 0) fg else mutedFg, onNext)
        BarAction("×", mutedFg, onClose, horizontalPadding = 4.dp)
    }
}

/**
 * A clickable label in the bar. Driven off the raw pointer Press rather than `clickable` so it
 * behaves the same whether the user's focus is in either field — the bar's controls never take
 * focus away from what they're typing into.
 */
@Composable
private fun BarAction(
    label: String,
    color: Color,
    onClick: () -> Unit,
    horizontalPadding: Dp = 2.dp,
) {
    Text(
        label,
        color = color,
        modifier = Modifier
            .padding(horizontal = horizontalPadding)
            .pointerInput(onClick) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Press) onClick()
                    }
                }
            },
    )
}
