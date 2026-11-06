package iondrive.nop.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.TtyConnector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.Color
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class TerminalResizeTest {
    @Test
    fun `a drag sends only its settled size and the PTY agrees with the buffer`() = withTerminal { widget, sizes ->
        SwingUtilities.invokeAndWait { widget.terminalStarter!!.postResize(TermSize(90, 30), RequestOrigin.User) }
        assertEquals(TermSize(90, 30), sizes.poll(5, TimeUnit.SECONDS))
        SwingUtilities.invokeAndWait {
            for (columns in 91..120) {
                widget.terminalStarter!!.postResize(TermSize(columns, 32), RequestOrigin.User)
            }
        }
        assertEquals(TermSize(120, 32), sizes.poll(5, TimeUnit.SECONDS))
        assertNull(sizes.poll(300, TimeUnit.MILLISECONDS), "intermediate sizes must not reach the CLI")
    }

    @Test
    fun `a drag returning to the original size needs no redraw`() = withTerminal { widget, sizes ->
        val original = TermSize(90, 30)
        SwingUtilities.invokeAndWait { widget.terminalStarter!!.postResize(original, RequestOrigin.User) }
        assertEquals(original, sizes.poll(5, TimeUnit.SECONDS))
        SwingUtilities.invokeAndWait {
            widget.terminalStarter!!.postResize(TermSize(100, 35), RequestOrigin.User)
            widget.terminalStarter!!.postResize(original, RequestOrigin.User)
        }
        assertNull(sizes.poll(400, TimeUnit.MILLISECONDS))
        assertEquals(original.columns, widget.terminalTextBuffer.width)
        assertEquals(original.rows, widget.terminalTextBuffer.height)
    }

    @Test
    fun `a remote size change does not suppress restoring the pane size`() = withTerminal { widget, sizes ->
        val original = TermSize(90, 30)
        SwingUtilities.invokeAndWait { widget.terminalStarter!!.postResize(original, RequestOrigin.User) }
        assertEquals(original, sizes.poll(5, TimeUnit.SECONDS))
        SwingUtilities.invokeAndWait {
            widget.terminal.resize(TermSize(110, 30), RequestOrigin.Remote)
            widget.terminalStarter!!.postResize(original, RequestOrigin.User)
        }
        assertEquals(original, sizes.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `stopping a terminal cancels its pending resize`() = withTerminal { widget, sizes ->
        SwingUtilities.invokeAndWait { widget.terminalStarter!!.postResize(TermSize(90, 30), RequestOrigin.User) }
        assertEquals(TermSize(90, 30), sizes.poll(5, TimeUnit.SECONDS))
        SwingUtilities.invokeAndWait {
            widget.terminalStarter!!.postResize(TermSize(120, 32), RequestOrigin.User)
            widget.terminalStarter!!.requestEmulatorStop()
        }
        assertNull(sizes.poll(400, TimeUnit.MILLISECONDS))
    }

    private fun withTerminal(test: (NopTerminalWidget, LinkedBlockingQueue<TermSize>) -> Unit) {
        lateinit var widget: NopTerminalWidget
        val sizes = LinkedBlockingQueue<TermSize>()
        SwingUtilities.invokeAndWait {
            widget = NopTerminalWidget(80, 24, NopTerminalSettings(Color.WHITE, Color.BLACK, Color.BLUE))
            widget.ttyConnector = object : TtyConnector {
                override fun resize(size: TermSize) {
                    // A CLI must never be told to redraw for a size the emulator has not adopted.
                    assertEquals(size.columns, widget.terminalTextBuffer.width)
                    assertEquals(size.rows, widget.terminalTextBuffer.height)
                    sizes.add(size)
                }
                override fun read(buf: CharArray, offset: Int, length: Int) = -1
                override fun write(bytes: ByteArray) = Unit
                override fun write(text: String) = Unit
                override fun isConnected() = true
                override fun waitFor() = 0
                override fun ready() = false
                override fun getName() = "resize-test"
                override fun close() = Unit
            }
        }
        try {
            test(widget, sizes)
        } finally {
            SwingUtilities.invokeAndWait {
                widget.terminalStarter!!.requestEmulatorStop()
                widget.close()
            }
        }
    }
}
