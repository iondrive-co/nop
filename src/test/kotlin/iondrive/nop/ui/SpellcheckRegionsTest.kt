package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpellcheckRegionsTest {

    /** The text the given regions actually cover, joined — what the spellchecker gets to read. */
    private fun covered(text: String, regions: List<IntRange>): String =
        regions.joinToString("|") { text.substring(it.first, it.last + 1) }

    private fun regionsFor(ext: String?, text: String): List<IntRange> {
        val tokens = tokenizerForExtension(ext)?.invoke(text) ?: emptyList()
        return spellcheckRegions(ext, text, tokens)
    }

    @Test
    fun `kotlin checks comments and strings but not code`() {
        val text = "// a comment\nval greeting = \"hello there\"\n"
        val regions = regionsFor("kt", text)
        val seen = covered(text, regions)
        assertTrue(seen.contains("a comment"), seen)
        assertTrue(seen.contains("hello there"), seen)
        assertFalse(seen.contains("greeting"), "identifiers must not be spellchecked: $seen")
        assertFalse(seen.contains("val "), seen)
    }

    @Test
    fun `json is left alone entirely`() {
        val text = """{"nme": "smoe value"}"""
        assertTrue(regionsFor("json", text).isEmpty())
    }

    @Test
    fun `markdown checks prose but not code spans or links`() {
        val text = "# Heading\n\nSome prose with `inlineCode` and [a link](http://exmaple.com/x).\n"
        val regions = regionsFor("md", text)
        val seen = covered(text, regions)
        assertTrue(seen.contains("Heading"), seen)
        assertTrue(seen.contains("Some prose with"), seen)
        assertFalse(seen.contains("inlineCode"), "inline code is not prose: $seen")
        assertFalse(seen.contains("exmaple"), "link targets are not prose: $seen")
    }

    @Test
    fun `markdown skips fenced code blocks`() {
        val text = "Intro line\n\n```\nval x = doTheThnig()\n```\n\nOutro line\n"
        val seen = covered(text, regionsFor("md", text))
        assertTrue(seen.contains("Intro line"), seen)
        assertTrue(seen.contains("Outro line"), seen)
        assertFalse(seen.contains("doTheThnig"), seen)
    }

    @Test
    fun `plain text is checked from end to end`() {
        val text = "Just some words.\nAnd a second line."
        assertEquals(listOf(0..(text.length - 1)), regionsFor("txt", text))
    }

    @Test
    fun `an unknown extension is not checked at all`() {
        val text = "col1,col2\nsome,data"
        assertTrue(regionsFor("csv", text).isEmpty())
        assertTrue(regionsFor(null, text).isEmpty())
    }

    @Test
    fun `empty files produce no regions`() {
        assertTrue(regionsFor("md", "").isEmpty())
        assertTrue(regionsFor("kt", "").isEmpty())
    }

    @Test
    fun `typosInLines offsets each hit into the joined text`() {
        val lines = listOf("// a mispelling here", "fun f() {}", "// and anohter one")
        val joined = lines.joinToString("\n")
        val typos = typosInLines(lines, "kt", tokenizerForExtension("kt"))

        assertEquals(listOf("mispelling", "anohter"), typos.map { it.word })
        typos.forEach {
            assertEquals(it.word, joined.substring(it.range.first, it.range.last + 1))
        }
    }

    @Test
    fun `typosInLines checks each line on its own terms`() {
        // Code outside a comment is not prose, on any line of the block.
        val typos = typosInLines(listOf("val mispelledIdentifier = 1"), "kt", tokenizerForExtension("kt"))
        assertTrue(typos.isEmpty())
    }

    @Test
    fun `typosInLines returns nothing for a file type that is not checked`() {
        assertTrue(typosInLines(listOf("teh wrogn stuff"), "csv", null).isEmpty())
        assertTrue(typosInLines(emptyList(), "md", null).isEmpty())
    }

    @Test
    fun `typosInLines handles blank and empty lines`() {
        val lines = listOf("", "   ", "A delibrate mistake", "")
        val joined = lines.joinToString("\n")
        val typos = typosInLines(lines, "txt", null)
        assertEquals(listOf("delibrate"), typos.map { it.word })
        assertEquals("delibrate", joined.substring(typos[0].range.first, typos[0].range.last + 1))
    }

    @Test
    fun `regions are ascending and non-overlapping`() {
        val text = """
            /* a block comment */
            fun f() {
                // line comment
                println("a string")
                val x = 1 // trailing
            }
        """.trimIndent()
        val regions = regionsFor("kt", text)
        assertTrue(regions.isNotEmpty())
        regions.zipWithNext().forEach { (a, b) ->
            assertTrue(a.last < b.first, "regions overlap or are unsorted: $a then $b")
        }
        regions.forEach { assertTrue(it.first in text.indices && it.last in text.indices, "out of range: $it") }
    }
}
