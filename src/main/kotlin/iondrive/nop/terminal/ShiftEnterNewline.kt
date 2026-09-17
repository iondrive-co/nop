package iondrive.nop.terminal

import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent

/**
 * Makes Shift+Enter insert a newline in the terminal instead of submitting the line.
 *
 * Every coding CLI nop runs has the same problem and the same workaround: Enter sends the message,
 * so writing a second paragraph needs a key that puts a line break in the draft, and the only one a
 * terminal can offer without a terminal-specific escape sequence is Ctrl+J — the control character
 * for line feed, which is what those TUIs read as "newline". Shift+Enter is what everyone reaches
 * for, and a terminal sends it as a plain carriage return, so it submits half a thought instead.
 *
 * What this does is exactly what iTerm2 and the VS Code terminal are configured to do by the
 * vendors' own setup commands: send the Ctrl+J byte for Shift+Enter. So it is not a nop-specific
 * dialect — a CLI that understands Ctrl+J, which is all of them, understands this with no setup at
 * all, and one that does not simply gets a line feed where it would have got a carriage return.
 *
 * Installed on every terminal rather than only on the agent tabs, because a shell reads the two
 * bytes the same way (readline accepts a line on either) and a key that works in one tab of a strip
 * and not the next is a key the user stops trusting.
 */
internal class ShiftEnterNewline(private val send: (String) -> Unit) : KeyAdapter() {

    /**
     * The press itself: write the byte and take the event, which is what stops JediTerm encoding
     * the same key as a carriage return.
     *
     * Taking it works because nop's listener is added to the terminal panel before the panel's own
     * (see [TerminalSession.getOrCreateWidget]), and JediTerm's handler declines an event that has
     * already been consumed.
     */
    override fun keyPressed(e: KeyEvent) {
        if (!isNewlineChord(e.keyCode, e.modifiersEx)) return
        send(NEWLINE)
        e.consume()
    }

    /**
     * The typed event that follows the press. AWT gives VK_ENTER the character `\n` whatever the
     * modifiers, so leaving this one alone would write the line feed a second time — once from the
     * press above and once from JediTerm typing the character.
     */
    override fun keyTyped(e: KeyEvent) {
        if (isNewlineChord(KeyEvent.VK_ENTER, e.modifiersEx) && (e.keyChar == '\n' || e.keyChar == '\r')) {
            e.consume()
        }
    }

    internal companion object {
        /** Line feed: the byte Ctrl+J sends, and the one the CLIs read as "newline, don't submit". */
        const val NEWLINE = "\n"

        /**
         * Shift and nothing else. Ctrl+Enter, Alt+Enter and Ctrl+Shift+Enter are left alone — each
         * means something of its own to one CLI or another, and a modifier nop did not ask about is
         * not one it should be answering for.
         */
        private const val MODIFIERS = InputEvent.SHIFT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or
            InputEvent.ALT_DOWN_MASK or InputEvent.META_DOWN_MASK or InputEvent.ALT_GRAPH_DOWN_MASK

        fun isNewlineChord(keyCode: Int, modifiersEx: Int): Boolean =
            keyCode == KeyEvent.VK_ENTER && (modifiersEx and MODIFIERS) == InputEvent.SHIFT_DOWN_MASK
    }
}
