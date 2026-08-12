package iondrive.nop.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * Whether long lines soft-wrap in the views that show file content — the editor tabs and both
 * side-by-side diffs. Toggled by the button beside the tab strip's "+" and persisted globally (see
 * [iondrive.nop.Settings.loadWrapLines]).
 *
 * It rides a composition local rather than a parameter because the surfaces that have to honour it
 * are several layers down (a diff row's half, the gutter beside it, the scrollbars under it) and
 * every one of them already reads its layout context this way — see [LocalDiffLayout].
 *
 * Off — the default — every line is laid out at its full length and the view scrolls sideways:
 * one text line is one row, which is the invariant the diffs' line grid, line numbers and row tints
 * are built on. On, a line takes as many rows as it needs to fit the width it's given, so two diff
 * halves that both carry text can no longer be laid out a fixed step apart; [iondrive.nop.ui.diffBlocks]
 * drops those rows to one per list item, each free to be as tall as the taller of its two sides.
 * Runs that are blank down one side — added lines, deleted lines — keep their grouping either way,
 * which is what keeps the working side of a diff editable as text rather than a line at a time.
 */
internal val LocalWrapLines = compositionLocalOf { false }
