package iondrive.nop.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/**
 * Which Enter goes to the process as a line feed.
 *
 * The chord is exactly Shift+Enter. Plain Enter has to stay a carriage return or nothing in a
 * terminal submits anything at all, and every other modifier means something of its own to one CLI
 * or another — Alt+Enter and Ctrl+Enter are both taken.
 */
class ShiftEnterNewlineTest {

    private fun chord(keyCode: Int, modifiersEx: Int) =
        ShiftEnterNewline.isNewlineChord(keyCode, modifiersEx)

    @Test
    fun `shift-enter is the newline chord`() {
        assertTrue(chord(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK))
    }

    @Test
    fun `plain enter still submits`() {
        assertFalse(chord(KeyEvent.VK_ENTER, 0))
    }

    @Test
    fun `the other modifiers are left to the CLI`() {
        assertFalse(chord(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK))
        assertFalse(chord(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK))
        assertFalse(chord(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK))
        assertFalse(
            chord(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK),
            "Ctrl+Shift+Enter is a chord of its own, not a shifted Enter",
        )
    }

    /** A shifted anything-else is an ordinary capital letter. */
    @Test
    fun `shift with another key is not the chord`() {
        assertFalse(chord(KeyEvent.VK_A, InputEvent.SHIFT_DOWN_MASK))
        assertFalse(chord(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK))
    }

    /**
     * The byte itself: line feed, which is what Ctrl+J sends and what the CLIs read as "newline,
     * don't submit". A carriage return here would be the bug this exists to fix.
     */
    @Test
    fun `the chord sends a line feed`() {
        assertEquals("\n", ShiftEnterNewline.NEWLINE)
    }
}
