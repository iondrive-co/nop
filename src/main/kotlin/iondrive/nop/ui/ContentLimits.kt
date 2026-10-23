package iondrive.nop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import iondrive.nop.diff.DiffRow

/**
 * The limits a view of file content lays itself out within, whatever the file holds.
 *
 * A view sized off its content — a line cell as wide as the longest line, an editor as wide as the
 * file — hands that size to Compose, and Compose has hard limits: a measurement's constraints are
 * packed into one Long, so a row's small height leaves the width 18 bits, and a cell past 262142px
 * throws from measure. That took nop down on a 70k-character line in a diff, and kept it down,
 * because the tab came back on every launch. Text engines have softer limits of their own: folding a
 * million-character line to the window's width took over a minute of the UI thread.
 *
 * So every content-derived size goes through here, and the file decides only how much of the view's
 * extent it fills — never whether the view can be laid out.
 */

/**
 * The widest a view of file content may be, in px. Comfortably inside Compose's 262142px, which a
 * HiDPI screen reaches at half the characters a 1x one does. Past this a line is clipped: the view
 * scrolls to here and no further.
 */
internal const val MAX_CONTENT_PX = 200_000f

/**
 * The longest line that is still folded to the window when wrapping is on. Longer, and the view
 * stays unwrapped and scrolls sideways instead: wrapping a 1M-character line hung the UI thread for
 * over a minute, while 70k folded in well under a frame's worth of work.
 */
internal const val MAX_WRAP_LINE_CHARS = 50_000

/** [width], held to [MAX_CONTENT_PX]. */
internal fun Density.capContentWidth(width: Dp): Dp = minOf(width, MAX_CONTENT_PX.toDp())

/** Whether a view whose longest line is [longest] characters may wrap. */
internal fun wrapsSafely(longest: Int): Boolean = longest <= MAX_WRAP_LINE_CHARS

/**
 * Turns wrapping off under [content] when [rows] holds a line too long to wrap — see
 * [MAX_WRAP_LINE_CHARS]. Always provides, rather than only when it has to, so the content keeps its
 * place in the composition (and its scroll and selection) when an edit takes a line over the limit.
 */
@Composable
internal fun WrapWithinLimits(rows: List<DiffRow>, content: @Composable () -> Unit) {
    val safe = remember(rows) {
        wrapsSafely(maxOf(maxDiffLineLength(rows, DiffSide.OLD), maxDiffLineLength(rows, DiffSide.NEW)))
    }
    CompositionLocalProvider(LocalWrapLines provides (LocalWrapLines.current && safe), content = content)
}
