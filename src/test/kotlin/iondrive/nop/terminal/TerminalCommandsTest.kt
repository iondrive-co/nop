package iondrive.nop.terminal

import com.jediterm.terminal.ArrayTerminalDataStream
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.TerminalOutputStream
import com.jediterm.terminal.emulator.mouse.TerminalMouseListener
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.InputEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JFrame
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalCommandsTest {
    @Test
    fun `inline commands and fenced scripts retain original quoting and newlines`() {
        val commands = TerminalCommands()
        val inline = "runner --target=sample --message='two  spaces'"
        val script = "cat <<'PAYLOAD'\nfirst  line\n\nsecond line\nPAYLOAD\nprintf '%s\\n' done"
        commands.addMessage("Run `$inline`, then:\n\n```bash\n$script\n```\nThe `checkpoint_size` setting is in `settings.conf`.")

        assertEquals(listOf(inline, script), commands.snippets.map { it.text })
        assertTrue(commands.snippets.all { it.command })
        commands.addMessage("Again: `$inline`.")
        assertEquals(2, commands.snippets.size, "transcript redraws do not duplicate controls")
    }

    @Test
    fun `matches display wrapping through a token and removes only screen indentation`() {
        val original = "runner --target=sample --mode=inspect --message='two  spaces'"
        val commands = TerminalCommands().apply { addMessage("Run `$original`.") }
        val match = commands.find(listOf("  runner --target=sam", "  ple --mode=inspect --", "  message='two  spaces'", "Done."), 0, 3).single()

        assertEquals(original, match.snippet.text)
        assertEquals(listOf(0, 1, 2), match.spans.map { it.row })
        assertEquals(2, match.spans.first().start)
        assertEquals(23, match.spans.last().end)
    }

    @Test
    fun `code block boxes and blank rows do not enter the clipboard`() {
        val original = "printf 'alpha  beta'\n\nprintf 'gamma'"
        val commands = TerminalCommands().apply { addMessage("```sh\n$original\n```") }
        val match = commands.find(listOf("│ printf 'alpha  beta' │", "│                     │", "│ printf 'gamma'       │"), -3, 0).single()

        assertEquals(original, match.snippet.text)
        assertEquals(listOf(-3, -1), match.spans.map { it.row })
    }

    @Test
    fun `incomplete commands and the command being typed get no control`() {
        val commands = TerminalCommands().apply { addMessage("Run `runner --check all`.") }
        assertTrue(commands.find(listOf("runner --check"), 0, 1).isEmpty())
        assertTrue(commands.find(listOf("runner --check all"), 0, 0).isEmpty())
        assertTrue(commands.find(listOf("runner --check other"), 0, 1).isEmpty())
    }

    @Test
    fun `ambiguous originals never guess which whitespace to copy`() {
        val commands = TerminalCommands().apply {
            addMessage("Either `printf 'a b'` or `printf 'ab'`.")
        }
        assertTrue(commands.find(listOf("printf 'ab'"), 0, 1).isEmpty())
    }

    @Test
    fun `a fenced block owns the snippets it contains`() {
        val commands = TerminalCommands().apply {
            addMessage("Use `runner --check all` or:\n```bash\nrunner --check all\nrunner --list\n```")
        }
        val matches = commands.find(listOf("runner --check all", "runner --list"), 0, 2)
        assertEquals(listOf("runner --check all\nrunner --list"), matches.map { it.snippet.text })
    }

    @Test
    fun `live button copies without a selection and hover highlights all wrapped rows`() = onEdt {
        val original = "runner --target=sample --mode=inspect --message='two  spaces'"
        val copied = mutableListOf<String>()
        val commands = TerminalCommands { copied += it }.apply { addMessage("Run `$original`.") }
        val widget = terminal(48, 12, commands)
        try {
            feed(widget, "Try this:\r\n  runner --target=sample --mode=\r\n  inspect --message='two  spaces'\r\n\r\nReady.\r\n")
            val before = paint(widget)
            val button = buttons(widget).single()
            assertEquals("Copy command", button.text)
            assertTrue(button.isVisible)
            assertTrue(widget.terminalPanel.selection == null)
            button.model.isRollover = true
            val after = paint(widget)
            val changedByRow = (0 until after.height).filter { y ->
                (CommandCopyGutter.WIDTH + 5 until after.width).any { x -> before.getRGB(x, y) != after.getRGB(x, y) }
            }.map { it / (widget.terminalPanel.pixelHeight / 12) }.toSet()
            assertEquals(setOf(1, 2), changedByRow)

            button.doClick(0)
            assertEquals(listOf(original), copied)
            assertEquals("Copied", button.text)
            assertTrue(widget.terminalPanel.selection == null)
            savePreview(after, "inline")
        } finally { widget.close() }
    }

    @Test
    fun `partly visible scripts get a button which copies the entire script`() = onEdt {
        val original = (1..18).joinToString("\n") { "printf 'entry $it'" }
        val copied = mutableListOf<String>()
        val commands = TerminalCommands { copied += it }.apply { addMessage("```bash\n$original\n```") }
        val widget = terminal(48, 12, commands)
        try {
            feed(widget, original.replace("\n", "\r\n") + "\r\nReady.\r\n")
            paint(widget)
            val button = buttons(widget).single()
            assertEquals(0, button.y)
            button.doClick(0)
            assertEquals(listOf(original), copied)

            widget.terminalPanel.verticalScrollModel.value = -widget.terminalTextBuffer.historyLinesCount
            paint(widget)
            buttons(widget).single().doClick(0)
            assertEquals(listOf(original, original), copied)
        } finally { widget.close() }
    }

    @Test
    fun `redraws invalidate controls before their clicks can copy stale text`() = onEdt {
        val copied = mutableListOf<String>()
        val commands = TerminalCommands { copied += it }.apply { addMessage("Run `runner --check all`.") }
        val widget = terminal(48, 12, commands)
        try {
            feed(widget, "runner --check all\r\nReady.\r\n")
            paint(widget)
            val button = buttons(widget).single()
            feed(widget, "\u001b[2J\u001b[HNothing to copy.\r\n")
            button.doClick(0)
            assertTrue(copied.isEmpty())
            paint(widget)
            assertTrue(buttons(widget).isEmpty())
        } finally { widget.close() }
    }

    @Test
    fun `buttons follow terminal reflow and a theme change`() = onEdt {
        val original = "runner --target=sample --mode=inspect --message='two  spaces'"
        val copied = mutableListOf<String>()
        val settings = NopTerminalSettings(Color.WHITE, Color.BLACK, Color(0x17, 0x50, 0xEB))
        val commands = TerminalCommands { copied += it }.apply { addMessage("Run `$original`.") }
        val widget = NopTerminalWidget(72, 12, settings).apply { showCommandCopies(commands) }
        try {
            feed(widget, original + "\r\nReady.\r\n")
            paint(widget)
            val firstY = buttons(widget).single().y
            widget.terminal.resize(TermSize(36, 12), RequestOrigin.User)
            settings.bg = Color(0x1E, 0x1F, 0x22)
            settings.fg = Color(0xDF, 0xE1, 0xE5)
            settings.link = Color(0x68, 0x97, 0xBB)
            val image = paint(widget)
            buttons(widget).single().doClick(0)
            assertEquals(listOf(original), copied)
            assertEquals(firstY, buttons(widget).single().y)
            assertEquals(settings.bg, buttons(widget).single().background)
            savePreview(image, "dark")
        } finally { widget.close() }
    }

    @Test
    fun `native click copies to the clipboard without sending a mouse report to the CLI`() {
        assumeTrue(System.getenv("NOP_TEST_PRIVATE_DISPLAY") == "1" && !GraphicsEnvironment.isHeadless(),
            "Requires a private display, so the desktop and its clipboard are left alone")
        val original = "runner --target=sample --mode=inspect --message='two  spaces'"
        val sent = ConcurrentLinkedQueue<String>()
        val widget = onEdt { terminal(72, 12, TerminalCommands().apply { addMessage("Run `$original`.") }) }
        val frame = onEdt {
            widget.terminal.setTerminalOutput(object : TerminalOutputStream {
                override fun sendBytes(bytes: ByteArray, userInput: Boolean) { sent += String(bytes) }
                override fun sendString(text: String, userInput: Boolean) { sent += text }
            })
            widget.terminalPanel.addTerminalMouseListener(widget.terminal as TerminalMouseListener)
            JFrame("Command copy preview").apply {
                contentPane.add(widget)
                setSize(850, 400)
                setLocation(30, 30)
                isVisible = true
                feed(widget, "\u001b[?1000h\u001b[?1006hTry this:\r\n  runner --target=sample --mode=\r\n  inspect --message='two  spaces'\r\n\r\nReady.\r\n")
            }
        }
        try {
            val robot = Robot().apply { autoDelay = 30 }
            robot.waitForIdle()
            Thread.sleep(100)
            val point = onEdt {
                buttons(widget).single().let { button -> button.locationOnScreen.apply {
                    translate(button.width / 2, button.height / 2)
                } }
            }
            robot.mouseMove(point.x, point.y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            robot.waitForIdle()
            onEdt {
                assertEquals(original, Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor))
                assertEquals("Copied", buttons(widget).single().text)
                assertTrue(widget.terminalPanel.selection == null)
                assertTrue(sent.isEmpty(), "clicking Copy must not send input to the CLI")
            }
            val bounds = onEdt { frame.bounds }
            savePreview(robot.createScreenCapture(bounds), "native")
            val gutter = onEdt { widget.terminalPanel.locationOnScreen.apply { translate(2, 120) } }
            robot.mouseMove(gutter.x, gutter.y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            robot.waitForIdle()
            assertTrue(sent.isEmpty(), "the rest of the copy gutter must also leave the CLI alone")
            val text = onEdt { widget.terminalPanel.locationOnScreen.apply { translate(CommandCopyGutter.WIDTH + 25, 100) } }
            robot.mouseMove(text.x, text.y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            robot.waitForIdle()
            assertTrue(sent.isNotEmpty(), "the simulated CLI really has mouse reporting enabled")
        } finally { onEdt { frame.dispose(); widget.close() } }
    }

    private fun terminal(columns: Int, rows: Int, commands: TerminalCommands) =
        NopTerminalWidget(columns, rows, NopTerminalSettings(Color.WHITE, Color.BLACK, Color(0x17, 0x50, 0xEB)))
            .apply { showCommandCopies(commands) }

    private fun feed(widget: NopTerminalWidget, output: String) {
        val emulator = JediEmulator(ArrayTerminalDataStream(output.toCharArray()), widget.terminal)
        while (emulator.hasNext()) emulator.next()
    }

    private fun paint(widget: NopTerminalWidget): BufferedImage {
        val panel = widget.terminalPanel
        panel.setSize(panel.pixelWidth, panel.pixelHeight)
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { graphics ->
            panel.paint(graphics)
            graphics.dispose()
        }
        return image
    }

    private fun buttons(widget: NopTerminalWidget) = widget.terminalPanel.components.filterIsInstance<JButton>()

    private fun savePreview(image: BufferedImage, name: String) {
        val directory = Path.of("build", "test-previews", "command-copy")
        Files.createDirectories(directory)
        ImageIO.write(image, "png", directory.resolve("$name.png").toFile())
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
