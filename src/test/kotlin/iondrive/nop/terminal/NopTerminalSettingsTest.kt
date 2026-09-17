package iondrive.nop.terminal

import com.jediterm.terminal.HyperlinkStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Color
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * The terminal's key bindings and link styling. Ctrl-C is the one that matters: it has to be a
 * *copy* binding (JediTerm then only consumes it when something is selected, so an unselected
 * Ctrl-C still interrupts the run) — see [NopTerminalSettings.getCopyActionPresentation].
 */
class NopTerminalSettingsTest {

    private fun settings() = NopTerminalSettings(Color.BLACK, Color.WHITE, Color.BLUE)

    @DisabledOnOs(OS.MAC)
    @Test
    fun `ctrl-c copies, alongside the shift variants`() {
        val keys = settings().copyActionPresentation.keyStrokes
        assertTrue(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK) in keys, "Ctrl-C missing: $keys")
        assertTrue(
            KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK) in keys,
            "Ctrl-Shift-C missing: $keys",
        )
    }

    @DisabledOnOs(OS.MAC)
    @Test
    fun `paste keeps ctrl-shift-v and adds shift-insert`() {
        val keys = settings().pasteActionPresentation.keyStrokes
        assertTrue(
            KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK) in keys,
            "Ctrl-Shift-V missing: $keys",
        )
        assertTrue(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK) in keys, "Shift-Insert missing: $keys")
        // Plain Ctrl-V must stay out of it: in a terminal that is the literal-next / visual-block key.
        assertTrue(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK) !in keys, "Ctrl-V should not paste: $keys")
    }

    /**
     * The colours output is actually written with. JediTerm derives `defaultForeground/Background`
     * from `defaultStyle` and hardcodes that pair to black-on-white, so a provider that overrides
     * only the getters themes the empty panel and leaves a white block behind every line of text.
     */
    @Test
    fun `text is written in the theme colours, live`() {
        val s = settings()
        val style = s.defaultStyle
        assertEquals(Color.WHITE.rgb, style.foreground!!.toColor().rgb)
        assertEquals(Color.BLACK.rgb, style.background!!.toColor().rgb)
        assertEquals(Color.WHITE.rgb, s.defaultForeground.toColor().rgb, "derived foreground disagrees")
        assertEquals(Color.BLACK.rgb, s.defaultBackground.toColor().rgb, "derived background disagrees")

        // The widget keeps this one TextStyle for its lifetime, so a theme toggle has to reach it.
        s.fg = Color.GREEN
        s.bg = Color.DARK_GRAY
        assertEquals(Color.GREEN.rgb, style.foreground!!.toColor().rgb, "foreground did not follow the theme")
        assertEquals(Color.DARK_GRAY.rgb, style.background!!.toColor().rgb, "background did not follow the theme")
    }

    @Test
    fun `links are always underlined`() {
        assertEquals(HyperlinkStyle.HighlightMode.ALWAYS, settings().hyperlinkHighlightingMode)
    }

    @Test
    fun `link colour follows the theme and keeps the line background`() {
        val s = settings()
        val style = s.hyperlinkColor
        assertEquals(Color.BLUE.rgb, style.foreground!!.toColor().rgb, "link should start out at the given colour")
        assertNull(style.background, "a link must not paint its own background")

        // JediTerm holds on to this TextStyle for the life of the widget, so a theme toggle has to
        // reach links that are already on screen.
        s.link = Color.RED
        assertEquals(Color.RED.rgb, style.foreground!!.toColor().rgb, "link colour did not follow the theme")
    }

    @Test
    fun `scrollback holds a long build log`() {
        assertTrue(settings().bufferMaxLinesCount >= 20_000, "scrollback too short for a build log")
    }

    @Test
    fun `typeahead is disabled to prevent text jumping during agent output`() {
        org.junit.jupiter.api.Assertions.assertFalse(
            settings().typeAheadSettings.isEnabled,
            "typeahead should be disabled in local terminal sessions",
        )
    }

    @Test
    fun `mouse scroll does not simulate arrow keys in alternate screen`() {
        org.junit.jupiter.api.Assertions.assertFalse(
            settings().simulateMouseScrollWithArrowKeysInAlternativeScreen(),
            "mouse scroll should not send arrow keys in alternate screen",
        )
        org.junit.jupiter.api.Assertions.assertFalse(
            settings().sendArrowKeysInAlternativeMode(),
            "arrow keys should not be sent in alternative mode",
        )
    }
}
