package iondrive.nopdiff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenizerTest {

    private fun textOf(line: String, t: Token) = line.substring(t.start, t.endExclusive)

    @Test
    fun kotlinKeywordAndNumber() {
        val line = "val x = 1"
        val tokens = tokenizeKotlin(line)
        assertTrue(tokens.any { it.kind == TokenKind.KEYWORD && textOf(line, it) == "val" })
        assertTrue(tokens.any { it.kind == TokenKind.NUMBER && textOf(line, it) == "1" })
    }

    @Test
    fun kotlinStringIsOneToken() {
        val line = """val s = "hello""""
        val tokens = tokenizeKotlin(line)
        assertTrue(tokens.any { it.kind == TokenKind.STRING && textOf(line, it) == "\"hello\"" })
    }

    @Test
    fun jsonLiteralAndString() {
        val line = """{"ok": true}"""
        val tokens = tokenizeJson(line)
        assertTrue(tokens.any { it.kind == TokenKind.STRING && textOf(line, it) == "\"ok\"" })
        assertTrue(tokens.any { it.kind == TokenKind.LITERAL && textOf(line, it) == "true" })
    }

    @Test
    fun extensionMapping() {
        assertNotNull(tokenizerForExtension("kt"))
        assertNotNull(tokenizerForExtension("go"))
        assertNotNull(tokenizerForExtension("tsx"))
        assertNull(tokenizerForExtension("rs"))
        assertNull(tokenizerForExtension(null))
    }

    @Test
    fun tokensAreSortedAndInBounds() {
        // Inline-flag regexes (markdown/yaml/shell) must construct on the JS RegExp engine without throwing.
        for (sample in listOf("# heading" to "md", "name: build" to "yml", "echo hi # x" to "sh")) {
            val tok = tokenizerForExtension(sample.second)!!
            val toks = tok(sample.first)
            var last = -1
            for (t in toks) {
                assertTrue(t.start in 0..sample.first.length)
                assertTrue(t.endExclusive in t.start..sample.first.length)
                assertTrue(t.start >= last)
                last = t.start
            }
        }
    }
}
