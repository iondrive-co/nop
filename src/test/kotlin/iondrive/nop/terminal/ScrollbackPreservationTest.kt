package iondrive.nop.terminal

import com.jediterm.terminal.ArrayTerminalDataStream
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.terminal.model.JediTerminal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class ScrollbackPreservationTest {
    @Test
    fun `destructive 3J compaction preserves prior scrollback and live screen lines`() {
        val conversation = (1..80).joinToString("\r\n") { "Transcript line $it discussing widget layout" }
        withWidget { widget, panel ->
            var beforeCount = 0
            onEdt {
                panel.preserveScrollback = true
                panel.synchronizedOutput.clearsStartUpdates = true
                feed(panel, conversation)
                beforeCount = allLines(widget).size
                assertTrue(widget.terminalTextBuffer.historyLinesCount > 60)

                feed(panel, "\u001b[2J\u001b[3J\u001b[H\u001b[?2026h" +
                    "Conversation compacted\r\n" +
                    "────────────────────────\r\n" +
                    "> \u001b[?2026l")
            }
            onEdt {
                val after = allLines(widget)
                assertTrue(after.any { it.contains("Transcript line 1 discussing widget layout") },
                    "oldest pre-compaction scrollback must survive ESC[3J]")
                assertTrue(after.any { it.contains("Transcript line 80 discussing widget layout") },
                    "pre-compaction viewport lines must survive ESC[3J]")
                assertTrue(screenText(widget).contains("Conversation compacted"))
                assertEquals(1, after.count { it.contains("Transcript line 80 discussing widget layout") })
                assertTrue(widget.terminalTextBuffer.historyLinesCount >= beforeCount - 1)
                assertEquals(-widget.terminalTextBuffer.historyLinesCount, panel.verticalScrollModel.minimum)
            }
        }
    }

    @Test
    fun `full conversation reprint after 3J does not duplicate scrollback`() {
        val conversation = (1..80).joinToString("\r\n") { "Table row $it | Alpha | Beta" } + "\r\nCurrent prompt"
        withWidget { widget, panel ->
            onEdt {
                panel.preserveScrollback = true
                panel.synchronizedOutput.screenClearsStartUpdates = true
                feed(panel, conversation)

                feed(panel, "\u001b[H\u001b[2J\u001b[3J\u001b[H\u001b[?2026h" + conversation +
                    "\r\nAppended reply 1\r\nAppended reply 2\u001b[?2026l")
            }
            onEdt {
                val after = allLines(widget)
                assertEquals(1, after.count { it.contains("Table row 1 | Alpha | Beta") },
                    "normal full redraws must not duplicate scrollback")
                assertEquals(1, after.count { it.contains("Table row 80 | Alpha | Beta") })
                assertTrue(after.any { it.contains("Appended reply 2") })
            }
        }
    }

    @Test
    fun `truncated 3J redraw preserves dropped earlier lines without duplicating the retained tail`() {
        val earlier = (1..10).joinToString("\r\n") { "Earlier turn $it details" }
        val retained = (1..60).joinToString("\r\n") { "Recent turn $it summary" }
        withWidget { widget, panel ->
            onEdt {
                panel.preserveScrollback = true
                panel.synchronizedOutput.screenClearsStartUpdates = true
                feed(panel, "$earlier\r\n$retained")

                feed(panel, "\u001b[2J\u001b[3J\u001b[H\u001b[?2026h$retained\r\nPost-compact prompt\u001b[?2026l")
            }
            onEdt {
                val after = allLines(widget)
                assertTrue(after.any { it.contains("Earlier turn 1 details") })
                assertTrue(after.any { it.contains("Earlier turn 10 details") })
                assertEquals(1, after.count { it.contains("Recent turn 1 summary") },
                    "the retained tail must not be duplicated at the seam")
                assertEquals(1, after.count { it.contains("Recent turn 60 summary") })
                assertTrue(after.indexOfFirst { it.contains("Earlier turn 10 details") } <
                    after.indexOfFirst { it.contains("Recent turn 1 summary") })
            }
        }
    }

    @Test
    fun `Claude classic in-place viewport wipe on compaction saves live viewport into scrollback`() {
        val historyPart = (1..40).joinToString("\r\n") { "Earlier message $it in scrollback" }
        val screenPart = (1..8).joinToString("\r\n") { "Latest reply line $it before compact" } +
            "\r\n────────────────────────\r\n❯ \r\n────────────────────────\r\n  bypass permissions"
        withWidget { widget, panel ->
            var historyBefore = 0
            onEdt {
                panel.preserveScrollback = true
                feed(panel, "$historyPart\r\n$screenPart")
                historyBefore = widget.terminalTextBuffer.historyLinesCount
                assertTrue(historyBefore > 30)

                val wipe = "\u001b[H" + "\u001b[2K\u001b[1B".repeat(widget.terminalTextBuffer.height) + "\u001b[H"
                feed(panel, "\u001b[?2026h$wipe" +
                    "✻ Conversation compacted (ctrl+o for history)\r\n" +
                    "────────────────────────\r\n❯ \r\n────────────────────────\r\n  bypass permissions\u001b[?2026l")
            }
            onEdt {
                val after = allLines(widget)
                assertTrue(widget.terminalTextBuffer.historyLinesCount > historyBefore,
                    "pre-compaction viewport rows must move into scrollback")
                assertTrue(after.any { it.contains("Earlier message 1 in scrollback") })
                assertTrue(after.any { it.contains("Latest reply line 1 before compact") })
                assertTrue(after.any { it.contains("Latest reply line 8 before compact") })
                assertTrue(screenText(widget).contains("Conversation compacted"))
                assertTrue(after.indexOfFirst { it.contains("Earlier message 40 in scrollback") } <
                    after.indexOfFirst { it.contains("Latest reply line 1 before compact") })
                assertTrue(after.indexOfFirst { it.contains("Latest reply line 8 before compact") } <
                    after.indexOfFirst { it.contains("Conversation compacted") })
            }
        }
    }

    @Test
    fun `unbracketed joined-row viewport wipe preserves pre-compaction viewport`() {
        val screenPart = (1..8).joinToString("\r\n") { "Joined wipe row $it before compact" }
        withWidget { widget, panel ->
            val restored = CountDownLatch(1)
            onEdt {
                panel.preserveScrollback = true
                panel.onScrollbackRestored = { restored.countDown() }
                feed(panel, screenPart)

                val wipe = "\u001b[H\u001b[2K" + "\u001b[K\u001b[1B".repeat(widget.terminalTextBuffer.height) + "\u001b[H"
                feed(panel, wipe + "✻ Conversation compacted\r\n❯ ")
            }
            assertTrue(restored.await(5, TimeUnit.SECONDS))
            onEdt {
                val after = allLines(widget)
                assertTrue(after.any { it.contains("Joined wipe row 1 before compact") })
                assertTrue(after.any { it.contains("Joined wipe row 8 before compact") })
                assertTrue(screenText(widget).contains("Conversation compacted"))
            }
        }
    }

    @Test
    fun `routine Claude classic in-place redraw does not duplicate viewport into scrollback`() {
        val historyPart = (1..40).joinToString("\r\n") { "Earlier message $it in scrollback" }
        val screenPart = (1..8).joinToString("\r\n") { "Visible answer line $it" } +
            "\r\n────────────────────────\r\n❯ \r\n────────────────────────"
        withWidget { widget, panel ->
            var historyBefore = 0
            onEdt {
                panel.preserveScrollback = true
                feed(panel, "$historyPart\r\n$screenPart")
                historyBefore = widget.terminalTextBuffer.historyLinesCount

                val wipe = "\u001b[H" + "\u001b[2K\u001b[1B".repeat(widget.terminalTextBuffer.height) + "\u001b[H"
                val updatedScreen = (1..8).joinToString("\r\n") { "Visible answer line $it" } +
                    "\r\nUpdated status line\r\n────────────────────────\r\n❯ "
                feed(panel, "\u001b[?2026h$wipe$updatedScreen\u001b[?2026l")
            }
            onEdt {
                assertEquals(historyBefore, widget.terminalTextBuffer.historyLinesCount,
                    "routine viewport redraw must not push duplicate lines into history")
                assertEquals(1, allLines(widget).count { it.contains("Visible answer line 1") })
            }
        }
    }

    @Test
    fun `scrolled-up reader stays anchored on the same historical rows across compaction`() {
        val conversation = (1..100).joinToString("\r\n") { "History entry $it | payload" }
        withWidget { widget, panel ->
            lateinit var readingBefore: List<String>
            onEdt {
                panel.preserveScrollback = true
                panel.synchronizedOutput.clearsStartUpdates = true
                feed(panel, conversation)
                paint(panel)
            }
            onEdt {
                repeat(10) { wheel(panel, -1) }
                paint(panel)
                readingBefore = visibleLines(widget, panel)
                assertTrue(panel.verticalScrollModel.value < -15)
            }
            onEdt {
                feed(panel, "\u001b[2J\u001b[3J\u001b[H\u001b[?2026hConversation compacted\r\n> \u001b[?2026l")
            }
            onEdt {
                assertEquals(readingBefore, visibleLines(widget, panel),
                    "reader scrolled into history must stay on the same lines after compaction")
                assertTrue(widget.userScrolledUp)
            }
        }
    }

    @Test
    fun `multiple consecutive compactions preserve the full chronological history`() {
        withWidget { widget, panel ->
            onEdt {
                panel.preserveScrollback = true
                panel.synchronizedOutput.clearsStartUpdates = true
                feed(panel, (1..30).joinToString("\r\n") { "Phase one row $it" })
                feed(panel, "\u001b[2J\u001b[3J\u001b[H\u001b[?2026hFirst compaction banner\r\n> \u001b[?2026l")
            }
            onEdt {
                feed(panel, "\r\n" + (1..30).joinToString("\r\n") { "Phase two row $it" })
                feed(panel, "\u001b[2J\u001b[3J\u001b[H\u001b[?2026hSecond compaction banner\r\n> \u001b[?2026l")
            }
            onEdt {
                val after = allLines(widget)
                val p1 = after.indexOfFirst { it.contains("Phase one row 1") }
                val b1 = after.indexOfFirst { it.contains("First compaction banner") }
                val p2 = after.indexOfFirst { it.contains("Phase two row 30") }
                val b2 = after.indexOfFirst { it.contains("Second compaction banner") }
                assertTrue(p1 >= 0 && b1 > p1 && p2 > b1 && b2 > p2,
                    "both pre-compaction phases must remain in chronological order ($p1, $b1, $p2, $b2)")
            }
        }
    }

    @Test
    fun `preserved pre-compaction scrollback survives TerminalSnapshot replay`() {
        val conversation = (1..60).joinToString("\r\n") { "Saved turn $it content" }
        withWidget { widget, panel ->
            onEdt {
                panel.preserveScrollback = true
                panel.synchronizedOutput.clearsStartUpdates = true
                feed(panel, conversation)
                feed(panel, "\u001b[2J\u001b[3J\u001b[H\u001b[?2026hConversation compacted\r\n> \u001b[?2026l")
            }
            onEdt {
                val snapshot = TerminalSnapshot.replay(widget.terminal as JediTerminal, ByteArray(0), ByteArray(0))
                assertNotNull(snapshot)

                val restored = widget()
                try {
                    val restoredPanel = restored.terminalPanel as NopTerminalPanel
                    feed(restoredPanel, snapshot!!.decodeToString())
                    val lines = allLines(restored)
                    assertTrue(lines.any { it.contains("Saved turn 1 content") })
                    assertTrue(lines.any { it.contains("Saved turn 60 content") })
                    assertTrue(screenText(restored).contains("Conversation compacted"))
                } finally { restored.close() }
            }
        }
    }

    @Test
    fun `tiny startup banner cleared for a large resumed transcript is not prepended`() {
        withWidget { widget, panel ->
            onEdt {
                panel.preserveScrollback = true
                panel.synchronizedOutput.screenClearsStartUpdates = true
                feed(panel, "Loading session...")

                val resumed = (1..60).joinToString("\r\n") { "Resumed conversation line $it" }
                feed(panel, "\u001b[2J\u001b[3J\u001b[H\u001b[?2026h$resumed\u001b[?2026l")
            }
            onEdt {
                val after = allLines(widget)
                assertFalse(after.any { it.contains("Loading session...") })
                assertEquals("Resumed conversation line 1", after.first().trimEnd())
            }
        }
    }

    private fun withWidget(block: (NopTerminalWidget, NopTerminalPanel) -> Unit) {
        lateinit var widget: NopTerminalWidget
        lateinit var panel: NopTerminalPanel
        onEdt {
            widget = widget()
            panel = widget.terminalPanel as NopTerminalPanel
        }
        try {
            block(widget, panel)
        } finally {
            onEdt { widget.close() }
        }
    }

    private fun widget() = NopTerminalWidget(56, 12, NopTerminalSettings(Color.WHITE, Color.BLACK, Color.BLUE)).apply {
        terminalPanel.setSize(terminalPanel.preferredSize)
    }

    private fun feed(panel: NopTerminalPanel, text: String) {
        val widget = SwingUtilities.getAncestorOfClass(NopTerminalWidget::class.java, panel) as NopTerminalWidget
        val emulator = JediEmulator(SynchronizedOutputStream(ArrayTerminalDataStream(text.toCharArray()), panel.synchronizedOutput), widget.terminal)
        while (emulator.hasNext()) emulator.next()
    }

    private fun allLines(widget: NopTerminalWidget): List<String> {
        val buffer = widget.terminalTextBuffer
        return (-buffer.historyLinesCount until buffer.height).map { buffer.getLine(it).text }
    }

    private fun screenText(widget: NopTerminalWidget): String =
        (0 until widget.terminalTextBuffer.height).joinToString("\n") { widget.terminalTextBuffer.getLine(it).text }

    private fun visibleLines(widget: NopTerminalWidget, panel: NopTerminalPanel): List<String> {
        val buffer = widget.terminalTextBuffer
        val origin = panel.verticalScrollModel.value
        return (origin until origin + buffer.height).map { buffer.getLine(it).text }
    }

    private fun paint(panel: NopTerminalPanel) {
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { try { panel.paintComponent(it) } finally { it.dispose() } }
    }

    private fun wheel(panel: NopTerminalPanel, rotation: Int) {
        panel.dispatchEvent(MouseWheelEvent(panel, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(),
            0, 20, 20, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation))
    }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
