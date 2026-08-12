package iondrive.nop.ui

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import iondrive.nop.spell.Dictionary
import iondrive.nop.spell.Typo
import iondrive.nop.spell.suggestionsFor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.ContextSubmenu

/**
 * The parts of the spellchecker every text surface shares: whether it is on for what's being shown,
 * what colour its underline is, and how to draw one.
 *
 * The editor computes its typos in the background and keeps them in state (see TabbedViewerPanel);
 * the diffs compute theirs during composition, because a diff renders one small block of lines at a
 * time and only the ones on screen. Both end up here to draw.
 */

/**
 * The extension of the file the enclosing view is showing, or null when spellcheck is off for it —
 * the toggle is off, or it isn't showing a file at all. Provided once per tab, so every text surface
 * under it (both halves of a diff included) inherits the same answer.
 */
internal val LocalSpellcheckExtension = compositionLocalOf<String?> { null }

/**
 * Bumped whenever a word is added to the dictionary. Views fold it into the keys they cache typos
 * under, so accepting a word in the editor also clears it from an open diff — the dictionary itself
 * is a plain object with no way to tell Compose it changed.
 */
internal object SpellcheckRevision {
    var value by mutableStateOf(0)
        private set

    fun bump() {
        value++
    }
}

/**
 * The spellchecker's underline colour: green, so it can't be mistaken for the red one a syntax
 * error draws — a misspelled word in a comment is a remark, not a broken file. Muted enough on both
 * themes that a paragraph of prose with several typos doesn't turn into a light show.
 */
@Composable
internal fun typoSquiggleColor(): Color =
    if (JewelTheme.isDark) Color(0xFF6E9A5E) else Color(0xFF3F7A32)

/**
 * The typos in [lines] (joined by newlines), or nothing when spellcheck is off for this view.
 *
 * Cached against the lines themselves, so a recomposition that doesn't change the text — a scroll,
 * a find hit, a theme flip — re-uses the answer. [tokenize] decides which parts of each line are
 * prose; pass the same tokenizer the view highlights with.
 */
@Composable
internal fun rememberTypos(lines: List<String>, tokenize: ((String) -> List<Token>)?): List<Typo> {
    val ext = LocalSpellcheckExtension.current ?: return emptyList()
    val revision = SpellcheckRevision.value
    return remember(lines, tokenize, ext, revision) { typosInLines(lines, ext, tokenize) }
}

/**
 * Underlines [typos] beneath the text this modifier is applied to, which must be laid out as
 * [layout] reports. Drawn over the content rather than under it so the squiggle stays visible on a
 * diff row that already has a background tint.
 *
 * Apply *after* any padding in the chain, so the coordinates line up with the text rather than with
 * the space around it. With nothing to draw the modifier adds no node at all.
 */
internal fun Modifier.spellcheckSquiggles(
    typos: List<Typo>,
    color: Color,
    layout: () -> TextLayoutResult?,
): Modifier {
    if (typos.isEmpty()) return this
    return drawWithContent {
        drawContent()
        val tl = layout() ?: return@drawWithContent
        val length = tl.layoutInput.text.length
        for (typo in typos) {
            drawSquiggle(tl, typo.range.first, typo.range.last + 1, length, 0f, color)
        }
    }
}

/**
 * The spellchecker's part of a right-click menu over [typo]: what to replace the word with, and the
 * option to accept it instead. Empty when the click didn't land on an underlined word, so a menu
 * over ordinary text is unchanged.
 *
 * The corrections hang off a submenu rather than sitting in the main menu: there can be eight of
 * them, and they would otherwise push the actions the user came for (cut, copy, paste) off the
 * bottom of a menu that is different every time. The list is built by the submenu's own lambda,
 * which the menu only calls when it opens the submenu — so a right-click that was after "paste"
 * never pays for a dictionary search.
 */
internal fun spellingMenuItems(typo: Typo?, onReplace: (Typo, String) -> Unit): List<ContextMenuItem> {
    if (typo == null) return emptyList()
    return listOf(
        ContextSubmenu("Replace With") {
            val suggestions = suggestionsFor(typo.word)
            if (suggestions.isEmpty()) {
                // The submenu is offered before its contents are known, so it has to be able to
                // say "nothing" — an empty popup would read as a broken menu.
                listOf(ContextMenuItem("No suggestions") {})
            } else {
                suggestions.map { suggestion -> ContextMenuItem(suggestion) { onReplace(typo, suggestion) } }
            }
        },
        ContextMenuItem("Add \"${typo.word}\" to dictionary") {
            Dictionary.add(typo.word)
            SpellcheckRevision.bump()
        },
    )
}

/**
 * Swaps [typo] for [replacement] in [state], as one edit so it undoes in one step.
 *
 * The range is re-checked against the text before anything is written: it was computed when the
 * word was last spellchecked, and a menu can outlive an edit made elsewhere in the file (an agent
 * writing to the same buffer, say). If the word isn't where it was, the replacement is dropped
 * rather than applied to whatever moved into its place. [onUserEdit] runs only if it is applied.
 */
internal fun replaceTypo(
    state: TextFieldState,
    typo: Typo,
    replacement: String,
    onUserEdit: () -> Unit = {},
) {
    val text = state.text.toString()
    val start = typo.range.first
    val end = typo.range.last + 1
    if (start < 0 || end > text.length || start >= end) return
    if (text.substring(start, end) != typo.word) return
    onUserEdit()
    state.edit { replace(start, end, replacement) }
}

/**
 * Draws one wavy underline beneath the text between [start] and [endExclusive], in document
 * offsets, for the text laid out as [layout] and scrolled by [scroll] pixels.
 *
 * Ranges that fall outside the text, collapse to nothing, or sit off-screen cost a couple of
 * comparisons and no drawing — with a squiggle per misspelled word, a long file's list is mostly
 * off-screen at any moment and this is the loop that has to stay cheap.
 */
internal fun DrawScope.drawSquiggle(
    layout: TextLayoutResult,
    start: Int,
    endExclusive: Int,
    textLength: Int,
    scroll: Float,
    color: Color,
) {
    val s = start.coerceIn(0, textLength)
    val e = endExclusive.coerceIn(s, textLength)
    if (s >= e) return
    val amplitude = 1.2.dp.toPx()
    val halfPeriod = 2.dp.toPx()
    val strokeWidth = 1.dp.toPx()
    val line = layout.getLineForOffset(s)
    val y = layout.getLineBottom(line) - scroll - strokeWidth
    if (y < -amplitude || y > size.height + amplitude) return // off-screen
    val left = layout.getHorizontalPosition(s, usePrimaryDirection = true)
    val right = layout.getHorizontalPosition(e, usePrimaryDirection = true)
    if (right <= left) return
    val path = Path().apply {
        moveTo(left, y)
        var x = left
        var up = true
        while (x < right) {
            val nextX = (x + halfPeriod).coerceAtMost(right)
            lineTo(nextX, if (up) y - amplitude else y + amplitude)
            x = nextX
            up = !up
        }
    }
    drawPath(path, color, style = Stroke(width = strokeWidth))
}
