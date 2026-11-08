package iondrive.nop.terminal

import com.jediterm.terminal.ArrayTerminalDataStream
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.terminal.model.JediTerminal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import javax.swing.SwingUtilities

class TerminalSnapshotTest {
    @Test
    fun `saved cursors and delayed wrapping still apply to future output`() = onEdt {
        for (initial in listOf("\u001b[3;4H\u001b[32m\u001b7\u001b[6;1Hready", "x".repeat(48), "x".repeat(46) + "雨", "x".repeat(46) + "🌿")) {
            val old = widget()
            val restored = widget()
            try {
                feed(old, initial)
                feed(restored, String(TerminalSnapshot.replay(old.terminal as JediTerminal, ByteArray(0), initial.toByteArray())!!))
                val continuation = if ("ready" in initial) "\u001b8updated" else "next"
                feed(old, continuation); feed(restored, continuation)
                assertSameContents(old, restored)
                assertEquals(old.terminal.cursorPosition, restored.terminal.cursorPosition)
            } finally { old.close(); restored.close() }
        }
    }

    @Test
    fun `redraws cannot evict rendered scrollback from a restart`() = onEdt {
        val old = widget()
        val restored = widget()
        try {
            val recorder = PtyRecorder(capacity = 1024)
            val history = (1..180).joinToString("\r\n") { "Earlier result $it" }
            val redraw = "\u001b[12;1H\u001b[38;2;19;53;87mcurrent status\u001b[0m\u001b[K"
            val output = history + "\r\n\u001b[?2004h" + redraw.repeat(300)
            recorder.seed(output.toByteArray())
            feed(old, output)
            assertFalse(String(recorder.replay()).contains("Earlier result 1\r"))
            val replay = TerminalSnapshot.replay(old.terminal as JediTerminal, recorder.modePrefix(), recorder.replay())!!
            feed(restored, String(replay))
            assertSameContents(old, restored)
            assertEquals(old.terminal.cursorPosition, restored.terminal.cursorPosition)
            assertEquals(old.terminalTextBuffer.getStyleAt(0, 11), restored.terminalTextBuffer.getStyleAt(0, 11))
            assertTrue(String(replay).contains("\u001b[?2004h"))
        } finally { old.close(); restored.close() }
    }

    @Test
    fun `wrapped lines wide text and continued output survive restoration`() = onEdt {
        val old = widget()
        val restored = widget()
        try {
            val output = "Heading\r\n" + "wrapped text 雨 🌿 ".repeat(90) + "\r\n\u001b[1;4;31mCurrent line"
            feed(old, output)
            val replay = TerminalSnapshot.replay(old.terminal as JediTerminal, ByteArray(0), output.toByteArray())!!
            feed(restored, String(replay))
            assertSameContents(old, restored)
            val continuation = " continued\u001b[0m\r\nAnother result"
            feed(old, continuation); feed(restored, continuation)
            assertSameContents(old, restored)
        } finally { old.close(); restored.close() }
    }

    @Test
    fun `a color sequence split by the restart is completed by new output`() = onEdt {
        val old = widget()
        val restored = widget()
        try {
            feed(old, "Earlier result\r\nPrompt")
            val partial = "\u001b[38;2;19;53;"
            val replay = TerminalSnapshot.replay(old.terminal as JediTerminal, ByteArray(0), partial.toByteArray())!!
            feed(restored, String(replay) + "87m result")
            feed(old, partial + "87m result")
            assertSameContents(old, restored)
            val expected = old.terminalTextBuffer.getStyleAt(8, 1)!!
            val actual = restored.terminalTextBuffer.getStyleAt(8, 1)!!
            assertEquals(expected.foreground, actual.foreground, "foreground")
            assertEquals(expected.background!!.toColor(),
                (actual.background ?: (restored.terminal as JediTerminal).styleState.defaultBackground).toColor(), "background")
            for (option in com.jediterm.terminal.TextStyle.Option.entries) assertEquals(expected.hasOption(option), actual.hasOption(option))
        } finally { old.close(); restored.close() }
    }

    private fun widget() = NopTerminalWidget(48, 12, NopTerminalSettings(Color.WHITE, Color.BLACK, Color.BLUE))
    private fun feed(widget: NopTerminalWidget, output: String) {
        val emulator = JediEmulator(ArrayTerminalDataStream(output.toCharArray()), widget.terminal)
        while (emulator.hasNext()) emulator.next()
    }
    private fun assertSameContents(old: NopTerminalWidget, restored: NopTerminalWidget) {
        val a = old.terminalTextBuffer
        val b = restored.terminalTextBuffer
        assertEquals(a.historyLinesCount, b.historyLinesCount)
        for (row in -a.historyLinesCount until a.height) {
            assertEquals(a.getLine(row).text, b.getLine(row).text, "row $row")
            assertEquals(a.getLine(row).isWrapped, b.getLine(row).isWrapped, "wrap at $row")
        }
    }
    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
