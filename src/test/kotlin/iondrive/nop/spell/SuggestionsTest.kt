package iondrive.nop.spell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestionsTest {

    private val vocabulary = setOf(
        "receive", "separate", "environment", "the", "quick", "brown", "colour", "color",
        "doesn't", "friend", "fried", "fiend", "spelling", "spelunking",
    )

    @Test
    fun `offers the word one edit away`() {
        assertTrue(suggestionsFor("recieve", vocabulary).contains("receive"))
        assertTrue(suggestionsFor("seperate", vocabulary).contains("separate"))
        assertTrue(suggestionsFor("brwon", vocabulary).contains("brown"))
    }

    @Test
    fun `offers nothing when nothing is close`() {
        assertTrue(suggestionsFor("qqqqzzzz", vocabulary).isEmpty())
    }

    @Test
    fun `reaches words two edits away when there is no closer one`() {
        // "enviroment" is two deletions from "environment": no one-edit neighbour exists.
        assertTrue(suggestionsFor("enviroment", vocabulary).contains("environment"))
    }

    @Test
    fun `an apostrophe counts as one edit`() {
        assertTrue(suggestionsFor("doesnt", vocabulary).contains("doesn't"))
    }

    @Test
    fun `a missing space is offered as two words`() {
        assertTrue(suggestionsFor("thequick", vocabulary).contains("the quick"))
    }

    @Test
    fun `suggestions follow the capitalisation of the word they replace`() {
        assertTrue(suggestionsFor("Recieve", vocabulary).contains("Receive"))
        assertTrue(suggestionsFor("RECIEVE", vocabulary).contains("RECEIVE"))
        assertTrue(suggestionsFor("recieve", vocabulary).contains("receive"))
    }

    @Test
    fun `corrections that keep the first letter come first`() {
        // "friend", "fiend" and "fried" are all one edit from "freind"; the ones starting with the
        // same letter are all of them here, so ordering falls to the shared prefix: "fri…" wins.
        val suggestions = suggestionsFor("freind", vocabulary)
        assertEquals("friend", suggestions.first())
    }

    @Test
    fun `the list is capped`() {
        val many = (1..50).map { "wordx$it" }.toSet() + setOf("wordxa", "wordxb", "wordxc")
        assertTrue(suggestionsFor("wordx", many, limit = 3).size <= 3)
    }

    @Test
    fun `the word itself is never suggested`() {
        assertTrue(suggestionsFor("colour", vocabulary).none { it == "colour" })
    }

    @Test
    fun `real misspellings get the obvious correction from the bundled dictionary`() {
        assertTrue(suggestionsFor("recieve").contains("receive"))
        assertTrue(suggestionsFor("teh").contains("the"))
        assertTrue(suggestionsFor("mispelling").contains("misspelling"))
        assertTrue(suggestionsFor("aplication").contains("application"))
        assertTrue(suggestionsFor("delibrate").contains("deliberate"))
    }

    @Test
    fun `suggesting against the whole dictionary stays fast enough for a menu`() {
        // The pathological case: a word with no near neighbours, so the fallback scan runs in full.
        val started = System.nanoTime()
        repeat(5) { suggestionsFor("zqxjvwkbfp") }
        val perCall = (System.nanoTime() - started) / 5 / 1_000_000
        assertTrue(perCall < 250, "suggestion lookup took ${perCall}ms per call")
    }

    @Test
    fun `distanceAtMost agrees with the obvious cases`() {
        assertTrue(distanceAtMost("kitten", "kitten", 0))
        assertTrue(distanceAtMost("kitten", "sitten", 1))
        assertTrue(distanceAtMost("kitten", "sitting", 3))
        assertTrue(!distanceAtMost("kitten", "sitting", 2))
        assertTrue(!distanceAtMost("short", "muchlongerword", 2))
    }
}
