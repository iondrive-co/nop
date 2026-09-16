package iondrive.nop.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * How a dropped file's path is typed into the prompt.
 *
 * The rule is "quote only when it has to be", and it matters in both directions: an unquoted path
 * with a space reaches the CLI as two arguments and neither of them is a file, while a quoted one
 * where nothing needed quoting is a path the user then has to edit before it reads as one.
 */
class TerminalFileDropTest {

    @Test
    fun `an ordinary path is left exactly as it is`() {
        assertEquals(
            "/home/me/Downloads/Screenshot_2026-09-16.png",
            quoteForPrompt("/home/me/Downloads/Screenshot_2026-09-16.png"),
        )
    }

    @Test
    fun `a path with a space is quoted whole`() {
        assertEquals(
            "'/home/me/My Pictures/a.png'",
            quoteForPrompt("/home/me/My Pictures/a.png"),
        )
    }

    /** The `'\''` dance: close the quote, escape one, open again. */
    @Test
    fun `a single quote in the name survives the quoting`() {
        assertEquals(
            """'/home/me/Ada'\''s shot.png'""",
            quoteForPrompt("/home/me/Ada's shot.png"),
        )
    }

    @Test
    fun `a backslash or double quote is quoted rather than passed through`() {
        assertEquals("""'/tmp/a\b.png'""", quoteForPrompt("""/tmp/a\b.png"""))
        assertEquals("""'/tmp/a"b.png'""", quoteForPrompt("""/tmp/a"b.png"""))
    }

    /** A newline is whitespace too, and an unquoted one would submit half a path. */
    @Test
    fun `a newline in the name is quoted`() {
        assertEquals("'/tmp/a\nb.png'", quoteForPrompt("/tmp/a\nb.png"))
    }
}
