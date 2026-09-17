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
    override fun getTerminalFontSize(): Float = FONT_SIZE

    /**
     * The same JetBrains Mono the editor renders code with (see `NopFonts`), so a shell sitting
     * beside a file looks like part of the same application.
     *
     * The face matters more here than anywhere else in nop: AWT's logical `MONOSPACED` family —
     * what this used to ask for, and what JediTerm falls back to — resolves on most Linux boxes to
     * DejaVu Sans Mono, whose small x-height and heavy hinting is what made the terminal read as a
     * different, older program than the one around it.
     */
    override fun getTerminalFont(): Font = monoFont.deriveFont(FONT_SIZE)

    /**
     * A little air between rows. JediTerm packs lines at exactly the font's height (1.0), which is
     * tighter than any terminal the user has beside it — 1.1 is roughly what IntelliJ's terminal
     * and xterm.js-based terminals give a line.
     */
    override fun getLineSpacing(): Float = 1.1f

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

    /**
     * Disable predictive typeahead.
     *
     * JediTerm's typeahead attempts to predict typed characters and render them speculatively when
     * process roundtrip latency exceeds 100ms. In local PTY sessions running interactive coding
     * agents (Antigravity, Claude Code, Codex), the agent CLI frequently moves the cursor and
     * rewrites lines via ANSI escape sequences while streaming output. When typeahead is enabled,
     * incoming output chunks desynchronize the prediction manager, causing the user's typed text to
     * jump between lines and move around the screen.
     */
    override fun getTypeAheadSettings(): com.jediterm.terminal.model.TerminalTypeAheadSettings =
        com.jediterm.terminal.model.TerminalTypeAheadSettings(false, 0, null)

    /**
     * Disable arrow key simulation on mouse scroll when in the alternate screen buffer.
     *
     * JediTerm's default (`simulateMouseScrollWithArrowKeysInAlternativeScreen = true`) translates
     * mouse wheel and trackpad scroll gestures in the alternate buffer into Up/Down arrow keystrokes
     * and sends them to the child process's stdin. For full-screen TUIs like Antigravity (`agy`),
     * which runs in the alternate screen buffer, this floods the CLI with arrow keys on every scroll,
     * triggering endless command history cycling or cursor jumping ("infinite scroll").
     */
    override fun simulateMouseScrollWithArrowKeysInAlternativeScreen(): Boolean = false
    override fun sendArrowKeysInAlternativeMode(): Boolean = false

    private fun Color.toJediColor() = com.jediterm.core.Color(red, green, blue)

    private companion object {
        /** Matches the editor's 13sp, so code reads the same size in a file and in a shell. */
        const val FONT_SIZE = 13f

        private const val FONT_RESOURCE = "fonts/jetbrains-mono/JetBrainsMono-Regular.ttf"

        /**
         * JetBrains Mono, loaded from the TTF the Jewel dependency already puts on the classpath —
         * the same file `NopFonts` hands Compose, so the terminal and the editor render the same
         * face without this repo carrying its own copy of the font. Falls back to the platform
         * monospace if a Jewel upgrade ever moves the resource: a duller terminal beats no text.
         */
        val monoFont: Font = run {
            val loader = Thread.currentThread().contextClassLoader ?: NopTerminalSettings::class.java.classLoader
            val stream = loader?.getResourceAsStream(FONT_RESOURCE)
            if (stream == null) {
                Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE.toInt())
            } else {
                runCatching { stream.use { Font.createFont(Font.TRUETYPE_FONT, it) } }
                    .getOrElse { Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE.toInt()) }
            }
        }

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
