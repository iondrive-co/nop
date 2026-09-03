package iondrive.nop.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaLexerTest {
    private fun names(text: String): List<String> =
        JavaLexer.identifiers(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun `identifiers are found and punctuation is not`() {
        assertEquals(listOf("int", "x", "y"), names("int x = y + 1;"))
    }

    @Test
    fun `line comments are skipped`() {
        assertEquals(listOf("int", "x"), names("int x; // secret ghost\n"))
    }

    @Test
    fun `block comments and javadoc are skipped`() {
        assertEquals(listOf("int", "x"), names("/** ghost {@link ghost} */\nint x;"))
    }

    @Test
    fun `string literals are skipped`() {
        assertEquals(listOf("String", "s"), names("""String s = "ghost and ghost";"""))
    }

    @Test
    fun `escaped quotes do not end a string early`() {
        assertEquals(listOf("String", "s"), names("""String s = "a\" ghost";"""))
    }

    @Test
    fun `text blocks are skipped whole`() {
        val text = "String s = \"\"\"\n  ghost ghost\n  \"\"\";\nint after;"
        assertEquals(listOf("String", "s", "int", "after"), names(text))
    }

    @Test
    fun `char literals are skipped`() {
        assertEquals(listOf("char", "c"), names("""char c = '"';"""))
    }

    @Test
    fun `numeric literals do not yield identifiers`() {
        assertEquals(listOf("double", "a", "int", "b", "long", "c"), names("double a = 1e5; int b = 0x1F; long c = 10L;"))
    }

    @Test
    fun `a decimal point inside a number does not split it`() {
        assertEquals(listOf("double", "d"), names("double d = 1.5;"))
    }

    @Test
    fun `a field access after a number-led name still lexes`() {
        assertEquals(listOf("x1", "foo"), names("x1.foo;"))
    }

    @Test
    fun `an unterminated string stops at the newline`() {
        // What a buffer looks like mid-keystroke: the rest of the file must still lex.
        assertEquals(listOf("String", "s", "int", "after"), names("String s = \"oops\nint after;"))
    }

    @Test
    fun `occurrencesOf matches whole tokens only`() {
        val text = "int count = counter + count;"
        val hits = JavaLexer.occurrencesOf(text, "count")
        assertEquals(2, hits.size, "counter must not match count")
        assertEquals("count", text.substring(hits[0].first, hits[0].last + 1))
    }

    @Test
    fun `occurrencesOf ignores the name inside a comment or string`() {
        val text = """
            // count
            int count = 1;
            String s = "count";
        """.trimIndent()
        assertEquals(1, JavaLexer.occurrencesOf(text, "count").size)
    }

    @Test
    fun `identifierAt finds the token under the caret`() {
        val text = "int total = 1;"
        val range = JavaLexer.identifierAt(text, text.indexOf("total") + 2)
        assertEquals("total", text.substring(range!!.first, range.last + 1))
    }

    @Test
    fun `identifierAt returns null off a word`() {
        // Offset 6 is the '=' — no identifier touches it on either side.
        assertNull(JavaLexer.identifierAt("int x = 1;", 6))
    }

    @Test
    fun `identifierAt resolves a caret sitting just past the end of a word`() {
        // Where the caret lands after typing a name, and the position Find Usages is most often
        // invoked from. JumpResolver.wordRangeAt behaves the same way for Ctrl-click.
        val text = "int total = 1;"
        val range = JavaLexer.identifierAt(text, text.indexOf("total") + "total".length)
        assertEquals("total", text.substring(range!!.first, range.last + 1))
    }

    @Test
    fun `identifierAt does not treat a number as a name`() {
        assertNull(JavaLexer.identifierAt("int x = 12;", 9))
    }

    @Test
    fun `skipTrivia steps over whitespace and comments`() {
        val text = "name /* gap */  (int a)"
        assertEquals('(', text[JavaLexer.skipTrivia(text, text.indexOf(' '))])
    }

    @Test
    fun `identifier validity rejects keywords and bad shapes`() {
        assertTrue(JavaLexer.isValidIdentifier("total"))
        assertTrue(JavaLexer.isValidIdentifier("_x$1"))
        assertFalse(JavaLexer.isValidIdentifier("class"))
        assertFalse(JavaLexer.isValidIdentifier("1abc"))
        assertFalse(JavaLexer.isValidIdentifier(""))
        assertFalse(JavaLexer.isValidIdentifier("a b"))
    }
}
