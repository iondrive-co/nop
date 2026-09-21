package iondrive.nop.terminal

import com.jediterm.terminal.HyperlinkStyle
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.ui.TerminalActionPresentation
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
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
     *
     * Asked for **by family name**, not by deriving the `Font.createFont` handle. JediTerm draws
     * bold, italic and bold-italic cells with `getTerminalFont().deriveFont(style)`, and a font
     * built by `createFont` is not in any family AWT can search: `deriveFont(BOLD)` on one keeps
     * reporting `JetBrains Mono Regular` and the renderer smears the regular outlines instead.
     * Every weight a TUI leans on — Claude Code's section headings, `git`'s branch names, a
     * compiler's error prefix — was arriving as algorithmic fake bold. [registerMonoFaces] puts
     * the four real faces in the graphics environment so this lookup resolves them.
     */
    override fun getTerminalFont(): Font =
        if (monoFamilyRegistered) Font(MONO_FAMILY, Font.PLAIN, FONT_SIZE.toInt())
        else monoFont.deriveFont(FONT_SIZE)

    /**
     * Rows packed at exactly the font's height, which is what a real terminal does.
     *
     * This was 1.1 — "a little air between rows" — on the theory that JediTerm's 1.0 was tighter
     * than the terminals beside it. Measured against one, it is the other way round: JetBrains
     * Mono's own hhea metrics are tall (18px of line box for a 13px font), so 1.1 made a 20px row
     * for an 8px-wide glyph, a cell 2.6x taller than wide where a terminal's is nearer 1.8x. The
     * air was already in the font.
     */
    override fun getLineSpacing(): Float = 1.0f

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
        /**
         * Two points above the editor's 13sp, which this used to match on the grounds that code
         * should read the same size in a file and in a shell.
         *
         * They are not the same surface. The editor is Compose text; the terminal is AWT text in
         * an embedded Swing panel, laid out on a whole-pixel grid, and at 13f JetBrains Mono's
         * advance rounds down to 8px — against the 9px cell of the terminal the user has beside
         * this one. 15f is the smallest size that rounds to 9 (14f still gives 8), so it is what
         * makes a character here the width of a character there. Paired with a 1.0 line spacing
         * the cell is 9x21 against the old 8x20: wider glyphs, near enough the same rows.
         *
         * Load-bearing beyond its own legibility: the panel draws with fractional metrics on (see
         * `NopTerminalPanel.setupAntialiasing`), which is only safe while the advance sits on the
         * cell grid. 0.6em of 15px is 9.0000354px; of 14px it would be 8.4. Moving this constant
         * means re-reading that, and `NopTerminalSettingsTest` fails if the drift grows.
         */
        const val FONT_SIZE = 15f

        private const val MONO_FAMILY = "JetBrains Mono"
        private const val FONT_DIR = "fonts/jetbrains-mono"
        private const val FONT_RESOURCE = "$FONT_DIR/JetBrainsMono-Regular.ttf"

        /**
         * The four faces JediTerm can ask for — regular, bold, italic, bold-italic — handed to the
         * graphics environment so that [getTerminalFont]'s by-name lookup finds each one rather
         * than having AWT fake it off the regular outlines.
         *
         * Loaded from the TTFs the Jewel dependency already puts on the classpath, the same files
         * `NopFonts` hands Compose, so the terminal and the editor render the same face without
         * this repo carrying its own copy of the font. False if any of them is missing — a Jewel
         * upgrade moving the resources leaves the old [monoFont] path in charge, which is a duller
         * terminal rather than no text.
         */
        val monoFamilyRegistered: Boolean = run {
            val loader = Thread.currentThread().contextClassLoader ?: NopTerminalSettings::class.java.classLoader
            val ge = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment() }.getOrNull()
            ge != null && listOf("Regular", "Bold", "Italic", "BoldItalic").all { face ->
                val stream = loader?.getResourceAsStream("$FONT_DIR/JetBrainsMono-$face.ttf")
                stream != null && runCatching {
                    ge.registerFont(stream.use { Font.createFont(Font.TRUETYPE_FONT, it) })
                }.getOrDefault(false)
            }
        }

        /**
         * Fallback for when [monoFamilyRegistered] is false: JetBrains Mono's regular face on its
         * own, or the platform monospace if even that has moved.
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
