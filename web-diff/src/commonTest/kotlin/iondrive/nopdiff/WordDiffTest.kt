package iondrive.nopdiff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordDiffTest {

    /** Reassemble the substring covered by all `changed` spans, in order. */
    private fun changedText(line: String, spans: List<InlineSpan>): String =
        spans.filter { it.changed }.joinToString("") { line.substring(it.startChar, it.endCharExclusive) }

    /** Spans must tile the line exactly: contiguous, in order, covering [0, length). */
    private fun assertTiles(line: String, spans: List<InlineSpan>) {
        if (line.isEmpty()) {
            assertTrue(spans.isEmpty())
            return
        }
        var pos = 0
        for (s in spans) {
            assertEquals(pos, s.startChar, "span gap/overlap in $spans")
            assertTrue(s.endCharExclusive > s.startChar)
            pos = s.endCharExclusive
        }
        assertEquals(line.length, pos)
    }

    @Test
    fun highlightsOnlyTheChangedToken() {
        val old = "val x = 1"
        val new = "val x = 2"
        val (oldSpans, newSpans) = WordDiff.spans(old, new)
        assertTiles(old, oldSpans)
        assertTiles(new, newSpans)
        assertEquals("1", changedText(old, oldSpans))
        assertEquals("2", changedText(new, newSpans))
    }

    @Test
    fun identicalLinesHaveNoChanges() {
        val (oldSpans, newSpans) = WordDiff.spans("same", "same")
        assertEquals("", changedText("same", oldSpans))
        assertEquals("", changedText("same", newSpans))
    }

    @Test
    fun insertionFromEmptyMarksWholeNewLine() {
        val (oldSpans, newSpans) = WordDiff.spans("", "added text")
        assertTrue(oldSpans.isEmpty())
        assertEquals("added text", changedText("added text", newSpans))
    }

    @Test
    fun sharedPrefixAndSuffixStayUnchanged() {
        val old = "the quick brown fox"
        val new = "the slow brown fox"
        val (oldSpans, newSpans) = WordDiff.spans(old, new)
        assertTiles(old, oldSpans)
        assertTiles(new, newSpans)
        assertEquals("quick", changedText(old, oldSpans))
        assertEquals("slow", changedText(new, newSpans))
    }

    @Test
    fun completelyDifferentLinesAreFullyChanged() {
        val (oldSpans, newSpans) = WordDiff.spans("aaa", "bbb")
        assertEquals("aaa", changedText("aaa", oldSpans))
        assertEquals("bbb", changedText("bbb", newSpans))
    }
}
