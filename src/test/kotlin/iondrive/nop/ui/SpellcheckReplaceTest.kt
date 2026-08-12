package iondrive.nop.ui

import androidx.compose.foundation.text.input.TextFieldState
import iondrive.nop.spell.Typo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpellcheckReplaceTest {

    @Test
    fun `replaces the misspelled word in place`() {
        val state = TextFieldState("// a mispelling here")
        var edited = false

        replaceTypo(state, Typo(5..14, "mispelling"), "misspelling") { edited = true }

        assertEquals("// a misspelling here", state.text.toString())
        assertTrue(edited)
    }

    @Test
    fun `leaves the buffer alone when the word has moved`() {
        // The menu was built against text that has since changed under it.
        val state = TextFieldState("// something else entirely")
        var edited = false

        replaceTypo(state, Typo(5..14, "mispelling"), "misspelling") { edited = true }

        assertEquals("// something else entirely", state.text.toString())
        assertFalse(edited, "nothing was replaced, so nothing was edited")
    }

    @Test
    fun `ignores a range that runs past the end of the text`() {
        val state = TextFieldState("short")
        replaceTypo(state, Typo(2..40, "whatever"), "something")
        assertEquals("short", state.text.toString())
    }

    @Test
    fun `a multi-word suggestion replaces the single word`() {
        val state = TextFieldState("thequick brown fox")
        replaceTypo(state, Typo(0..7, "thequick"), "the quick")
        assertEquals("the quick brown fox", state.text.toString())
    }
}
