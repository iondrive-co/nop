package iondrive.nop.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel

class AltArrowKeysTest {

    private fun escape(keyCode: Int, modifiersEx: Int) =
        AltArrowKeys.escapeSequence(keyCode, modifiersEx)

    @Test
    fun `alt plus arrow keys emit xterm modified arrow sequences`() {
        assertEquals("\u001b[1;3A", escape(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK))
        assertEquals("\u001b[1;3B", escape(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK))
        assertEquals("\u001b[1;3C", escape(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK))
        assertEquals("\u001b[1;3D", escape(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK))
    }

    @Test
    fun `alt shift plus arrow uses modifier code 4`() {
        assertEquals(
            "\u001b[1;4A",
            escape(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
        )
    }

    @Test
    fun `plain arrow keys are left alone`() {
        assertNull(escape(KeyEvent.VK_UP, 0))
        assertNull(escape(KeyEvent.VK_DOWN, 0))
        assertNull(escape(KeyEvent.VK_RIGHT, 0))
        assertNull(escape(KeyEvent.VK_LEFT, 0))
    }

    @Test
    fun `ctrl chords are not intercepted`() {
        assertNull(escape(KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK))
        assertNull(escape(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK))
    }

    @Test
    fun `non arrow keys with alt are not intercepted`() {
        assertNull(escape(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK))
        assertNull(escape(KeyEvent.VK_A, InputEvent.ALT_DOWN_MASK))
    }

    @Test
    fun `key listener consumes event and sends sequence on alt up`() {
        var sent: String? = null
        val listener = AltArrowKeys { sent = it }
        val dummy = JPanel()
        val event = KeyEvent(
            dummy,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            InputEvent.ALT_DOWN_MASK,
            KeyEvent.VK_UP,
            KeyEvent.CHAR_UNDEFINED,
        )

        listener.keyPressed(event)

        assertEquals("\u001b[1;3A", sent)
        assertTrue(event.isConsumed)
    }

    @Test
    fun `key listener ignores unintercepted keys`() {
        var sent: String? = null
        val listener = AltArrowKeys { sent = it }
        val dummy = JPanel()
        val event = KeyEvent(
            dummy,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_UP,
            KeyEvent.CHAR_UNDEFINED,
        )

        listener.keyPressed(event)

        assertNull(sent)
        assertFalse(event.isConsumed)
    }
}
