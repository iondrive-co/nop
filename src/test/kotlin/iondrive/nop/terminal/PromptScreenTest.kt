package iondrive.nop.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ArrayTerminalDataStream
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.model.TerminalTextBuffer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading an agent CLI's prompt off its screen, to tell a prompt that is really empty from one the
 * keystrokes only guess may hold a draft. The screens are drawn by JediTerm from the escape sequences
 * each CLI was seen writing (Claude Code 2.1, Codex 0.160, Antigravity 1.3), cut down to the prompt.
 */
class PromptScreenTest {

    private val rule = "─".repeat(40)

    /** [output] as JediTerm would draw it on a 60×12 screen. */
    private fun screen(output: String): List<ScreenLine> {
        val state = StyleState()
        val buffer = TerminalTextBuffer(60, 12, state)
        val terminal = JediTerminal(NoDisplay, buffer, state)
        val emulator = JediEmulator(ArrayTerminalDataStream(output.replace("\n", "\r\n").toCharArray()), terminal)
        while (emulator.hasNext()) emulator.next()
        buffer.lock()
        try {
            return (0 until buffer.height).map { TerminalSession.screenLine(buffer.getLine(it)) }
        } finally {
            buffer.unlock()
        }
    }

    private fun empty(output: String) = TerminalSession.promptLooksEmpty(screen(output))

    @Test
    fun `Claude's prompt is empty under its dim placeholder and not with text typed`() {
        val conversation = "\u001b[39m❯ fix the tests\n\n● Done.\n\n"
        assertTrue(empty("$conversation$rule\n\u001b[39m❯ \u001b[2mTry \"how do I log an error?\"\n\u001b[22m$rule\n  ? for shortcuts"))
        assertTrue(empty("$conversation$rule\n\u001b[39m❯ \n$rule\n"), "no placeholder at all")
        assertFalse(empty("$conversation$rule\n\u001b[39m❯ hello there\n$rule\n"))
        assertFalse(
            empty("$conversation$rule\n\u001b[39m❯ hello\u001b[2m there\u001b[22m\n$rule\n"),
            "a dim suggestion after typed text still leaves the text",
        )
    }

    @Test
    fun `Claude's caret on its placeholder or suggested prompt still reads as an empty prompt`() {
        val conversation = "\u001b[39m❯ fix the tests\n\n● Done.\n\n"
        // Claude draws its caret by reversing the cell under it: on an empty prompt that is the
        // placeholder's first character, with the rest of the placeholder dim behind it.
        assertTrue(
            empty("$conversation$rule\n❯ \u001b[7mT\u001b[27m\u001b[2mry \"how does <filepath> work?\"\u001b[22m\n$rule\n"),
            "an example placeholder",
        )
        assertTrue(empty("$conversation$rule\n❯ \u001b[7mr\u001b[27m\u001b[2mun the full test suite\u001b[22m\n$rule\n"), "a suggested prompt")
        assertTrue(empty("$conversation$rule\n❯ \u001b[7m \u001b[27m\n$rule\n"), "the caret alone")
        val long = "run the whole test suite again and then fix whatever it turns up in the parser"
        assertTrue(
            empty("$conversation$rule\n❯ \u001b[7m${long.first()}\u001b[27m\u001b[2m${long.drop(1)}\u001b[22m\n$rule\n"),
            "a suggestion long enough to run onto the next row",
        )
    }

    @Test
    fun `Claude's caret on a draft leaves it a draft`() {
        assertFalse(empty("$rule\n❯ half a thought\u001b[7m \u001b[27m\n$rule\n"), "the caret after the text")
        assertFalse(empty("$rule\n❯ \u001b[7mh\u001b[27malf a thought\n$rule\n"), "the caret moved back to the start")
        assertFalse(empty("$rule\n❯ \u001b[7mx\u001b[27m\n$rule\n"), "one character under the caret")
        assertFalse(empty("$rule\n❯ \u001b[7m/\u001b[27m\u001b[2mhelp\u001b[22m\n$rule\n"), "a command's first character under the caret")
    }

    @Test
    fun `a draft that starts on the row below the marker is still a draft`() {
        assertFalse(empty("$rule\n❯ \n  second line of the draft\n$rule\n"))
    }

    @Test
    fun `Codex's prompt is read the same way`() {
        val header = "  >_ OpenAI Codex (v0.160.0)\n\n"
        assertTrue(empty("$header\u001b[1m›\u001b[22m \u001b[2mAsk Codex to do anything\u001b[22m\n\n  ? for shortcuts"))
        assertFalse(empty("$header\u001b[1m›\u001b[22m hello there\n\n  ? for shortcuts"))
    }

    @Test
    fun `Antigravity's prompt is read the same way, box and all`() {
        assertTrue(empty("\u001b[90m$rule\u001b[m\n> \n\u001b[90m$rule\u001b[m\n? for shortcuts"))
        assertFalse(empty("\u001b[90m$rule\u001b[m\n> hello there\n\u001b[90m$rule\u001b[m\n"))
        assertTrue(empty("╭$rule╮\n│ > \n╰$rule╯\n"), "inside a box")
    }

    @Test
    fun `a screen showing a menu or no prompt at all is not read as empty`() {
        assertFalse(empty("Is this a project you trust?\n\n ❯ No, exit\n   Yes, I trust this folder\n\n Enter to confirm"))
        assertFalse(empty("> Yes, I trust this folder\n  No, exit\n"))
        assertFalse(empty("● Working…\n\n  esc to interrupt\n"))
        assertFalse(empty(""))
    }

    @Test
    fun `an echoed prompt above the real one does not count`() {
        assertFalse(empty("❯ \n\n● Done.\n$rule\n❯ half a thought\n$rule\n"))
    }

    private object NoDisplay : TerminalDisplay {
        override fun setCursor(x: Int, y: Int) = Unit
        override fun setCursorShape(shape: CursorShape?) = Unit
        override fun beep() = Unit
        override fun onResize(newTermSize: TermSize, origin: RequestOrigin) = Unit
        override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) = Unit
        override fun setCursorVisible(isCursorVisible: Boolean) = Unit
        override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) = Unit
        override fun getWindowTitle(): String = ""
        override fun setWindowTitle(windowTitle: String) = Unit
        override fun getSelection(): TerminalSelection? = null
        override fun terminalMouseModeSet(mouseMode: MouseMode) = Unit
        override fun setMouseFormat(mouseFormat: MouseFormat) = Unit
        override fun ambiguousCharsAreDoubleWidth(): Boolean = false
    }
}
