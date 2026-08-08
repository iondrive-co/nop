package iondrive.nop.ui

import iondrive.nop.diff.DiffRow
import iondrive.nop.diff.RowKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiffViewTest {

    // --- helpers for building diff rows the way DiffComputer would ----------------------------
    private fun equal(line: String, n: Int) =
        DiffRow(RowKind.EQUAL, line, line, emptyList(), emptyList(), n, n)

    private fun change(old: String, new: String, oldN: Int, newN: Int) =
        DiffRow(RowKind.CHANGE, old, new, emptyList(), emptyList(), oldN, newN)

    private fun insert(new: String, newN: Int) =
        DiffRow(RowKind.INSERT, null, new, emptyList(), emptyList(), null, newN)

    private fun delete(old: String, oldN: Int) =
        DiffRow(RowKind.DELETE, old, null, emptyList(), emptyList(), oldN, null)

    /** Replace the lines a block owns, the way its write-back does, so spans read as edits here. */
    private fun replaceLines(full: String, startLine: Int, count: Int, text: String): String {
        val span = lineSpanOf(full, startLine, count) ?: return full
        return full.substring(0, span.start) + text + full.substring(span.endExclusive)
    }

    @Test
    fun `lineSpanOf covers one line without its newline`() {
        val full = "alpha\nbeta\ngamma\n"
        val span = lineSpanOf(full, 2, 1)!!
        assertEquals("beta", full.substring(span.start, span.endExclusive))
    }

    @Test
    fun `lineSpanOf covers a run of lines and the newlines between them`() {
        val full = "alpha\nbeta\ngamma\n"
        val span = lineSpanOf(full, 1, 2)!!
        assertEquals("alpha\nbeta", full.substring(span.start, span.endExclusive))
    }

    @Test
    fun `lineSpanOf reaches the trailing empty line a final newline produces`() {
        // "a\nb\n" splits to [a, b, ""], so line 3 exists and is empty — replacing lines 1..3 has
        // to be able to drop or keep the file's final newline.
        val full = "a\nb\n"
        val span = lineSpanOf(full, 3, 1)!!
        assertEquals(4, span.start)
        assertEquals(4, span.endExclusive)
    }

    @Test
    fun `lineSpanOf is null past the end and for nonsense input`() {
        val full = "alpha\nbeta"
        assertEquals(null, lineSpanOf(full, 3, 1))
        assertEquals(null, lineSpanOf(full, 2, 2))
        assertEquals(null, lineSpanOf(full, 0, 1))
        assertEquals(null, lineSpanOf(full, 1, 0))
    }

    @Test
    fun `replacing a block's span swaps exactly its lines`() {
        val full = "alpha\nbeta\ngamma\n"
        assertEquals("alpha\nBETA\ngamma\n", replaceLines(full, 2, 1, "BETA"))
        assertEquals("ALPHA\nbeta\ngamma\n", replaceLines(full, 1, 1, "ALPHA"))
        // The file's final newline round-trips: line 4 is the empty trailer and stays put.
        assertEquals(full, replaceLines(full, 1, 3, "alpha\nbeta\ngamma"))
    }

    @Test
    fun `replacing a block's span can grow and shrink the file`() {
        val full = "alpha\nbeta\ngamma\n"
        assertEquals("alpha\nbe\nta\ngamma\n", replaceLines(full, 2, 1, "be\nta"))
        assertEquals("alphabeta\ngamma\n", replaceLines(full, 1, 2, "alphabeta"))
    }

    @Test
    fun `linesAt reads back the lines a block stands for`() {
        val full = "alpha\nbeta\ngamma\n"
        assertEquals("beta\ngamma", linesAt(full, 2, 2))
        assertEquals("alpha", linesAt(full, 1, 1))
        assertEquals(null, linesAt(full, 3, 3))
    }

    @Test
    fun `lineCountOf counts the empty line a trailing newline leaves`() {
        assertEquals(1, lineCountOf(""))
        assertEquals(1, lineCountOf("alpha"))
        assertEquals(2, lineCountOf("alpha\n"))
        assertEquals(3, lineCountOf("alpha\nbeta\n"))
    }

    @Test
    fun `lineIndexOf and lineStartOf locate an offset`() {
        val text = "alpha\nbeta\ngamma"
        assertEquals(0, lineIndexOf(text, 0))
        assertEquals(0, lineIndexOf(text, 5)) // end of "alpha", before the newline
        assertEquals(1, lineIndexOf(text, 6)) // start of "beta"
        assertEquals(2, lineIndexOf(text, text.length))
        assertEquals(0, lineStartOf(text, 0))
        assertEquals(6, lineStartOf(text, 1))
        assertEquals(11, lineStartOf(text, 2))
    }

    @Test
    fun `offsetOfLineCol clamps the column to its own line`() {
        val text = "alpha\nbeta\ngamma"
        assertEquals(8, offsetOfLineCol(text, 1, 2))
        // Int.MAX_VALUE is how "put the caret at the end of that line" is asked for.
        assertEquals(10, offsetOfLineCol(text, 1, Int.MAX_VALUE))
        assertEquals(6, offsetOfLineCol(text, 1, -4))
        assertEquals(text.length, offsetOfLineCol(text, 9, 0))
    }

    @Test
    fun `diffBlocks keeps one contiguous run of working lines together`() {
        // Nothing deleted, so the whole file is one block: selecting and pasting spans all of it.
        val rows = listOf(equal("a", 1), change("b", "B", 2, 2), insert("c", 3))
        assertEquals(listOf(DiffBlock(0..2, editable = true)), diffBlocks(rows, editable = true))
    }

    @Test
    fun `diffBlocks splits where the working side has no line`() {
        // A deleted row has nothing on the working side, so the buffer isn't contiguous across it.
        val rows = listOf(equal("a", 1), delete("b", 2), delete("c", 3), equal("d", 2))
        assertEquals(
            listOf(
                DiffBlock(0..0, editable = true),
                DiffBlock(1..2, editable = false),
                DiffBlock(3..3, editable = true),
            ),
            diffBlocks(rows, editable = true),
        )
    }

    @Test
    fun `diffBlocks caps a long run so a big file stays lazy`() {
        val rows = (1..7).map { equal("line $it", it) }
        assertEquals(
            listOf(
                DiffBlock(0..2, editable = true),
                DiffBlock(3..5, editable = true),
                DiffBlock(6..6, editable = true),
            ),
            diffBlocks(rows, editable = true, maxLines = 3),
        )
    }

    @Test
    fun `diffBlocks marks everything display-only when there is no buffer`() {
        // A removed file has no working copy to edit; the blocks still group, but none is editable.
        val rows = listOf(equal("a", 1), delete("b", 2))
        assertEquals(
            listOf(DiffBlock(0..0, editable = false), DiffBlock(1..1, editable = false)),
            diffBlocks(rows, editable = false),
        )
    }

    @Test
    fun `diffBlocks keeps a boundary at the block holding the caret`() {
        // Typing can make a line pair up with HEAD where it didn't before, closing the gap that
        // separated two blocks. Without the forced split the lower block — the one being typed into
        // — would be absorbed, taking its text field, caret and focus out of the list mid-word.
        val rows = listOf(equal("a", 1), change("b", "B", 2, 2), equal("c", 3))
        assertEquals(listOf(DiffBlock(0..2, editable = true)), diffBlocks(rows, editable = true))
        assertEquals(
            listOf(DiffBlock(0..0, editable = true), DiffBlock(1..2, editable = true)),
            diffBlocks(rows, editable = true, splitAtLine = 2),
        )
    }

    @Test
    fun `diffBlocks ignores a caret line that no longer starts anything`() {
        // The line was deleted, or never had a working side: nothing to pin, so group as usual.
        val rows = listOf(equal("a", 1), equal("b", 2))
        val natural = diffBlocks(rows, editable = true)
        assertEquals(natural, diffBlocks(rows, editable = true, splitAtLine = 99))
        // Deletion rows carry a null line number, which must not be read as "matches null".
        val withDelete = listOf(equal("a", 1), delete("b", 2), delete("c", 3))
        assertEquals(
            listOf(DiffBlock(0..0, editable = true), DiffBlock(1..2, editable = false)),
            diffBlocks(withDelete, editable = true),
        )
    }

    @Test
    fun `diffBlocks is empty for an empty diff`() {
        assertTrue(diffBlocks(emptyList(), editable = true).isEmpty())
    }

    @Test
    fun `hunkRanges is empty when everything matches`() {
        val rows = listOf(equal("a", 1), equal("b", 2))
        assertTrue(hunkRanges(rows).isEmpty())
    }

    @Test
    fun `hunkRanges groups a single change`() {
        val rows = listOf(equal("a", 1), change("b", "B", 2, 2), equal("c", 3))
        assertEquals(listOf(1..1), hunkRanges(rows))
    }

    @Test
    fun `hunkRanges separates two runs and handles edges`() {
        // change at the very start, then equal, then a delete+insert run at the end
        val rows = listOf(
            change("a", "A", 1, 1),
            equal("b", 2),
            delete("c", 3),
            insert("d", 3),
        )
        assertEquals(listOf(0..0, 2..3), hunkRanges(rows))
    }

    @Test
    fun `revertHunk restores HEAD for a changed hunk and keeps the rest`() {
        val rows = listOf(equal("a", 1), change("b", "B", 2, 2), equal("c", 3))
        assertEquals("a\nb\nc\n", revertHunk(rows, 1..1, trailingNewline = true))
        assertEquals("a\nb\nc", revertHunk(rows, 1..1, trailingNewline = false))
    }

    @Test
    fun `revertHunk drops an inserted hunk`() {
        // HEAD: a,c  working: a,b,c — reverting the insert removes b.
        val rows = listOf(equal("a", 1), insert("b", 2), equal("c", 3))
        assertEquals("a\nc\n", revertHunk(rows, 1..1, trailingNewline = true))
    }

    @Test
    fun `revertHunk re-adds a deleted hunk`() {
        // HEAD: a,b,c  working: a,c — reverting the delete brings b back.
        val rows = listOf(equal("a", 1), delete("b", 2), equal("c", 3))
        assertEquals("a\nb\nc\n", revertHunk(rows, 1..1, trailingNewline = true))
    }

    @Test
    fun `applyStructuralEdit merges a line into the one above`() {
        val (text, focus) = applyStructuralEdit("alpha\nbeta\ngamma", 2, StructuralEdit.MERGE_PREV)!!
        assertEquals("alphabeta\ngamma", text)
        assertEquals(1 to 5, focus)
    }

    @Test
    fun `applyStructuralEdit merge-prev is a no-op on the first line`() {
        assertEquals(null, applyStructuralEdit("alpha\nbeta", 1, StructuralEdit.MERGE_PREV))
    }

    @Test
    fun `applyStructuralEdit pulls the next line up`() {
        val (text, focus) = applyStructuralEdit("alpha\nbeta\ngamma", 1, StructuralEdit.MERGE_NEXT)!!
        assertEquals("alphabeta\ngamma", text)
        assertEquals(1 to 5, focus)
    }

    @Test
    fun `applyStructuralEdit merge-next on the last real line drops the trailing newline`() {
        // "a\nb\n" splits to [a, b, ""]; Delete at the end of "b" joins the empty trailer onto it.
        val (text, focus) = applyStructuralEdit("a\nb\n", 2, StructuralEdit.MERGE_NEXT)!!
        assertEquals("a\nb", text)
        assertEquals(2 to 1, focus)
    }

    @Test
    fun `applyStructuralEdit merge-next is a no-op past the end`() {
        assertEquals(null, applyStructuralEdit("alpha\nbeta", 2, StructuralEdit.MERGE_NEXT))
    }

    @Test
    fun `applyStructuralEdit ignores out-of-range lines`() {
        assertEquals(null, applyStructuralEdit("alpha", 0, StructuralEdit.MERGE_PREV))
        assertEquals(null, applyStructuralEdit("alpha", 9, StructuralEdit.MERGE_NEXT))
    }

    @Test
    fun `merging across a block boundary joins the lines the diff separated`() {
        // HEAD had a line the working file doesn't, so the diff splits these lines into two blocks.
        // Backspace at the top of the lower one still has to join it onto the line above it in the
        // *file* — the deleted line isn't there to get in the way.
        val rows = listOf(equal("keep", 1), delete("gone", 2), equal("next", 2))
        val blocks = diffBlocks(rows, editable = true)
        assertEquals(3, blocks.size)
        val lowerBlockStart = rows[blocks[2].range.first].newLineNumber!!
        val (text, focus) = applyStructuralEdit("keep\nnext\n", lowerBlockStart, StructuralEdit.MERGE_PREV)!!
        assertEquals("keepnext\n", text)
        assertEquals(1 to 4, focus)
    }

    @Test
    fun `revertHunk touches only the targeted hunk`() {
        // Two changed hunks; reverting the first leaves the second's working content intact.
        val rows = listOf(
            equal("a", 1),
            change("b", "B", 2, 2),
            equal("c", 3),
            change("d", "D", 4, 4),
            equal("e", 5),
        )
        assertEquals("a\nb\nc\nD\ne\n", revertHunk(rows, 1..1, trailingNewline = true))
        assertEquals("a\nB\nc\nd\ne\n", revertHunk(rows, 3..3, trailingNewline = true))
    }

    @Test
    fun `newSideLineAt returns the row's new-side line`() {
        val rows = listOf(equal("a", 1), change("b", "B", 2, 2), equal("c", 3))
        assertEquals(1, newSideLineAt(rows, 0))
        assertEquals(2, newSideLineAt(rows, 1))
        assertEquals(3, newSideLineAt(rows, 2))
    }

    @Test
    fun `newSideLineAt skips deletions to the next new-side line`() {
        // A deleted block has no new-side number; the file line is the next surviving line.
        val rows = listOf(equal("a", 1), delete("b", 2), delete("c", 3), equal("d", 2))
        assertEquals(2, newSideLineAt(rows, 1))
        assertEquals(2, newSideLineAt(rows, 2))
    }

    @Test
    fun `newSideLineAt falls back to the previous new-side line at a trailing deletion`() {
        // Deletions at the end have nothing after them; scan backward to the last real line.
        val rows = listOf(equal("a", 1), equal("b", 2), delete("c", 3))
        assertEquals(2, newSideLineAt(rows, 2))
    }

    @Test
    fun `newSideLineAt is safe for empty and out-of-range input`() {
        assertEquals(1, newSideLineAt(emptyList(), 0))
        assertEquals(1, newSideLineAt(emptyList(), 5))
        val rows = listOf(equal("a", 1), equal("b", 2))
        assertEquals(2, newSideLineAt(rows, 99))
        assertEquals(1, newSideLineAt(rows, -3))
    }

    @Test
    fun `maxDiffLineLength measures each side on its own`() {
        // The two halves scroll separately, so each side's extent has to come from its own longest
        // line — a long line on the left must not stretch the right side's scroll.
        val rows = listOf(equal("ab", 1), change("a-very-long-old-line", "new", 2, 2), equal("c", 3))
        assertEquals("a-very-long-old-line".length, maxDiffLineLength(rows, DiffSide.OLD))
        assertEquals(3, maxDiffLineLength(rows, DiffSide.NEW))
    }

    @Test
    fun `maxDiffLineLength ignores the missing side of an insert or delete`() {
        // Insert/delete rows carry a null on one side; a null must not read as a line of its own.
        val rows = listOf(insert("hello", 1), delete("hi", 1))
        assertEquals(2, maxDiffLineLength(rows, DiffSide.OLD))
        assertEquals(5, maxDiffLineLength(rows, DiffSide.NEW))
    }

    @Test
    fun `maxDiffLineLength is zero for an empty diff`() {
        assertEquals(0, maxDiffLineLength(emptyList(), DiffSide.OLD))
        assertEquals(0, maxDiffLineLength(emptyList(), DiffSide.NEW))
    }
}
