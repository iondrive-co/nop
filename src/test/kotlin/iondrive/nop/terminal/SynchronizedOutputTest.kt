package iondrive.nop.terminal

import com.jediterm.terminal.ArrayTerminalDataStream
import com.jediterm.terminal.TtyBasedArrayDataStream
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.terminal.util.CharUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

class SynchronizedOutputTest {
    @Test
    fun `a handed-over terminal stays hidden through recorded redraw boundaries`() {
        val output = SynchronizedOutput()
        output.replay(true)
        repeat(3) {
            output.begin()
            output.end()
            assertTrue(output.active, "recorded frames must not reveal the unfinished restoration")
        }
        output.replay(false)
        assertFalse(output.active)
        output.replay(true)
        output.begin()
        output.replay(false)
        assertTrue(output.active, "a live frame that spans the handover keeps painting suspended")
        output.end()
        assertFalse(output.active)
    }

    @Test
    fun `quick redraws need no indicator and a broken frame expires after output stops`() {
        var now = 0L
        val changes = mutableListOf<Boolean>()
        val output = SynchronizedOutput(changes::add) { now }
        output.begin()
        assertFalse(output.showProgress)
        now = 200
        assertTrue(output.showProgress)
        repeat(5) {
            now += 1_000
            output.touch()
            output.expire()
            assertTrue(output.active, "a long redraw continues while output arrives")
        }
        now += 2_001
        output.expire()
        assertFalse(output.active)
        output.end()
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `reset is observed only after the final screen is emulated even with one character reads`() = onEdt {
        for (chunk in listOf(1, 7, 1_024)) {
            val widget = widget()
            try {
                var completed = false
                val output = SynchronizedOutput(changed = { active ->
                    if (!active) {
                        completed = true
                        assertTrue(screen(widget).contains("Final prompt"))
                        assertTrue(widget.terminalTextBuffer.historyLinesCount > 150)
                    }
                })
                val text = "\u001b[?2026h" + (1..200).joinToString("") { "History row $it 雨\r\n" } +
                    "Final prompt\u001b[?2026l"
                var offset = 0
                val connector = object : TtyConnector {
                    override fun read(buf: CharArray, start: Int, length: Int): Int {
                        if (offset == text.length) return -1
                        val size = minOf(length, chunk, text.length - offset)
                        text.toCharArray(buf, start, offset, offset + size)
                        offset += size
                        return size
                    }
                    override fun write(bytes: ByteArray) = Unit
                    override fun write(text: String) = Unit
                    override fun isConnected() = offset < text.length
                    override fun waitFor() = 0
                    override fun ready() = true
                    override fun getName() = "synchronized-output"
                    override fun close() = Unit
                }
                val emulator = JediEmulator(SynchronizedOutputStream(TtyBasedArrayDataStream(connector), output), widget.terminal)
                while (emulator.hasNext()) emulator.next()
                assertTrue(completed)
                assertFalse(output.active)
                assertEquals("History row 1 雨", widget.terminalTextBuffer.getLine(-widget.terminalTextBuffer.historyLinesCount)
                    .text.replace(CharUtils.DWC.toString(), ""))
            } finally { widget.close() }
        }
    }

    @Test
    fun `mode text in titles and string controls does not hide the terminal`() {
        val output = SynchronizedOutput()
        for (text in listOf("\u001b]2;example \u001b[?2026h\u0007", "\u001bPexample \u001b[?2026h\u001b\\")) {
            val stream = SynchronizedOutputStream(ArrayTerminalDataStream(text.toCharArray()), output)
            repeat(text.length) { stream.char }
            assertFalse(output.active)
        }
        val begin = "\u001b[?25;2026h"
        val stream = SynchronizedOutputStream(ArrayTerminalDataStream(begin.toCharArray()), output)
        while (!stream.isEmpty) stream.char
        assertTrue(output.active)
    }

    @Test
    fun `a history redraw stays hidden until complete and retains every scrollback row`() = onEdt {
        val widget = widget()
        try {
            val panel = widget.terminalPanel as NopTerminalPanel
            fun feed(text: String) {
                val emulator = JediEmulator(SynchronizedOutputStream(ArrayTerminalDataStream(text.toCharArray()), panel.synchronizedOutput), widget.terminal)
                while (emulator.hasNext()) emulator.next()
            }
            feed("Previous screen\u001b[?25l")
            val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_RGB)
            fun paint() { image.createGraphics().let { try { panel.paintComponent(it) } finally { it.dispose() } } }
            paint()
            val before = pixels(image)
            feed("\u001b[?2026h\u001b[2J\u001b[H" + (1..300).joinToString("") { "Example reply $it\r\n" } + "Current prompt")
            val rows = (-widget.terminalTextBuffer.historyLinesCount until widget.terminalTextBuffer.height)
                .map { widget.terminalTextBuffer.getLine(it).text }
            paint()
            val during = pixels(image)
            feed("\u001b[?2026l")
            paint()
            assertFalse(before.contentEquals(pixels(image)))
            assertFalse(during.contentEquals(pixels(image)), "the in-progress redraw must not reveal partial history")
            assertEquals(rows, (-widget.terminalTextBuffer.historyLinesCount until widget.terminalTextBuffer.height)
                .map { widget.terminalTextBuffer.getLine(it).text })
            assertTrue(screen(widget).contains("Current prompt"))
            assertEquals(0, panel.verticalScrollModel.value)
        } finally { widget.close() }
    }

    private fun widget() = NopTerminalWidget(48, 12, NopTerminalSettings(Color.WHITE, Color.BLACK, Color.BLUE)).apply {
        terminalPanel.setSize(terminalPanel.preferredSize)
    }
    private fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    private fun screen(widget: NopTerminalWidget) = (0 until widget.terminalTextBuffer.height)
        .joinToString("\n") { widget.terminalTextBuffer.getLine(it).text }
    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
