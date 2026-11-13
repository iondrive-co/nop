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
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.JScrollBar
import javax.swing.SwingUtilities

class TerminalScrollingTest {
    @Test
    fun `refreshing table rows during offscreen synchronized output preserves the entire table`() {
        for (scale in listOf(1.0, 1.25, 2.0)) assertTableRefreshStable(scale)
    }

    private fun assertTableRefreshStable(scale: Double) {
        lateinit var widget: NopTerminalWidget
        lateinit var expected: IntArray
        lateinit var image: BufferedImage
        lateinit var visibleRows: List<String>
        lateinit var table: List<String>
        onEdt {
            widget = terminal()
            table = listOf(
                "┌──────────────┬─────────┐",
                "│ Example item │ Count   │",
                "├──────────────┼─────────┤",
                "│ Amber        │ 12      │",
                "│ Birch        │ 34      │",
                "│ Cedar        │ 56      │",
                "│ Dune         │ 78      │",
                "│ Elm          │ 90      │",
                "└──────────────┴─────────┘",
            )
            feed(widget, ((1..40).map { "Earlier line $it" } + table +
                (1..41).map { "Later line $it" }).joinToString("\r\n") + "\r\n\u001b[?25l")
            paint(widget)
        }
        onEdt {
            wheel(widget, -13)
            image = paint(widget, scale)
            expected = pixels(image)
            val buffer = widget.terminalTextBuffer
            val origin = widget.terminalPanel.verticalScrollModel.value
            visibleRows = (origin until origin + buffer.height).map { buffer.getLine(it).text }
            assertEquals(table.first(), visibleRows.first())
            val panel = widget.terminalPanel as NopTerminalPanel
            val output = "\u001b[?2026hNew output below the visible table\r\n"
            val emulator = JediEmulator(SynchronizedOutputStream(ArrayTerminalDataStream(output.toCharArray()), panel.synchronizedOutput), widget.terminal)
            while (emulator.hasNext()) emulator.next()
            panel.flushPendingScroll()
        }
        try {
            // An expose or partial Swing repaint can arrive while the program's frame is open.
            Thread.sleep(150)
            onEdt {
                val panel = widget.terminalPanel as NopTerminalPanel
                panel.synchronizedOutput.touch()
                assertTrue(panel.synchronizedOutput.active)
                assertTrue(panel.synchronizedOutput.age >= 100)
                image.createGraphics().let { graphics ->
                    try {
                        graphics.scale(scale, scale)
                        graphics.clip = Rectangle(0, 40, panel.width, 80)
                        panel.paintComponent(graphics)
                    } finally { graphics.dispose() }
                }
                val buffer = widget.terminalTextBuffer
                val origin = panel.verticalScrollModel.value
                assertEquals(visibleRows, (origin until origin + buffer.height).map { buffer.getLine(it).text },
                    "the writer is changing only rows below the table")
                assertArrayEquals(expected, pixels(image), "refreshing a table must not erase its rows")
            }
        } finally { onEdt { widget.close() } }
    }

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

    @Test
    fun `a partial repaint after scrolling preserves pixels outside the requested region`() = onEdt {
        val widget = terminal()
        try {
            feed(widget, (1..70).joinToString("") {
                "\u001b[48;2;55;55;55m❯ Example question $it\u001b[0m\r\n" +
                    "  Answer $it with unchanged text.\r\n"
            } + "\u001b[?25l")
            val panel = widget.terminalPanel
            for (scale in listOf(1.0, 1.25, 2.0)) {
                widget.scrollToBottom()
                fun frame(): BufferedImage {
                    val image = BufferedImage((panel.width * scale).toInt(), (panel.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
                    image.createGraphics().let { graphics ->
                        try {
                            graphics.scale(scale, scale)
                            panel.paintComponent(graphics)
                        } finally { graphics.dispose() }
                    }
                    return image
                }
                val actual = frame()
                val before = pixels(actual)
                wheel(widget, -1)
                val expected = frame()
                val region = Rectangle(24, 40, 120, 80)
                actual.createGraphics().let { graphics ->
                    try {
                        graphics.scale(scale, scale)
                        graphics.clip = region
                        panel.paintComponent(graphics)
                        assertEquals(region, graphics.clipBounds, "the caller's repaint region is preserved")
                    } finally { graphics.dispose() }
                }
                val painted = Rectangle((region.x * scale).toInt(), (region.y * scale).toInt(),
                    (region.width * scale).toInt(), (region.height * scale).toInt())
                for (y in 0 until actual.height) for (x in 0 until actual.width) {
                    val wanted = if (painted.contains(x, y)) expected.getRGB(x, y) else before[y * actual.width + x]
                    assertEquals(wanted, actual.getRGB(x, y), "pixel $x,$y at scale $scale")
                }
            }
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

    private fun paint(widget: NopTerminalWidget, scale: Double = 1.0): BufferedImage {
        val panel = widget.terminalPanel
        val image = BufferedImage((panel.width * scale).toInt(), (panel.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { graphics ->
            try { graphics.scale(scale, scale); panel.paintComponent(graphics) } finally { graphics.dispose() }
        }
        return image
    }

    private fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
