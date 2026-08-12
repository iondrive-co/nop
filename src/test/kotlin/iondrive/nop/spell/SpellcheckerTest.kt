package iondrive.nop.spell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpellcheckerTest {

    /** A dictionary small enough to read: everything else in these tests is a "typo" by definition. */
    private val vocabulary = setOf(
        "the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog",
        "colour", "user", "name", "server", "http", "returns", "value", "this", "word", "words",
        "here",
    )

    private fun knows(word: String) = word.lowercase() in vocabulary

    private fun typos(text: String, regions: List<IntRange> = listOf(text.indices)) =
        findTypos(text, regions, ::knows).map { it.word }

    @Test
    fun `reports only the words it does not know`() {
        val text = "the quick brwon fox"
        val found = findTypos(text, listOf(text.indices), ::knows)
        assertEquals(listOf("brwon"), found.map { it.word })
        assertEquals("brwon", text.substring(found[0].range.first, found[0].range.last + 1))
    }

    @Test
    fun `clean prose reports nothing`() {
        assertTrue(typos("the quick brown fox jumps over the lazy dog").isEmpty())
    }

    @Test
    fun `only the given regions are checked`() {
        val text = "wrng words wrng"
        // The middle of the text only: the misspellings on either side are outside the region.
        assertTrue(findTypos(text, listOf(5..9), ::knows).isEmpty())
        assertEquals(listOf("wrng", "wrng"), typos(text))
    }

    @Test
    fun `words of three letters or fewer are left alone`() {
        // "teh" is the classic typo and still gets a pass: at this length there is no telling it
        // from an abbreviation, and abbreviations are everywhere in code.
        assertTrue(typos("teh xyz ab c").isEmpty())
    }

    @Test
    fun `case is ignored`() {
        assertTrue(typos("The Quick BROWN fox").isEmpty())
    }

    @Test
    fun `acronyms are not checked`() {
        assertTrue(typos("HTTP GET JSON XSLT").isEmpty())
    }

    @Test
    fun `camel case identifiers are checked a word at a time`() {
        assertEquals(listOf("Nmae"), typos("userNmae"))
        assertTrue(typos("userName").isEmpty())
    }

    @Test
    fun `an acronym prefix keeps its last letter out of the following word`() {
        assertTrue(typos("HTTPServer").isEmpty())
        assertEquals(listOf("Servr"), typos("HTTPServr"))
    }

    @Test
    fun `urls paths emails and qualified names are skipped`() {
        assertTrue(typos("https://exmaple.com/foo").isEmpty())
        assertTrue(typos("src/main/kotln/Thing.kt").isEmpty())
        assertTrue(typos("someone@exmaple.com").isEmpty())
        assertTrue(typos("kotlinx.corutines.flow").isEmpty())
        assertTrue(typos("e.g. bhavior").contains("bhavior"), "only the qualified chunk is skipped")
    }

    @Test
    fun `words glued to digits are skipped`() {
        assertTrue(typos("utf8 sha256 x1234").isEmpty())
        assertEquals(listOf("wrod"), typos("wrod 1234"))
    }

    @Test
    fun `possessives and contractions stay in one piece`() {
        val found = findTypos("the fox's dinner doesn't", listOf(0..23)) { w ->
            w in setOf("the", "fox's", "dinner", "doesn't")
        }
        assertTrue(found.isEmpty(), "got ${found.map { it.word }}")
    }

    @Test
    fun `non-english text is left alone`() {
        assertTrue(typos("συντακτικό λάθος").isEmpty())
        assertTrue(typos("これはコメントです").isEmpty())
    }

    @Test
    fun `ranges point at the misspelled word inside its line`() {
        val text = "// this word is wrogn here"
        val found = findTypos(text, listOf(text.indices), ::knows)
        assertEquals(1, found.size)
        assertEquals("wrogn", found[0].word)
        assertEquals(16, found[0].range.first)
        assertEquals(20, found[0].range.last)
    }

    @Test
    fun `regions outside the text are clamped rather than thrown`() {
        val text = "wrng"
        assertEquals(listOf("wrng"), findTypos(text, listOf(0..100), ::knows).map { it.word })
        assertTrue(findTypos(text, listOf(50..60), ::knows).isEmpty())
    }

    @Test
    fun `the bundled dictionary knows ordinary english and common jargon`() {
        val text = """
            The tokenizer resolves each namespace lazily, so the coroutine that
            recomposes the gutter never blocks on IO. Colour and color both pass,
            as do behaviour, initialise and initialize.
        """.trimIndent()
        assertTrue(findTypos(text, listOf(text.indices)).isEmpty(), "unexpected typos in real prose")
    }

    @Test
    fun `the bundled dictionary catches real misspellings`() {
        val text = "recieve the seperate enviroment"
        assertEquals(
            listOf("recieve", "seperate", "enviroment"),
            findTypos(text, listOf(text.indices)).map { it.word },
        )
    }
}
