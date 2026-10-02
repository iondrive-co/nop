package iondrive.nop.terminal

import com.jediterm.terminal.HyperlinkStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    private fun settings(
        bg: Color = Color.BLACK,
        fg: Color = Color.WHITE,
        link: Color = Color.BLUE,
    ) = NopTerminalSettings(bg, fg, link)

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

    /**
     * Bold has to come from JetBrains Mono's *bold* outlines, not from AWT smearing the regular
     * ones. JediTerm paints a bold cell with `getTerminalFont().deriveFont(Font.BOLD)`, and a font
     * built by `Font.createFont` belongs to no family AWT can search — so deriving from one leaves
     * the regular face in place under a BOLD style flag and the renderer fakes the weight. Every
     * heading a TUI emits (Claude Code's section titles, a compiler's error prefix) goes through
     * this call, so the check is on the derived font's own name.
     */
    @Test
    fun `bold cells resolve the real bold face`() {
        val font = settings().terminalFont
        assertEquals("JetBrains Mono", font.family, "terminal is not on JetBrains Mono at all")
        assertEquals(
            "JetBrains Mono Bold",
            font.deriveFont(java.awt.Font.BOLD).fontName,
            "bold is being synthesised from the regular face",
        )
        assertEquals(
            "JetBrains Mono Italic",
            font.deriveFont(java.awt.Font.ITALIC).fontName,
            "italic is being synthesised from the regular face",
        )
    }

    /**
     * The cell a character lands in. A terminal beside nop puts a monospace glyph on a 9px advance;
     * JetBrains Mono's advance rounds down to 8 below 15f (14f still gives 8), and a 1.1 line
     * spacing would stretch the row to 20px the font's own metrics have already paid for. Both are
     * easy to undo by tidying a constant, hence the test.
     */
    @Test
    fun `a character occupies the same cell as a plain terminal`() {
        val s = settings()
        val metrics = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
            .createGraphics().getFontMetrics(s.terminalFont)
        assertEquals(9, metrics.charWidth('W'), "glyph advance no longer matches a plain terminal's cell")
        assertEquals(1.0f, s.lineSpacing, "rows are being padded past the height the font already asks for")
    }

    /**
     * The characters JetBrains Mono has no glyph for must not come out as AWT's empty box. The
     * case that found this is codex, whose composer animates `·✦✧` behind the prompt: the font has
     * the middle dot and neither star, so the animation was boxes crawling across the input.
     */
    @Test
    fun `a glyph the terminal font lacks falls back to a face that has it`() {
        val font = settings().terminalFont
        // The premise: these really are missing, so the test is not passing vacuously.
        assertFalse(font.canDisplay(0x2726), "JetBrains Mono gained ✦; this test needs a new example")
        assertFalse(font.canDisplay(0x2727), "JetBrains Mono gained ✧; this test needs a new example")

        for (cp in intArrayOf(0x2726, 0x2727, 0x28FF)) {
            val text = Character.toChars(cp)
            val chosen = TerminalGlyphFallback.fontFor(font, text, 0, text.size)
            assertTrue(
                chosen.canDisplay(cp),
                "U+%04X would render as a missing-glyph box in %s".format(cp, chosen.fontName),
            )
        }
    }

    /** ASCII — every cluster that matters — must keep the terminal's own face. */
    @Test
    fun `text the terminal font can draw is not substituted`() {
        val font = settings().terminalFont
        for (s in listOf("class Discount(", "─│╭╮ ● ○ █░▒▓", "·")) {
            val text = s.toCharArray()
            assertEquals(
                font,
                TerminalGlyphFallback.fontFor(font, text, 0, text.size),
                "\"$s\" was pushed onto the fallback face unnecessarily",
            )
        }
    }

    /**
     * [NopTerminalWidget] turns fractional metrics on, to stop OpenJDK's FreeType scaler hinting
     * the glyphs — that snapping is what made the terminal look unlike the Compose-drawn code tabs
     * beside it. The glyph then occupies its true sub-pixel advance rather than a rounded one, so
     * that advance has to agree with the whole-pixel cell the grid was measured into: JetBrains
     * Mono's 0.6em is 9.0000354px at 15f against a 9px cell, where 14f (8.4) or 13f (7.8) would
     * leave every glyph sitting visibly inside or over its cell.
     */
    @Test
    fun `the drawn advance agrees with the whole-pixel cell`() {
        val font = settings().terminalFont
        val cell = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
            .createGraphics().getFontMetrics(font).charWidth('W')
        val columns = 400
        val frc = java.awt.font.FontRenderContext(null, true, true)
        val drift = Math.abs(font.getStringBounds("0".repeat(columns), frc).width - cell.toDouble() * columns)
        assertTrue(drift < 0.5, "over $columns cells the drawn advance is ${drift}px away from the ${cell}px cell")
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

    @Test
    fun `scrollToBottom resets vertical scroll value to zero`() {
        val s = settings()
        val widget = NopTerminalWidget(80, 24, s)
        val model = widget.terminalPanel.verticalScrollModel
        model.setRangeProperties(-10, 24, -100, 24, false)
        assertEquals(-10, model.value)

        widget.scrollToBottom()
        assertEquals(0, model.value, "scrollToBottom should reset scroll to 0")
    }

    @Test
    fun `leaving alternate screen buffer resets viewport scroll to bottom`() {
        val s = settings()
        val widget = NopTerminalWidget(80, 24, s)
        val model = widget.terminalPanel.verticalScrollModel
        model.setRangeProperties(-15, 24, -100, 24, false)

        widget.terminalPanel.useAlternateScreenBuffer(true)
        widget.terminalPanel.useAlternateScreenBuffer(false)
        javax.swing.SwingUtilities.invokeAndWait { }

        assertEquals(0, model.value, "leaving alternate screen should restore scroll to bottom")
    }

    @Test
    fun `incoming buffer lines autoscroll to bottom when user has not scrolled up`() {
        val s = settings()
        val widget = NopTerminalWidget(80, 24, s)
        val model = widget.terminalPanel.verticalScrollModel
        model.setRangeProperties(-50, 24, -100, 24, false)
        assertEquals(-50, model.value)
        assertFalse(widget.userScrolledUp)

        widget.terminalTextBuffer.addLine(com.jediterm.terminal.model.TerminalLine.createEmpty())
        javax.swing.SwingUtilities.invokeAndWait { }

        assertEquals(0, model.value, "incoming buffer lines must autoscroll to bottom even from deep in history")
    }

    // Whether a buffer update leaves a scrolled-up view alone is not asserted here: setting the scroll
    // model by hand bypasses JediTerm's panel, whose own repaint timer puts the model back at the
    // bottom whenever it fires, so the outcome depended on how loaded the machine running it was.
    @Test
    fun `scrollToBottom ends a scroll into history`() {
        val s = settings()
        val widget = NopTerminalWidget(80, 24, s)
        val model = widget.terminalPanel.verticalScrollModel
        model.setRangeProperties(-50, 24, -100, 24, false)
        widget.userScrolledUp = true

        widget.scrollToBottom()
        assertFalse(widget.userScrolledUp, "scrollToBottom must reset userScrolledUp")
        assertEquals(0, model.value)
    }

    @Test
    fun `caret blinking is active initially and suspended when idle`() {
        val s = settings()
        assertEquals(NopTerminalSettings.BLINK_PERIOD_MS, s.caretBlinkingMs(), "caret should blink when active")
        assertTrue(s.isBlinkingActive)

        // Advance activity time past the idle threshold
        s.lastActiveAt = System.currentTimeMillis() - NopTerminalSettings.BLINK_IDLE_MS - 1_000L
        assertFalse(s.isBlinkingActive)
        assertEquals(
            NopTerminalSettings.IDLE_BLINKING_PERIOD_MS,
            s.caretBlinkingMs(),
            "caret blinking should be suspended when idle",
        )

        // Nudge restores active blinking
        s.nudgeActive()
        assertTrue(s.isBlinkingActive)
        assertEquals(NopTerminalSettings.BLINK_PERIOD_MS, s.caretBlinkingMs())
    }

    @Test
    fun `contrast adjusting graphics batches brush color across character draws`() {
        val s = settings()
        val img = java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val baseG2d = img.createGraphics()

        class RecordingGraphics2D(delegate: java.awt.Graphics2D, settings: NopTerminalSettings) :
            ContrastAdjustingGraphics2D(delegate, settings) {
            val colorAssignments = mutableListOf<Color?>()
            override fun setColor(c: Color?) {
                colorAssignments.add(c)
                super.setColor(c)
            }
        }

        val recording = RecordingGraphics2D(baseG2d, s)
        val wrapper = ContrastAdjustingGraphics2D(recording, s)
        wrapper.color = Color.BLACK
        wrapper.fillRect(0, 0, 100, 20)

        recording.colorAssignments.clear()

        wrapper.color = Color.WHITE
        val chars = "Hello World".toCharArray()
        for (i in chars.indices) {
            wrapper.drawChars(chars, i, 1, i * 9, 15)
        }

        // Must NOT ping-pong setColor on every single character cluster (previously 22 calls for 11 chars)
        assertEquals(
            1,
            recording.colorAssignments.size,
            "underlying Graphics2D color should only be updated once for a contiguous run of characters with identical contrast requirements",
        )
    }

    @Test
    fun `terminal panel switches between steady and blinking cursor on period change`() {
        val s = settings()
        val widget = NopTerminalWidget(80, 24, s)
        val panel = widget.terminalPanel

        panel.setBlinkingPeriod(NopTerminalSettings.IDLE_BLINKING_PERIOD_MS)
        panel.setBlinkingPeriod(NopTerminalSettings.BLINK_PERIOD_MS)
    }

    @Test
    fun `selection uses explicit uniform selection colors rather than inverse`() {
        val dark = settings(bg = Color(0x2B, 0x2B, 0x2B))
        assertFalse(dark.useInverseSelectionColor())
        val darkSel = dark.selectionColor
        assertEquals(Color(0x26, 0x75, 0xBF).rgb, darkSel.background!!.toColor().rgb)
        assertEquals(Color(0xDF, 0xE1, 0xE5).rgb, darkSel.foreground!!.toColor().rgb)

        val light = settings(bg = Color.WHITE)
        assertFalse(light.useInverseSelectionColor())
        val lightSel = light.selectionColor
        assertEquals(Color(0xA6, 0xD2, 0xFF).rgb, lightSel.background!!.toColor().rgb)
        assertEquals(Color.BLACK.rgb, lightSel.foreground!!.toColor().rgb)
    }

    @Test
    fun `hyperlinks receive the same selection background as surrounding text`() {
        val s = settings(bg = Color(0x2B, 0x2B, 0x2B))
        val widget = NopTerminalWidget(80, 24, s)
        widget.addHyperlinkFilter(UrlHyperlinkFilter())
        val buffer = widget.terminalTextBuffer
        val line = "See https://example.com/docs here"
        buffer.writeString(0, 1, com.jediterm.terminal.model.CharBuffer(line))

        val panel = widget.terminalPanel
        panel.setSize(800, 400)
        val selField = com.jediterm.terminal.ui.TerminalPanel::class.java.getDeclaredField("mySelection")
        selField.isAccessible = true
        selField.set(
            panel,
            com.jediterm.terminal.model.TerminalSelection(
                com.jediterm.core.compatibility.Point(0, 0),
                com.jediterm.core.compatibility.Point(25, 0),
            ),
        )

        val fills = mutableListOf<Triple<Int, Int, Color>>()
        val img = java.awt.image.BufferedImage(800, 400, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g2d = img.createGraphics()
        val recording = object : ContrastAdjustingGraphics2D(g2d, s) {
            override fun fillRect(x: Int, y: Int, width: Int, height: Int) {
                fills.add(Triple(x, width, color))
                super.fillRect(x, y, width, height)
            }
        }

        panel.paintComponent(recording)

        val expectedBg = Color(0x26, 0x75, 0xBF)
        val selectionFills = fills.filter { it.third == expectedBg }
        assertTrue(selectionFills.isNotEmpty(), "Selection background color should be filled")
        val totalSelectedWidth = selectionFills.sumOf { it.second }
        assertTrue(totalSelectedWidth > 150, "Selection fill should span plain text and the hyperlink")
        val linkColor = s.hyperlinkColor.foreground!!.toColor()
        assertFalse(fills.any { it.third == linkColor }, "Hyperlink should not use its link foreground as background")
    }
}
