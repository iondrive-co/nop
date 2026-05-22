package iondrive.nop.ui

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
}
