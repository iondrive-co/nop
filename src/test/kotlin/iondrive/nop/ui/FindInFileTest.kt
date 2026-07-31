package iondrive.nop.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FindInFileTest {
    @Test
    fun `empty query returns no matches`() {
        assertEquals(emptyList<IntRange>(), findAllMatches("hello world", ""))
    }

    @Test
    fun `empty text returns no matches`() {
        assertEquals(emptyList<IntRange>(), findAllMatches("", "x"))
    }

    @Test
    fun `single match returns its closed range`() {
        val r = findAllMatches("hello world", "world")
        assertEquals(listOf(6..10), r)
    }

    @Test
    fun `match is case-insensitive`() {
        val r = findAllMatches("Hello World", "world")
        assertEquals(listOf(6..10), r)
    }

    @Test
    fun `non-overlapping repeats are all found`() {
        val r = findAllMatches("ababab", "ab")
        assertEquals(listOf(0..1, 2..3, 4..5), r)
    }

    @Test
    fun `overlapping pattern matches step by query length not by 1`() {
        // "aaa" with query "aa" finds positions 0 and (0+2=2) — only 1 match because 2..3
        // would be out of bounds. The point is the next-search position advances by query
        // length, so we don't double-count overlapping matches.
        val r = findAllMatches("aaaa", "aa")
        assertEquals(listOf(0..1, 2..3), r)
    }

    @Test
    fun `multiline text matches across lines`() {
        val text = "foo\nbar\nfoo"
        val r = findAllMatches(text, "foo")
        assertEquals(listOf(0..2, 8..10), r)
    }

    @Test
    fun `runaway query is capped at 5000 matches`() {
        // 6000 single-char matches; cap kicks in.
        val text = "a".repeat(6000)
        val r = findAllMatches(text, "a")
        assertTrue(r.size == 5000, "expected cap at 5000, got ${r.size}")
    }

    @Test
    fun `lineStartOffset returns 0 for the first line`() {
        assertEquals(0, lineStartOffset("foo\nbar\nbaz", 1))
        assertEquals(0, lineStartOffset("foo", 0))
    }

    @Test
    fun `lineStartOffset finds the offset after each newline`() {
        val text = "foo\nbar\nbaz"
        assertEquals(4, lineStartOffset(text, 2))
        assertEquals(8, lineStartOffset(text, 3))
    }

    @Test
    fun `lineStartOffset past the last line clamps to the end`() {
        val text = "foo\nbar"
        assertEquals(text.length, lineStartOffset(text, 99))
    }

    @Test
    fun `matchIndexForLine picks the occurrence on the requested line`() {
        // "foo" appears on lines 1, 3, and 4 (0..2, 8..10, 12..14).
        val text = "foo\nbar\nfoo\nfoo"
        val matches = findAllMatches(text, "foo")
        assertEquals(0, matchIndexForLine(text, matches, 1))
        assertEquals(1, matchIndexForLine(text, matches, 3))
        assertEquals(2, matchIndexForLine(text, matches, 4))
    }

    @Test
    fun `matchIndexForLine on a line with no match takes the next one`() {
        // Line 2 has no match; the first hit at/after its start is the line-3 occurrence.
        val text = "foo\nbar\nfoo"
        val matches = findAllMatches(text, "foo")
        assertEquals(1, matchIndexForLine(text, matches, 2))
    }

    @Test
    fun `matchIndexForLine falls back to 0 when there are no matches`() {
        assertEquals(0, matchIndexForLine("nothing here", emptyList(), 5))
    }

    private fun bufferOf(text: String, caret: Int = 0) =
        TextFieldState(text).also { it.edit { selection = TextRange(caret) } }

    @Test
    fun `replaceMatches swaps a single hit`() {
        val state = bufferOf("hello world")
        replaceMatches(state, findAllMatches("hello world", "world"), "there")
        assertEquals("hello there", state.state())
    }

    @Test
    fun `replaceMatches rewrites every hit in one pass`() {
        val state = bufferOf("foo bar foo baz foo")
        replaceMatches(state, findAllMatches(state.state(), "foo"), "qux")
        assertEquals("qux bar qux baz qux", state.state())
    }

    @Test
    fun `a longer replacement does not shift the hits that follow it`() {
        // The reason the splice runs back-to-front: front-to-back, each rewrite would move every
        // later match right by the length difference and the second hit would land mid-word.
        val state = bufferOf("a x a x a")
        replaceMatches(state, findAllMatches(state.state(), "a"), "LONGER")
        assertEquals("LONGER x LONGER x LONGER", state.state())
    }

    @Test
    fun `a shorter replacement does not shift the hits that follow it`() {
        val state = bufferOf("aaa-aaa-aaa")
        replaceMatches(state, findAllMatches(state.state(), "aaa"), "b")
        assertEquals("b-b-b", state.state())
    }

    @Test
    fun `a replacement containing the query is not re-replaced`() {
        // Replace-all is one pass over the matches found before it started, so growing "a" into
        // "aa" terminates instead of feeding itself.
        val state = bufferOf("a b a")
        replaceMatches(state, findAllMatches(state.state(), "a"), "aa")
        assertEquals("aa b aa", state.state())
    }

    @Test
    fun `replaceMatches replaces case-insensitive hits with the exact replacement`() {
        val state = bufferOf("Foo foo FOO")
        replaceMatches(state, findAllMatches(state.state(), "foo"), "bar")
        assertEquals("bar bar bar", state.state())
    }

    @Test
    fun `replaceMatches with nothing to replace leaves the buffer alone`() {
        val state = bufferOf("untouched", caret = 3)
        replaceMatches(state, emptyList(), "x")
        assertEquals("untouched", state.state())
        assertEquals(3, state.selection.start)
    }

    @Test
    fun `the caret follows the text it was sitting on rather than jumping to the end`() {
        // The whole point of splicing rather than rewriting the buffer: a replace must not dump the
        // user at the bottom of the document (the reported find-then-type bug).
        val text = "foo bar foo baz"
        val state = bufferOf(text, caret = text.indexOf("baz"))
        replaceMatches(state, findAllMatches(text, "foo"), "X")

        assertEquals("X bar X baz", state.state())
        assertTrue(
            state.state().substring(state.selection.start).startsWith("baz"),
            "caret should still be on \"baz\", was at ${state.selection.start} in \"${state.state()}\"",
        )
    }

    @Test
    fun `a caret ahead of every hit stays exactly where it was`() {
        val state = bufferOf("keep me | foo foo", caret = 4)
        replaceMatches(state, findAllMatches(state.state(), "foo"), "replaced")
        assertEquals(4, state.selection.start, "nothing before the caret changed, so it must not move")
    }
}

/** The buffer's text, spelled out once so the assertions above read as comparisons. */
private fun TextFieldState.state(): String = text.toString()
