package iondrive.nop.terminal

import com.jediterm.terminal.ArrayTerminalDataStream
import com.jediterm.terminal.emulator.JediEmulator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.JScrollBar
import javax.swing.SwingUtilities

class TerminalScrollingTest {
    @Test
    fun `output received at the bottom does not become an extra scroll on the next wheel event`() = onEdt {
        val widget = terminal()
        try {
            feed(widget, (1..80).joinToString("") { "Result $it\r\n" })
            paint(widget)
            feed(widget, (1..5).joinToString("") { "New result $it\r\n" })
            wheel(widget, -1)
            paint(widget)
            assertEquals(-3, widget.terminalPanel.verticalScrollModel.value,
                "only the user's three-row wheel movement should move the viewport")
            wheel(widget, 1)
            paint(widget)
            assertEquals(0, widget.terminalPanel.verticalScrollModel.value)
        } finally { widget.close() }
    }

    @Test
    fun `new output keeps the same history text at the top of a scrolled viewport`() = onEdt {
        val widget = terminal()
        try {
            feed(widget, (1..80).joinToString("") { "Result $it\r\n" })
            paint(widget)
            wheel(widget, -1)
            paint(widget)
            val model = widget.terminalPanel.verticalScrollModel
            val top = widget.terminalTextBuffer.getLine(model.value).text
            feed(widget, (1..5).joinToString("") { "New result $it\r\n" })
            paint(widget)
            assertEquals(-8, model.value)
            assertEquals(top, widget.terminalTextBuffer.getLine(model.value).text)
        } finally { widget.close() }
    }

    @Test
    fun `scrolling down accounts for output received before the wheel event`() = onEdt {
        val widget = terminal()
        try {
            feed(widget, (1..80).joinToString("") { "Result $it\r\n" })
            paint(widget)
            wheel(widget, -1)
            paint(widget)
            feed(widget, (1..5).joinToString("") { "New result $it\r\n" })
            wheel(widget, 1)
            paint(widget)
            assertEquals(-5, widget.terminalPanel.verticalScrollModel.value,
                "the wheel moves three rows from the history position preserved by incoming output")
        } finally { widget.close() }
    }

    @Test
    fun `scrolling a screen region or inserting a screen row does not move a history viewport`() = onEdt {
        val widget = terminal()
        try {
            feed(widget, (1..80).joinToString("") { "Result $it\r\n" })
            paint(widget)
            wheel(widget, -3)
            paint(widget)
            val model = widget.terminalPanel.verticalScrollModel
            val origin = model.value
            val top = widget.terminalTextBuffer.getLine(origin).text
            for (output in listOf("\u001b[3;10r\u001b[10;1H\r\n", "\u001b[r\u001b[1;1H\u001bM")) {
                feed(widget, output)
                paint(widget)
                assertEquals(origin, model.value)
                assertEquals(top, widget.terminalTextBuffer.getLine(model.value).text)
            }
        } finally { widget.close() }
    }

    @Test
    fun `idle wheel round trips preserve every buffer row and the rendered text`() = onEdt {
        val widget = terminal()
        try {
            val output = (1..45).joinToString("") {
                "\u001b[48;2;55;55;55m❯ Question $it 雨\u001b[0m\r\n" +
                    "\u001b[1mAnswer $it\u001b[0m with a wrapped description. ".repeat(3) + "\r\n"
            }
            feed(widget, output + "\u001b[?25l")
            val buffer = widget.terminalTextBuffer
            val rows = (-buffer.historyLinesCount until buffer.height).map { buffer.getLine(it).text }
            paint(widget)
            val frames = mutableMapOf<Int, IntArray>()
            fun checkFrame() {
                val origin = widget.terminalPanel.verticalScrollModel.value
                val actual = pixels(paint(widget))
                frames.putIfAbsent(origin, actual)?.let { assertArrayEquals(it, actual, "viewport $origin") }
            }
            repeat(3) {
                repeat(8) { wheel(widget, -1); checkFrame() }
                repeat(8) { wheel(widget, 1); checkFrame() }
                assertEquals(0, widget.terminalPanel.verticalScrollModel.value)
                assertEquals(rows, (-buffer.historyLinesCount until buffer.height).map { buffer.getLine(it).text })
            }
        } finally { widget.close() }
    }

    @Test
    fun `clicking the scrollbar track pauses following output and keeps the same history text`() = onEdt {
        val widget = terminal()
        try {
            feed(widget, (1..80).joinToString("") { "Result $it\r\n" })
            paint(widget)
            fun scrollbar(component: Component): JScrollBar? = component as? JScrollBar ?: (component as? Container)
                ?.components?.firstNotNullOfOrNull(::scrollbar)
            val bar = checkNotNull(scrollbar(widget))
            bar.setSize(10, widget.terminalPanel.height)
            bar.doLayout()
            bar.dispatchEvent(MouseEvent(bar, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK, 5, 30, 1, false, MouseEvent.BUTTON1))
            bar.dispatchEvent(MouseEvent(bar, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                0, 5, 30, 1, false, MouseEvent.BUTTON1))
            assertTrue(widget.userScrolledUp, "track clicks are user scrolling even without a thumb drag")
            paint(widget)
            val model = widget.terminalPanel.verticalScrollModel
            val top = widget.terminalTextBuffer.getLine(model.value).text
            feed(widget, (1..5).joinToString("") { "New result $it\r\n" })
            paint(widget)
            assertEquals(top, widget.terminalTextBuffer.getLine(model.value).text)
        } finally { widget.close() }
    }

    private fun terminal() = NopTerminalWidget(60, 12, NopTerminalSettings(Color.WHITE, Color.BLACK, Color.BLUE)).apply {
        userMessages = UserMessages(emptyList())
        terminalPanel.setSize(terminalPanel.preferredSize)
    }

    private fun wheel(widget: NopTerminalWidget, rotation: Int) {
        val panel = widget.terminalPanel
        panel.dispatchEvent(MouseWheelEvent(panel, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(),
            0, 20, 20, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation))
    }

    private fun feed(widget: NopTerminalWidget, text: String) {
        val emulator = JediEmulator(ArrayTerminalDataStream(text.toCharArray()), widget.terminal)
        while (emulator.hasNext()) emulator.next()
    }

    private fun paint(widget: NopTerminalWidget): BufferedImage {
        val panel = widget.terminalPanel
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { graphics ->
            try { panel.paintComponent(graphics) } finally { graphics.dispose() }
        }
        return image
    }

    private fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
