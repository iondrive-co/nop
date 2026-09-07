package iondrive.nop.terminal

import com.jediterm.terminal.HyperlinkStyle
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.ui.TerminalActionPresentation
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import java.awt.Color
import java.awt.Font
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * Terminal look and key bindings for nop's launcher/terminal tabs.
 *
 * The default foreground/background are read live from [fg]/[bg]/[link], which the host updates
 * whenever the theme toggles — so flipping nop between light and dark repaints open terminals to
 * match the surrounding editor instead of staying stuck on one palette. ANSI colours keep
 * JediTerm's xterm-256 defaults, which read fine on either background.
 */
class NopTerminalSettings(
    @Volatile var bg: Color,
    @Volatile var fg: Color,
    @Volatile var link: Color,
) : DefaultSettingsProvider() {
    override fun getTerminalFontSize(): Float = 13f

    override fun getTerminalFont(): Font = Font(Font.MONOSPACED, Font.PLAIN, getTerminalFontSize().toInt())

    /**
     * The style every character is written with unless the program asks for another colour.
     *
     * [getDefaultStyle] is the hook that matters, not the [getDefaultBackground] /
     * [getDefaultForeground] pair: JediTerm derives *those* from this and hardcodes this one to
     * black-on-white. Overriding only the getters left the panel's empty area themed while every
     * line of output kept a white block behind it — which is what launcher output looked like on
     * nop's dark theme.
     *
     * One instance, colours read through suppliers: JediTerm hands the style to `StyleState` when
     * the widget is built and writes cells with it from then on, so this is also what lets a
     * light/dark toggle reach output that is already on screen.
     */
    override fun getDefaultStyle(): TextStyle = defaultStyle

    private val defaultStyle = TextStyle(TerminalColor { fg.toJediColor() }, TerminalColor { bg.toJediColor() })

    /**
     * How [UrlHyperlinkFilter]'s links are painted. JediTerm asks for this once, when the widget is
     * built, and reuses the colours for every link it ever styles — hence a *supplier* foreground,
     * which lets a theme toggle recolour links already on screen. The background is null ("keep the
     * line's own"): the inherited default hardcodes white, which paints a white block behind every
     * link on a dark terminal.
     */
    override fun getHyperlinkColor(): TextStyle = hyperlinkStyle

    private val hyperlinkStyle = TextStyle(TerminalColor { link.toJediColor() }, null)

    /**
     * Underline links whenever they're on screen, not just under the pointer (JediTerm's default),
     * so output that contains a URL advertises it as clickable without a hunt.
     */
    override fun getHyperlinkHighlightingMode(): HyperlinkStyle.HighlightMode =
        HyperlinkStyle.HighlightMode.ALWAYS

    /**
     * Copy on plain Ctrl-C as well as Ctrl-Shift-C — the point being that Ctrl-C must not kill the
     * run just because the user reached for the usual copy shortcut.
     *
     * This is safe to bind because JediTerm's copy handler special-cases exactly this keystroke: it
     * copies when there is a selection, and when there is none it *declines* the event, so the
     * event falls through to the terminal and the interrupt character still reaches the process.
     * So nothing selected behaves as before — Ctrl-C interrupts — and interrupting is never more
     * than one extra keystroke away, because copying also drops the selection: select, Ctrl-C to
     * copy, Ctrl-C again and the run takes the SIGINT.
     */
    override fun getCopyActionPresentation(): TerminalActionPresentation =
        TerminalActionPresentation("Copy", if (isMac) macCopyKeys else copyKeys)

    /** Paste keeps JediTerm's Ctrl-Shift-V and adds the terminal-classic Shift-Insert. */
    override fun getPasteActionPresentation(): TerminalActionPresentation =
        TerminalActionPresentation("Paste", if (isMac) macPasteKeys else pasteKeys)

    /**
     * Scrollback. A launcher run is usually a build or a dev server, whose interesting output (the
     * first error) can be thousands of lines above the tail; JediTerm's 5000-line default drops it.
     */
    override fun getBufferMaxLinesCount(): Int = 20_000

    private fun Color.toJediColor() = com.jediterm.core.Color(red, green, blue)

    private companion object {
        val isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")

        val copyKeys = listOf(
            KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.CTRL_DOWN_MASK),
        )
        val pasteKeys = listOf(
            KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK),
        )
        // macOS keeps Cmd-C/Cmd-V, where Ctrl-C is unambiguously the interrupt.
        val macCopyKeys = listOf(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.META_DOWN_MASK))
        val macPasteKeys = listOf(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.META_DOWN_MASK))
    }
}
