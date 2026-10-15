package iondrive.nop.terminal

import com.jediterm.terminal.ArrayTerminalDataStream
import com.jediterm.terminal.emulator.JediEmulator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * Telling the user's prompts from nop's among those an agent CLI echoes, on screens JediTerm draws
 * from escape sequences cut down from what Claude Code 2.1 and Codex 0.160 write: Claude's echo is
 * its marker and text on a grey band as wide as the terminal, Codex's the same on a paler band with a
 * blank row of it above and below, and Codex's input box looks like one of its echoes.
 */
class UserMessagesTest {

    private val nopPrompts = listOf(
        "[Message via nop from ",
        "Your usage limit has reset. Carry on with what you were doing.",
        "Continue the task handed over from ",
    )
    private val messages = UserMessages(nopPrompts)

    private val settings = NopTerminalSettings(Color.WHITE, Color.BLACK, Color.BLUE)

    /** A terminal [columns] wide and [rows] high that has been sent [output]. */
    private fun terminal(output: String, columns: Int = WIDTH, rows: Int = 12): NopTerminalWidget {
        val widget = NopTerminalWidget(columns, rows, settings)
        val emulator = JediEmulator(ArrayTerminalDataStream(output.toCharArray()), widget.terminal)
        while (emulator.hasNext()) emulator.next()
        return widget
    }

    /** The user's messages on [widget], scrollback and screen. */
    private fun found(widget: NopTerminalWidget, from: Int? = null): List<IntRange> {
        val buffer = widget.terminalTextBuffer
        buffer.lock()
        try {
            val rows = UserMessages.rowsOf(buffer, settings.getDefaultStyle().background)
            return messages.find(rows, from ?: rows.top, rows.bottom, widget.terminal.cursorY - 1)
        } finally {
            buffer.unlock()
        }
    }

    private fun found(output: String, from: Int? = null) = found(terminal(output), from)

    /** Claude's echo of a prompt whose rows read [lines]. */
    private fun claude(vararg lines: String): String = lines.mapIndexed { i, line ->
        val lead = if (i == 0) "$ESC[38;2;153;153;153m❯ $ESC[38;2;250;250;250m" else "  "
        "$ESC[48;2;55;55;55m$lead$line${" ".repeat(WIDTH - 3 - line.length)}$ESC[39m$ESC[49m\r\n"
    }.joinToString("")

    /** Codex's echo of a prompt whose rows read [lines], between its padding rows, on [shade]. */
    private fun codex(vararg lines: String, shade: Int = 249): String {
        val bg = "$ESC[48;2;$shade;$shade;${shade}m"
        val pad = "$bg${" ".repeat(WIDTH - 1)}$ESC[49m\r\n"
        return pad + lines.mapIndexed { i, line ->
            "$bg${if (i == 0) "› " else "  "}$line${" ".repeat(WIDTH - 3 - line.length)}$ESC[49m\r\n"
        }.joinToString("") + pad
    }

    private fun reply(text: String) = "● $text\r\n\r\n"

    @Test
    fun `the user's prompt is found and a message nop delivered is not`() {
        val screen = claude("fix the flaky parser test") + reply("Done.") +
            claude("[Message via nop from agent \"builder\" (id ab12)]", "the release build is green") + reply("Noted.")

        assertEquals(listOf(0..0), found(screen))
    }

    @Test
    fun `a message is every row of its band`() {
        val screen = "\r\n" + claude("rename the module,", "update its imports", "and run the tests") + reply("On it.")

        assertEquals(listOf(1..3), found(screen))
    }

    @Test
    fun `nop's prompts are known however the echo wraps them`() {
        val screen = claude("[Message via", "nop from service \"ci\"] build 41 failed") + "\r\n" +
            claude("Your usage limit has", "reset. Carry on with what you were doing.") + "\r\n" +
            claude("Continue the task handed over from Codex. Read", "/home/dev/handoff-1.md in full") + "\r\n" +
            claude("and what about the docs?")

        assertEquals(listOf(9..9), found(screen))
    }

    @Test
    fun `Codex's echo is found with its padding, and its input box is not`() {
        val screen = codex("add a changelog entry") + reply("Added.") + codex("[Message via nop from agent \"x\"] hi") +
            reply("Read it.") + codex("Ask Codex to do anything", shade = 244).removeSuffix("\r\n")
        val widget = terminal(screen, rows = 16)
        // Codex keeps the cursor in its input box, after the marker: on row 11, which is the 12th.
        widget.terminal.cursorPosition(3, 12)

        assertEquals(listOf(0..2), found(widget))
    }

    @Test
    fun `a band that no prompt marker heads is not a message`() {
        val added = "$ESC[48;2;30;80;30m+ val retries = 3${" ".repeat(WIDTH - 18)}$ESC[49m\r\n"
        val quoted = "$ESC[48;2;55;55;55m  as in ❯ this${" ".repeat(WIDTH - 15)}$ESC[49m\r\n"

        assertEquals(emptyList<IntRange>(), found(added + added + quoted))
    }

    @Test
    fun `the prompt being typed on the terminal's own background is not a message`() {
        val screen = "$ESC[0m❯ half a thought\r\n${"─".repeat(WIDTH - 1)}\r\n"

        assertEquals(emptyList<IntRange>(), found(screen))
    }

    @Test
    fun `a message scrolled partly out of view is still read from its first row`() {
        val screen = claude("[Message via nop from agent \"x\"]", "a", "b") + "\r\n" + claude("mine,", "and", "longer")

        assertEquals(listOf(5..6), found(screen, from = 5))
        assertEquals(emptyList<IntRange>(), found(screen, from = 1).filter { it.first < 4 })
    }

    @Test
    fun `messages in the scrollback are found too`() {
        val screen = claude("an early question") + (1..30).joinToString("") { reply("line $it") }
        val widget = terminal(screen)
        val history = widget.terminalTextBuffer.historyLinesCount

        assertTrue(history > 0, "the question has scrolled off the screen")
        assertEquals(listOf(-history..-history), found(widget))
    }

    @Test
    fun `the user's message is painted in nop's colour and a delivered one keeps the CLI's`() {
        val widget = terminal(claude("mine") + "\r\n" + claude("[Message via nop from agent \"x\"]"))
        widget.userMessages = messages
        val panel = widget.terminalPanel
        panel.setSize(WIDTH * 9, 12 * 21)
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        panel.paintComponent(g)
        g.dispose()
        val rowHeight = panel.pixelHeight / 12
        val columnWidth = panel.pixelWidth / WIDTH

        // Near the right of each row, past the text, where only the band's background is drawn.
        fun backgroundOn(row: Int) =
            Color(image.getRGB((WIDTH - 3) * columnWidth + columnWidth / 2, row * rowHeight + rowHeight / 2))
        assertEquals(settings.userMessageBackground, backgroundOn(0))
        assertEquals(Color(55, 55, 55), backgroundOn(2))
        assertEquals(Color.WHITE, backgroundOn(4))
    }

    @Test
    fun `text on a background nop swapped in is in the terminal's colour, and the program's again after`() {
        val image = BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB)
        val grey = Color(55, 55, 55)
        val light = Color(250, 250, 250)
        val g = ContrastAdjustingGraphics2D(
            image.createGraphics(), settings,
            fillColor = { _, color -> if (color == grey) settings.userMessageBackground else color },
        )
        g.color = grey
        g.fillRect(0, 0, 40, 10)
        g.color = light
        g.drawLine(0, 5, 39, 5)
        g.color = Color(40, 40, 120)
        g.fillRect(0, 10, 40, 10)
        g.color = light
        g.drawLine(0, 15, 39, 15)

        assertEquals(settings.userMessageBackground, Color(image.getRGB(20, 2)))
        assertEquals(settings.fg, Color(image.getRGB(20, 5)))
        assertEquals(light, Color(image.getRGB(20, 15)))
    }

    @Test
    fun `the scrollbar marks where each of the user's messages starts`() {
        val screen = claude("first", "question") + reply("one") + claude("[Message via nop from agent \"x\"]") +
            (1..20).joinToString("") { reply("line $it") } + claude("second")
        val widget = terminal(screen)
        widget.userMessages = messages
        widget.markMessages()

        val second = widget.terminalTextBuffer.historyLinesCount + found(widget).last().first
        assertEquals(listOf(0, second), widget.messageMarks)
    }

    private companion object {
        const val ESC = "\u001b"
        const val WIDTH = 60
    }
}
