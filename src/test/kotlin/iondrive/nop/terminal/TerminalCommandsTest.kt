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
    fun `paths and punctuation do not become inline commands`() {
        val commands = TerminalCommands().apply {
            addMessage("See `/`, `/var/example/data`, `///`, `--` and `settings.conf`. Run `./inspect`.")
        }
        assertEquals(listOf("./inspect"), commands.snippets.map { it.text })
    }

    @Test
    fun `command tokens cannot match unrelated fragments or longer words`() {
        val commands = TerminalCommands().apply { addMessage("Use `runner --check all`.") }
        for (line in listOf("runner--checkall", "run ner --check all", "prerunner --check all", "runner --check allowed")) {
            assertTrue(commands.find(listOf(line), 0, 1).isEmpty(), line)
        }
        val executable = TerminalCommands().apply { addMessage("Run `./inspect`.") }
        assertTrue(executable.find(listOf("./inspect.log"), 0, 1).isEmpty())
        assertEquals("./inspect", executable.find(listOf("Run ./inspect."), 0, 1).single().snippet.text)
    }

    @Test
    fun `right click on either wrapped row copies the original without a selection or gutter`() = onEdt {
        val original = "runner --target=sample --mode=inspect --message='two  spaces'"
        val copied = mutableListOf<String>()
        val commands = TerminalCommands { copied += it }.apply { addMessage("Run `$original`.") }
        val widget = terminal(48, 12, commands)
        val plain = NopTerminalWidget(48, 12, NopTerminalSettings(Color.WHITE, Color.BLACK, Color.BLUE))
        try {
            feed(widget, "Try this:\r\n  runner --target=sample --mode=\r\n  inspect --message='two  spaces'\r\n\r\nReady.\r\n")
            paint(widget)
            assertEquals(plain.terminalPanel.pixelWidth, widget.terminalPanel.pixelWidth)
            assertTrue(widget.terminalPanel.components.none { it is JButton })
            for (row in listOf(1, 2)) copyItem(widget, row, 4).doClick(0)
            assertEquals(listOf(original, original), copied)
            assertTrue(widget.terminalPanel.selection == null)
            assertTrue(copyItems(popup(widget, 0, 4)).isEmpty())
            assertTrue(copyItems(popup(widget, 2, 0)).isEmpty())
        } finally { widget.close(); plain.close() }
    }

    @Test
    fun `right click chooses the command under the pointer on a shared row`() = onEdt {
        val copied = mutableListOf<String>()
        val commands = TerminalCommands { copied += it }.apply {
            addMessage("Run `runner --list` or `runner --check`.")
        }
        val widget = terminal(64, 12, commands)
        try {
            feed(widget, "Run runner --list or runner --check.\r\nReady.\r\n")
            paint(widget)
            copyItem(widget, 0, 5).doClick(0)
            copyItem(widget, 0, 22).doClick(0)
            assertEquals(listOf("runner --list", "runner --check"), copied)
            assertTrue(copyItems(popup(widget, 0, 18)).isEmpty())
        } finally { widget.close() }
    }

    @Test
    fun `partly visible scripts copy all original lines from scrollback`() = onEdt {
        val original = (1..18).joinToString("\n") { "printf 'entry $it'" }
        val copied = mutableListOf<String>()
        val widget = terminal(48, 12, TerminalCommands { copied += it }.apply { addMessage("```bash\n$original\n```") })
        try {
            feed(widget, original.replace("\n", "\r\n") + "\r\nReady.\r\n")
            paint(widget)
            copyItem(widget, 0, 3).doClick(0)
            widget.terminalPanel.verticalScrollModel.value = -widget.terminalTextBuffer.historyLinesCount
            paint(widget)
            copyItem(widget, 0, 3).doClick(0)
            assertEquals(listOf(original, original), copied)
        } finally { widget.close() }
    }

    @Test
    fun `right click resolves current buffer after a redraw and reflow`() = onEdt {
        val original = "runner --target=sample --mode=inspect --message='two  spaces'"
        val copied = mutableListOf<String>()
        val widget = terminal(72, 12, TerminalCommands { copied += it }.apply { addMessage("Run `$original`.") })
        try {
            feed(widget, original + "\r\nReady.\r\n")
            paint(widget)
            widget.terminal.resize(TermSize(36, 12), RequestOrigin.User)
            paint(widget)
            copyItem(widget, 1, 3).doClick(0)
            assertEquals(listOf(original), copied)
            feed(widget, "\u001b[2J\u001b[HNothing to copy.\r\n")
            paint(widget)
            assertTrue(copyItems(popup(widget, 0, 3)).isEmpty())
        } finally { widget.close() }
    }

    @Test
    fun `command menu keeps the terminal and hyperlink actions`() = onEdt {
        val opened = mutableListOf<String>()
        val original = "fetch https://docs.example.test/start"
        val settings = NopTerminalSettings(Color.WHITE, Color.BLACK, Color.BLUE)
        val widget = NopTerminalWidget(72, 12, settings).apply {
            showCommandCopies(TerminalCommands().apply { addMessage("Run `$original`.") })
            addAsyncHyperlinkFilter(CellStylePreservingLinks(terminalTextBuffer,
                UrlHyperlinkFilter { opened += it }, settings.hyperlinkColor, settings.hyperlinkHighlightingMode))
        }
        try {
            feed(widget, original + "\r\nReady.\r\n")
            paint(widget)
            val menu = popup(widget, 0, 12)
            val items = menu.components.filterIsInstance<javax.swing.JMenuItem>()
            assertTrue(items.map { it.text }.containsAll(listOf("Copy command", "Copy", "Paste", "Open Link", "Copy Link Address")))
            items.single { it.text == "Open Link" }.doClick(0)
            assertEquals(listOf("https://docs.example.test/start"), opened)
        } finally { widget.close() }
    }

    @Test
    fun `native right click copies without selecting text or sending mouse reports`() {
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
            val point = onEdt {
                widget.terminalPanel.locationOnScreen.apply {
                    translate(widget.terminalPanel.pixelWidth / 72 * 6, widget.terminalPanel.pixelHeight / 12 * 2 + 5)
                }
            }
            robot.mouseMove(point.x, point.y)
            robot.mousePress(InputEvent.BUTTON3_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK)
            robot.waitForIdle()
            val menu = onEdt {
                javax.swing.MenuSelectionManager.defaultManager().selectedPath.filterIsInstance<javax.swing.JPopupMenu>().single()
            }
            val itemPoint = onEdt {
                copyItems(menu).single().let { item -> item.locationOnScreen.apply { translate(item.width / 2, item.height / 2) } }
            }
            savePreview(robot.createScreenCapture(onEdt { frame.bounds }), "context-menu")
            robot.mouseMove(itemPoint.x, itemPoint.y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            robot.waitForIdle()
            onEdt {
                assertEquals(original, Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor))
                assertTrue(widget.terminalPanel.selection == null)
                assertTrue(sent.isEmpty(), "right-clicking Copy must not send input to the CLI")
            }
            robot.mouseMove(point.x, point.y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            robot.waitForIdle()
            assertTrue(sent.isNotEmpty(), "the simulated CLI really has mouse reporting enabled")
        } finally { onEdt { frame.dispose(); widget.close() } }
    }

    private fun popup(widget: NopTerminalWidget, row: Int, column: Int): javax.swing.JPopupMenu {
        val panel = widget.terminalPanel as NopTerminalPanel
        val x = panel.pixelWidth / widget.terminalTextBuffer.width * column + 5
        val y = panel.pixelHeight / widget.terminalTextBuffer.height * row + 5
        return panel.commandCopyPopup(java.awt.event.MouseEvent(panel, java.awt.event.MouseEvent.MOUSE_PRESSED,
            0, InputEvent.BUTTON3_DOWN_MASK, x, y, 1, true, java.awt.event.MouseEvent.BUTTON3))
    }

    private fun copyItems(menu: javax.swing.JPopupMenu) =
        menu.components.filterIsInstance<javax.swing.JMenuItem>().filter { it.text in setOf("Copy command", "Copy code") }

    private fun copyItem(widget: NopTerminalWidget, row: Int, column: Int) = copyItems(popup(widget, row, column)).single()

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
