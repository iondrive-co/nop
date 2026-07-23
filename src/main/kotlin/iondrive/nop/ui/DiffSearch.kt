package iondrive.nop.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import iondrive.nop.diff.DiffRow
import iondrive.nop.diff.RowKind

// Find-in-diff, the side-by-side counterpart to the editor's find-in-file. A diff isn't one text
// buffer but a list of rows each holding two independent lines, so matches are located by (row,
// side, char range) rather than by a single document offset. Everything here is pure so the search
// and its ordering can be unit-tested without composing a diff.

/** Highest number of hits a single diff search collects, so a one-character query can't run away. */
private const val DIFF_MATCH_CAP = 5000

/** One occurrence of the find query inside a diff. [range] is closed, in chars of that line. */
internal data class DiffMatch(
    val rowIndex: Int,
    val side: DiffSide,
    val range: IntRange,
    /**
     * True when the row's two halves hold identical text, so this one hit is on screen twice and
     * should be highlighted on both sides. See [findDiffMatches] for why it isn't two matches.
     */
    val mirrored: Boolean = false,
)

/**
 * Every occurrence of [query] in [rows], case-insensitive, in reading order: down the rows, and
 * within a row the old (left) side before the new (right) one.
 *
 * An EQUAL row's halves are the same text by definition, so its hits are collected once and marked
 * [DiffMatch.mirrored] instead of being found twice. Otherwise Next would stop on every unchanged
 * line twice — once per pane — for what the user sees as a single occurrence, and the "n of m"
 * count would roughly double on a diff that's mostly context.
 */
internal fun findDiffMatches(rows: List<DiffRow>, query: String): List<DiffMatch> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<DiffMatch>()
    rows.forEachIndexed { rowIndex, row ->
        val mirrored = row.kind == RowKind.EQUAL
        val sides = if (mirrored) listOf(DiffSide.NEW) else listOf(DiffSide.OLD, DiffSide.NEW)
        for (side in sides) {
            val line = (if (side == DiffSide.OLD) row.oldLine else row.newLine) ?: continue
            for (range in findAllMatches(line, query)) {
                out += DiffMatch(rowIndex, side, range, mirrored)
                if (out.size >= DIFF_MATCH_CAP) return out
            }
        }
    }
    return out
}

/**
 * Which line cells a match paints. A mirrored hit (see [findDiffMatches]) lights up both halves of
 * its row; anything else only the side it was found on.
 */
internal fun DiffMatch.cells(): List<Long> =
    if (mirrored) listOf(diffCellKey(rowIndex, DiffSide.OLD), diffCellKey(rowIndex, DiffSide.NEW))
    else listOf(diffCellKey(rowIndex, side))

/** Identity of one line cell — a row plus which half of it — packed for use as a map key. */
internal fun diffCellKey(rowIndex: Int, side: DiffSide): Long =
    rowIndex.toLong() * 2 + side.ordinal

/** Groups [matches] by the cells they paint, so a row can look its own hits up in one map read. */
internal fun groupDiffMatches(matches: List<DiffMatch>): Map<Long, List<IntRange>> {
    if (matches.isEmpty()) return emptyMap()
    val out = HashMap<Long, MutableList<IntRange>>()
    for (m in matches) {
        for (cell in m.cells()) out.getOrPut(cell) { ArrayList() }.add(m.range)
    }
    return out
}

/**
 * The live find state a diff list hands down to its rows: where every hit is, and which one is
 * active. Rows read it out of [LocalDiffSearch] rather than taking it as a parameter, so adding
 * search didn't have to thread highlight arguments through every diff row composable.
 */
internal class DiffSearch(
    private val byCell: Map<Long, List<IntRange>>,
    private val activeByCell: Map<Long, IntRange>,
) {
    fun rangesFor(rowIndex: Int, side: DiffSide): List<IntRange> =
        byCell[diffCellKey(rowIndex, side)] ?: emptyList()

    fun activeRangeFor(rowIndex: Int, side: DiffSide): IntRange? =
        activeByCell[diffCellKey(rowIndex, side)]
}

internal val LocalDiffSearch = compositionLocalOf<DiffSearch?> { null }

/** The cells [active] paints in the active-match colour, keyed the same way as [groupDiffMatches]. */
internal fun activeDiffCells(active: DiffMatch?): Map<Long, IntRange> =
    active?.cells()?.associateWith { active.range } ?: emptyMap()

/**
 * Find hits to paint on one diff line, resolved against the current theme. Row composables get
 * this from [findHitsFor]; a null means nothing to paint, which is the common case.
 */
internal data class LineFindHits(
    val ranges: List<IntRange>,
    val active: IntRange?,
    val color: Color,
    val activeColor: Color,
)
