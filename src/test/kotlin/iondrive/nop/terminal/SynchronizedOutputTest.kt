package iondrive.nop.terminal

import com.jediterm.terminal.ArrayTerminalDataStream
import com.jediterm.terminal.TtyBasedArrayDataStream
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.util.CharUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

class SynchronizedOutputTest {
    @Test
    fun `programs can negotiate synchronized redraws and query their current mode`() {
        val output = SynchronizedOutput()
        val reports = mutableListOf<String>()
        val query = "\u001b[?2026\$p"
        val text = query + "\u001b[?2026h" + query + "reply" + "\u001b[?2026l" + query + "!"
        val stream = SynchronizedOutputStream(ArrayTerminalDataStream(text.toCharArray()), output, reports::add)
        val visible = buildString { while (!stream.isEmpty) append(stream.char) }

        assertEquals("reply!", visible, "negotiation and mode controls must not appear as message text")
        assertEquals(listOf("\u001b[?2026;2\$y", "\u001b[?2026;1\$y", "\u001b[?2026;2\$y"), reports)
        assertFalse(output.active)

        output.replay(true)
        assertEquals(2, output.modeStatus(), "holding restoration is independent of the program's requested mode")
    }

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
    fun `an update whose program stops writing expires so the terminal is painted again`() {
        var now = 0L
        val changes = mutableListOf<Boolean>()
        val output = SynchronizedOutput(changes::add) { now }
        output.begin()
        repeat(5) {
            now += 1_000
            output.touch()
            output.expire()
            assertTrue(output.active, "a long redraw continues while output arrives")
        }
        assertEquals(5_000, output.age)
        now += 2_001
        output.expire()
        assertFalse(output.active)
        output.end()
        assertEquals(listOf(true, false), changes)

        output.begin()
        repeat(29) {
            now += 1_000
            output.touch()
            output.expire()
        }
        assertTrue(output.active)
        now += 1_000
        output.touch()
        output.expire()
        assertFalse(output.active, "output that never finishes its update is shown after all")
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
        val replies = mutableListOf<String>()
        for (text in listOf("\u001b]2;example \u001b[?2026h\u0007", "\u001bPexample \u001b[?2026h\u001b\\",
            "\u001b]2;example \u001b[?2026\$p\u0007", "\u001bPexample \u001b[?2026\$p\u001b\\")) {
            val stream = SynchronizedOutputStream(ArrayTerminalDataStream(text.toCharArray()), output, replies::add)
            repeat(text.length) { stream.char }
            assertFalse(output.active)
        }
        assertTrue(replies.isEmpty(), "a query quoted inside a string control is not a request")
        val begin = "\u001b[?25;2026h"
        val stream = SynchronizedOutputStream(ArrayTerminalDataStream(begin.toCharArray()), output)
        while (!stream.isEmpty) stream.char
        assertTrue(output.active)
    }

    @Test
    fun `a long redraw leaves the last finished frame on screen until it is complete`() {
        lateinit var widget: NopTerminalWidget
        lateinit var panel: NopTerminalPanel
        onEdt {
            widget = widget()
            panel = widget.terminalPanel as NopTerminalPanel
            feed(panel, "Previous screen\u001b[?25l")
            assertTrue(repaintDue(panel), "ordinary output is painted on the next tick")
            feed(panel, "\u001b[?2026h\u001b[3J\u001b[2J\u001b[H" + (1..300).joinToString("") { "Example reply $it\r\n" } +
                "Current prompt")
            assertFalse(repaintDue(panel), "nothing is painted while the redraw is in progress, not even output from before it")
        }
        try {
            Thread.sleep(150)
            onEdt {
                assertTrue(pixels(paint(panel)).all { it == Color.WHITE.rgb },
                    "a paint Swing asks for part way through a long redraw shows the background, not the rows printed so far")
                feed(panel, "\u001b[?2026l")
                assertTrue(repaintDue(panel), "the finished redraw is painted on the next tick")
            }
            onEdt {
                val buffer = widget.terminalTextBuffer
                assertEquals(0, panel.verticalScrollModel.value)
                assertTrue(screen(widget).contains("Current prompt"))
                assertEquals("Example reply 1", buffer.getLine(-buffer.historyLinesCount).text.trimEnd())
            }
        } finally { onEdt { widget.close() } }
    }

    @Test
    fun `a reader scrolled up stays as far above the bottom when the conversation is printed again`() {
        val conversation = (1..300).joinToString("") { "Example reply $it\r\n" } + "Current prompt"
        lateinit var widget: NopTerminalWidget
        lateinit var panel: NopTerminalPanel
        var before = 0
        var reading = ""
        onEdt {
            widget = widget()
            panel = widget.terminalPanel as NopTerminalPanel
            feed(panel, conversation)
            paint(panel)
        }
        // After the scroll to the bottom a new terminal queues for itself.
        onEdt {
            repeat(10) { wheel(panel, -1) }
            paint(panel)
            before = panel.verticalScrollModel.value
            reading = widget.terminalTextBuffer.getLine(before).text
        }
        try {
            assertTrue(before < -20 && before > -widget.terminalTextBuffer.historyLinesCount)
            onEdt { feed(panel, "\u001b[?2026h\u001b[3J\u001b[2J\u001b[H" + conversation + "\u001b[?2026l") }
            onEdt {
                assertEquals(before, panel.verticalScrollModel.value, "not thrown to the top of the reprinted scrollback")
                assertEquals(reading, widget.terminalTextBuffer.getLine(panel.verticalScrollModel.value).text)
                assertTrue(widget.userScrolledUp)
            }
        } finally { onEdt { widget.close() } }
    }

    private fun feed(panel: NopTerminalPanel, text: String) {
        val widget = SwingUtilities.getAncestorOfClass(NopTerminalWidget::class.java, panel) as NopTerminalWidget
        val emulator = JediEmulator(SynchronizedOutputStream(ArrayTerminalDataStream(text.toCharArray()), panel.synchronizedOutput), widget.terminal)
        while (emulator.hasNext()) emulator.next()
    }

    private fun paint(panel: NopTerminalPanel): BufferedImage {
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { try { panel.paintComponent(it) } finally { it.dispose() } }
        return image
    }

    private fun wheel(panel: NopTerminalPanel, rotation: Int) {
        panel.dispatchEvent(MouseWheelEvent(panel, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(),
            0, 20, 20, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation))
    }

    /** Whether JediTerm's timer will paint the terminal on its next tick. */
    private fun repaintDue(panel: TerminalPanel): Boolean =
        (TerminalPanel::class.java.getDeclaredField("needRepaint").apply { isAccessible = true }.get(panel) as AtomicBoolean).get()

    private fun widget() = NopTerminalWidget(48, 12, NopTerminalSettings(Color.WHITE, Color.BLACK, Color.BLUE)).apply {
        terminalPanel.setSize(terminalPanel.preferredSize)
    }
    private fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    private fun screen(widget: NopTerminalWidget) = (0 until widget.terminalTextBuffer.height)
        .joinToString("\n") { widget.terminalTextBuffer.getLine(it).text }
    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
