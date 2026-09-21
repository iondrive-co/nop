package iondrive.nop.terminal

import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent

/**
 * Emits standard xterm modified arrow escape sequences (`\e[1;3A`, etc.) for Alt+Arrow keys.
 *
 * JediTerm's `TerminalKeyEncoder` hardcodes prepending an ESC character (`\e\e[A`) to cursor
 * keys when Alt is held. Modern terminal UIs (such as Codex's ratatui/crossterm interface)
 * parse a leading ESC as an Escape keystroke (which triggers "esc to interrupt") rather than
 * the Alt modifier on the arrow key, breaking chords like `Alt+Up` ("alt + ↑ to answer").
 */
internal class AltArrowKeys(private val send: (String) -> Unit) : KeyAdapter() {

    override fun keyPressed(e: KeyEvent) {
        val seq = escapeSequence(e.keyCode, e.modifiersEx) ?: return
        send(seq)
        e.consume()
    }

    override fun keyTyped(e: KeyEvent) {
        if (escapeSequence(e.keyCode, e.modifiersEx) != null) {
            e.consume()
        }
    }

    internal companion object {
        private const val MODIFIERS_MASK = InputEvent.ALT_DOWN_MASK or
            InputEvent.CTRL_DOWN_MASK or
            InputEvent.SHIFT_DOWN_MASK

        fun escapeSequence(keyCode: Int, modifiersEx: Int): String? {
            val mods = modifiersEx and MODIFIERS_MASK
            // Only intervene when Alt is pressed and Ctrl is not
            if ((mods and InputEvent.ALT_DOWN_MASK) == 0) return null
            if ((mods and InputEvent.CTRL_DOWN_MASK) != 0) return null

            val modCode = if ((mods and InputEvent.SHIFT_DOWN_MASK) != 0) 4 else 3
            val suffix = when (keyCode) {
                KeyEvent.VK_UP -> "A"
                KeyEvent.VK_DOWN -> "B"
                KeyEvent.VK_RIGHT -> "C"
                KeyEvent.VK_LEFT -> "D"
                else -> return null
            }
            return "\u001b[1;${modCode}$suffix"
        }
    }
}
