package iondrive.nop.terminal

import com.jediterm.terminal.DefaultTerminalCopyPasteHandler
import com.jediterm.terminal.TerminalCopyPasteHandler
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalPanel
import com.jediterm.terminal.ui.settings.SettingsProvider
import java.awt.Color
import java.awt.Composite
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsConfiguration
import java.awt.Image
import java.awt.Paint
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.Stroke
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.FontRenderContext
import java.awt.font.GlyphVector
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.BufferedImageOp
import java.awt.image.ImageObserver
import java.awt.image.RenderedImage
import java.awt.image.renderable.RenderableImage
import java.text.AttributedCharacterIterator
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollBar
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicScrollBarUI

/**
 * JediTerm's widget with nop's scrollbar, adaptive WCAG AA contrast filtering, and auto-scrolling.
 *
 * The default is a plain Swing [JScrollBar] with the platform look — on Linux a white Metal bar
 * with arrow buttons, standing permanently down the right edge of an otherwise themed terminal,
 * whether or not there is anything above the screen to scroll back to. [TerminalScrollBar] replaces
 * it with a slim, theme-coloured bar that takes no space at all until the output runs off the top.
 */
internal class NopTerminalWidget(
    columns: Int,
    rows: Int,
    settings: NopTerminalSettings,
) : JediTermWidget(columns, rows, settings) {

    internal var userScrolledUp: Boolean = false
    @Volatile
    private var scrollScheduled: Boolean = false
    private var scrollBarRef: JScrollBar? = null

    init {
        // Keep scroll at bottom on buffer updates unless the user has explicitly scrolled up
        terminalTextBuffer.addModelListener {
            if (!userScrolledUp && !scrollScheduled) {
                scrollScheduled = true
                SwingUtilities.invokeLater {
                    scrollScheduled = false
                    if (!userScrolledUp) {
                        scrollToBottom()
                    }
                }
            }
        }

        // Track when the viewport reaches bottom to reset userScrolledUp
        terminalPanel.verticalScrollModel.addChangeListener {
            if (terminalPanel.verticalScrollModel.value == 0) {
                userScrolledUp = false
            }
        }

        // Mouse wheel: scrolling up sets userScrolledUp; scrolling down checks if bottom reached
        terminalPanel.addMouseWheelListener { e ->
            if (e.wheelRotation < 0) {
                userScrolledUp = true
            } else if (e.wheelRotation > 0) {
                SwingUtilities.invokeLater {
                    if (terminalPanel.verticalScrollModel.value == 0) {
                        userScrolledUp = false
                    }
                }
            }
        }

        // Scrollbar thumb dragging: user dragging up into history sets userScrolledUp
        scrollBarRef?.addAdjustmentListener { e ->
            if (e.valueIsAdjusting && terminalPanel.verticalScrollModel.value < 0) {
                userScrolledUp = true
            } else if (terminalPanel.verticalScrollModel.value == 0) {
                userScrolledUp = false
            }
        }

        // Typing or submitting a command brings the viewport back to bottom
        terminalPanel.addCustomKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_PAGE_UP || e.keyCode == java.awt.event.KeyEvent.VK_UP) {
                    return
                }
                if (userScrolledUp && (e.keyCode == java.awt.event.KeyEvent.VK_ENTER ||
                        e.keyChar in ' '..'~' || e.keyCode == java.awt.event.KeyEvent.VK_BACK_SPACE)
                ) {
                    userScrolledUp = false
                    scrollToBottom()
                }
            }
        })

        // Snap to bottom when focused or clicked, unless scrolled up into history
        terminalPanel.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!userScrolledUp) {
                    scrollToBottom()
                }
            }
        })
        terminalPanel.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                if (!userScrolledUp) {
                    scrollToBottom()
                }
            }
        })
    }

    /** Scrolls the viewport down to the active prompt / live screen output. */
    fun scrollToBottom() {
        userScrolledUp = false
        val model = terminalPanel.verticalScrollModel
        if (model.value != 0) {
            model.value = 0
        }
    }

    /**
     * Takes the settings back off the base class rather than keeping a property of its own: JediTerm
     * builds the scrollbar from inside *its* constructor, before this class's fields are assigned,
     * so `private val settings` would still be null here. `mySettingsProvider` is set before that
     * call, and is the very object handed in above.
     */
    override fun createScrollBar(): JScrollBar {
        val bar = TerminalScrollBar(mySettingsProvider as NopTerminalSettings)
        scrollBarRef = bar
        return bar
    }

    override fun createTerminalPanel(
        settingsProvider: SettingsProvider,
        styleState: StyleState,
        textBuffer: TerminalTextBuffer,
    ): TerminalPanel =
        NopTerminalPanel(settingsProvider as NopTerminalSettings, textBuffer, styleState)
}

/**
 * TerminalPanel subclass applying contrast adjustments to ensure readable text on light or dark themes,
 * and resetting the scroll position when switching out of the alternate screen buffer.
 */
private class NopTerminalPanel(
    private val settings: NopTerminalSettings,
    textBuffer: TerminalTextBuffer,
    styleState: StyleState,
) : TerminalPanel(settings, textBuffer, styleState) {

    override fun createCopyPasteHandler(): TerminalCopyPasteHandler =
        NopTerminalCopyPasteHandler()

    /**
     * Substitutes a face that has the glyph whenever JetBrains Mono does not — see
     * [TerminalGlyphFallback]. JediTerm asks this once per grapheme cluster, on the way to drawing
     * it.
     */
    override fun getFontToDisplay(text: CharArray, start: Int, end: Int, style: TextStyle): Font =
        TerminalGlyphFallback.fontFor(super.getFontToDisplay(text, start, end, style), text, start, end)

    /**
     * JediTerm's own antialiasing, plus fractional metrics.
     *
     * Not about metrics so much as about *hinting*: OpenJDK's FreeType scaler loads glyphs
     * unhinted once fractional metrics are on, and hinting — stems snapped onto whole pixel
     * columns — is the whole of why the terminal read as a different, more "Linux" program than
     * the Compose-drawn code tabs beside it, which go through Skia and are not hinted. It does not
     * make the two rasterisers identical, but it takes the hard edge off the difference.
     *
     * A row cannot drift out of its cells from this: JediTerm splits a line into grapheme
     * clusters and draws each one at its own `column * cellWidth`, so no advance is ever summed
     * across a row. What the advance still has to do is fill the cell it was measured for —
     * JetBrains Mono's 0.6em is 9.0000354px at [NopTerminalSettings.FONT_SIZE] = 15, against the
     * 9px `charWidth` the grid is built from, so a glyph sits where its cell is. `NopTerminal
     * SettingsTest` fails if a change to that constant opens the two apart.
     */
    override fun setupAntialiasing(g: Graphics) {
        super.setupAntialiasing(g)
        (g as? Graphics2D)?.setRenderingHint(
            RenderingHints.KEY_FRACTIONALMETRICS,
            RenderingHints.VALUE_FRACTIONALMETRICS_ON,
        )
    }

    override fun paintComponent(g: Graphics) {
        val g2d = g as? Graphics2D ?: run {
            super.paintComponent(g)
            return
        }
        val wrapper = ContrastAdjustingGraphics2D(g2d, settings)
        super.paintComponent(wrapper)
    }

    override fun useAlternateScreenBuffer(enabled: Boolean) {
        super.useAlternateScreenBuffer(enabled)
        if (!enabled) {
            SwingUtilities.invokeLater {
                verticalScrollModel.value = 0
            }
        }
    }
}

/**
 * A face for the characters the terminal font has no glyph for, instead of the empty box AWT draws
 * when a physical font is asked for one it doesn't have.
 *
 * Codex animates its composer background with `·✦✧` (`chat_composer/sparkle.rs`). JetBrains Mono
 * has the middle dot and neither star, so two frames in three came out a hollow rectangle and the
 * animation read as boxes crawling behind the prompt rather than as anything twinkling. The same
 * gap swallows the braille spinners other CLIs use (U+2800..U+28FF) and most of Dingbats.
 *
 * The stand-in is AWT's *logical* monospace. A font from `Font.createFont` — which is what the
 * terminal runs on, see [NopTerminalSettings.getTerminalFont] — is a single physical face with no
 * fallback behind it; a logical family is a composite over the platform's whole fallback chain, so
 * it covers whatever turns up instead of a list of symbols guessed in advance.
 *
 * Substituting per cluster is safe because JediTerm positions each cluster at `column * cellWidth`
 * and centres one narrower than its cell, so a face with different metrics cannot walk the row off
 * the grid.
 */
internal object TerminalGlyphFallback {
    private val cache = ConcurrentHashMap<Long, Font>()

    /**
     * [font] if it can draw `text[start until end]`, otherwise a logical monospace of the same
     * size and style.
     */
    fun fontFor(font: Font, text: CharArray, start: Int, end: Int): Font {
        // Nearly every cluster on screen is one ASCII character, and the terminal font has all of
        // those — so answer those without touching the font's character map at all.
        var nonAscii = false
        for (i in start until end) {
            if (text[i].code >= 0x80) {
                nonAscii = true
                break
            }
        }
        if (!nonAscii || font.canDisplayUpTo(text, start, end) < 0) return font
        return cache.computeIfAbsent((font.style.toLong() shl 32) or font.size.toLong()) {
            Font(Font.MONOSPACED, font.style, font.size)
        }
    }
}

/**
 * Terminal copy/paste handler that intercepts paste when the clipboard holds an image or files.
 *
 * When an image is pasted into the terminal (e.g. via Ctrl+Shift+V or Shift+Insert), saves it as a
 * temporary PNG file and types the quoted file path with a trailing space into the prompt, matching
 * what [TerminalFileDrop] does when an image is dragged onto the terminal.
 *
 * Also handles dropped/copied file lists from the clipboard. If neither an image nor files are
 * present, delegates to [DefaultTerminalCopyPasteHandler] for standard text paste.
 */
internal class NopTerminalCopyPasteHandler : DefaultTerminalCopyPasteHandler() {

    override fun getContents(useSystemSelectionClipboard: Boolean): String? {
        val clipboard = if (useSystemSelectionClipboard) {
            runCatching { Toolkit.getDefaultToolkit().systemSelection }.getOrNull()
                ?: Toolkit.getDefaultToolkit().systemClipboard
        } else {
            Toolkit.getDefaultToolkit().systemClipboard
        } ?: return null

        // 1. Image on the clipboard
        if (runCatching { clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) }.getOrDefault(false)) {
            val image = runCatching { clipboard.getData(DataFlavor.imageFlavor) as? Image }.getOrNull()
            if (image != null) {
                val saved = saveImageToTemp(image)
                if (saved != null) {
                    return quoteForPrompt(saved.toAbsolutePath().toString()) + " "
                }
            }
        }

        // 2. File list on the clipboard
        if (runCatching { clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) }.getOrDefault(false)) {
            val files = runCatching {
                @Suppress("UNCHECKED_CAST")
                clipboard.getData(DataFlavor.javaFileListFlavor) as? List<File>
            }.getOrNull()
            if (!files.isNullOrEmpty()) {
                return files.joinToString(" ") { quoteForPrompt(it.absolutePath) } + " "
            }
        }

        // 3. Fall back to standard text
        return super.getContents(useSystemSelectionClipboard)
    }
}

/**
 * A delegating [Graphics2D] that intercepts text and line drawing in the terminal panel,
 * ensuring foreground text meets WCAG AA contrast (4.5:1) against the underlying cell background.
 */
private class ContrastAdjustingGraphics2D(
    private val delegate: Graphics2D,
    private val settings: NopTerminalSettings,
    private var currentBg: Color = settings.bg,
) : Graphics2D() {
    private var currentColor: Color = delegate.color ?: settings.fg

    override fun setColor(c: Color?) {
        if (c != null) {
            currentColor = c
        }
        delegate.color = c
    }

    override fun getColor(): Color = currentColor

    override fun fillRect(x: Int, y: Int, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            currentBg = currentColor
        }
        delegate.fillRect(x, y, width, height)
    }

    override fun drawChars(data: CharArray, offset: Int, length: Int, x: Int, y: Int) {
        val adjusted = TerminalContrast.cachedEnsureContrast(currentColor, currentBg)
        delegate.color = adjusted
        delegate.drawChars(data, offset, length, x, y)
        delegate.color = currentColor
    }

    override fun drawString(str: String, x: Int, y: Int) {
        val adjusted = TerminalContrast.cachedEnsureContrast(currentColor, currentBg)
        delegate.color = adjusted
        delegate.drawString(str, x, y)
        delegate.color = currentColor
    }

    override fun drawString(str: String, x: Float, y: Float) {
        val adjusted = TerminalContrast.cachedEnsureContrast(currentColor, currentBg)
        delegate.color = adjusted
        delegate.drawString(str, x, y)
        delegate.color = currentColor
    }

    override fun drawString(iterator: AttributedCharacterIterator, x: Int, y: Int) {
        val adjusted = TerminalContrast.cachedEnsureContrast(currentColor, currentBg)
        delegate.color = adjusted
        delegate.drawString(iterator, x, y)
        delegate.color = currentColor
    }

    override fun drawString(iterator: AttributedCharacterIterator, x: Float, y: Float) {
        val adjusted = TerminalContrast.cachedEnsureContrast(currentColor, currentBg)
        delegate.color = adjusted
        delegate.drawString(iterator, x, y)
        delegate.color = currentColor
    }

    override fun drawGlyphVector(g: GlyphVector, x: Float, y: Float) {
        val adjusted = TerminalContrast.cachedEnsureContrast(currentColor, currentBg)
        delegate.color = adjusted
        delegate.drawGlyphVector(g, x, y)
        delegate.color = currentColor
    }

    override fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int) {
        val adjusted = TerminalContrast.cachedEnsureContrast(currentColor, currentBg)
        delegate.color = adjusted
        delegate.drawLine(x1, y1, x2, y2)
        delegate.color = currentColor
    }

    override fun create(): Graphics =
        ContrastAdjustingGraphics2D(delegate.create() as Graphics2D, settings, currentBg)

    override fun create(x: Int, y: Int, width: Int, height: Int): Graphics =
        ContrastAdjustingGraphics2D(delegate.create(x, y, width, height) as Graphics2D, settings, currentBg)

    override fun translate(x: Int, y: Int) = delegate.translate(x, y)
    override fun translate(tx: Double, ty: Double) = delegate.translate(tx, ty)
    override fun rotate(theta: Double) = delegate.rotate(theta)
    override fun rotate(theta: Double, x: Double, y: Double) = delegate.rotate(theta, x, y)
    override fun scale(sx: Double, sy: Double) = delegate.scale(sx, sy)
    override fun shear(shx: Double, shy: Double) = delegate.shear(shx, shy)
    override fun transform(Tx: AffineTransform) = delegate.transform(Tx)
    override fun setTransform(Tx: AffineTransform) { delegate.transform = Tx }
    override fun getTransform(): AffineTransform = delegate.transform
    override fun getPaint(): Paint = delegate.paint
    override fun getComposite(): Composite = delegate.composite
    override fun setBackground(color: Color) { delegate.background = color }
    override fun getBackground(): Color = delegate.background
    override fun getStroke(): Stroke = delegate.stroke
    override fun clip(s: Shape) = delegate.clip(s)
    override fun getFontRenderContext(): FontRenderContext = delegate.fontRenderContext
    override fun setPaintMode() = delegate.setPaintMode()
    override fun setXORMode(c1: Color) = delegate.setXORMode(c1)
    override fun getFont(): Font = delegate.font
    override fun setFont(font: Font) { delegate.font = font }
    override fun getFontMetrics(f: Font): FontMetrics = delegate.getFontMetrics(f)
    override fun getClipBounds(): Rectangle? = delegate.clipBounds
    override fun clipRect(x: Int, y: Int, width: Int, height: Int) = delegate.clipRect(x, y, width, height)
    override fun setClip(x: Int, y: Int, width: Int, height: Int) = delegate.setClip(x, y, width, height)
    override fun getClip(): Shape? = delegate.clip
    override fun setClip(clip: Shape?) { delegate.clip = clip }
    override fun copyArea(x: Int, y: Int, width: Int, height: Int, dx: Int, dy: Int) =
        delegate.copyArea(x, y, width, height, dx, dy)
    override fun clearRect(x: Int, y: Int, width: Int, height: Int) = delegate.clearRect(x, y, width, height)
    override fun drawRoundRect(x: Int, y: Int, width: Int, height: Int, arcWidth: Int, arcHeight: Int) =
        delegate.drawRoundRect(x, y, width, height, arcWidth, arcHeight)
    override fun fillRoundRect(x: Int, y: Int, width: Int, height: Int, arcWidth: Int, arcHeight: Int) =
        delegate.fillRoundRect(x, y, width, height, arcWidth, arcHeight)
    override fun drawOval(x: Int, y: Int, width: Int, height: Int) = delegate.drawOval(x, y, width, height)
    override fun fillOval(x: Int, y: Int, width: Int, height: Int) = delegate.fillOval(x, y, width, height)
    override fun drawArc(x: Int, y: Int, width: Int, height: Int, startAngle: Int, arcAngle: Int) =
        delegate.drawArc(x, y, width, height, startAngle, arcAngle)
    override fun fillArc(x: Int, y: Int, width: Int, height: Int, startAngle: Int, arcAngle: Int) =
        delegate.fillArc(x, y, width, height, startAngle, arcAngle)
    override fun drawPolyline(xPoints: IntArray, yPoints: IntArray, nPoints: Int) =
        delegate.drawPolyline(xPoints, yPoints, nPoints)
    override fun drawPolygon(xPoints: IntArray, yPoints: IntArray, nPoints: Int) =
        delegate.drawPolygon(xPoints, yPoints, nPoints)
    override fun fillPolygon(xPoints: IntArray, yPoints: IntArray, nPoints: Int) =
        delegate.fillPolygon(xPoints, yPoints, nPoints)
    override fun drawBytes(data: ByteArray, offset: Int, length: Int, x: Int, y: Int) =
        delegate.drawBytes(data, offset, length, x, y)
    override fun drawImage(img: Image, x: Int, y: Int, observer: ImageObserver?): Boolean =
        delegate.drawImage(img, x, y, observer)
    override fun drawImage(img: Image, x: Int, y: Int, width: Int, height: Int, observer: ImageObserver?): Boolean =
        delegate.drawImage(img, x, y, width, height, observer)
    override fun drawImage(img: Image, x: Int, y: Int, bgcolor: Color?, observer: ImageObserver?): Boolean =
        delegate.drawImage(img, x, y, bgcolor, observer)
    override fun drawImage(img: Image, x: Int, y: Int, width: Int, height: Int, bgcolor: Color?, observer: ImageObserver?): Boolean =
        delegate.drawImage(img, x, y, width, height, bgcolor, observer)
    override fun drawImage(img: Image, dx1: Int, dy1: Int, dx2: Int, dy2: Int, sx1: Int, sy1: Int, sx2: Int, sy2: Int, observer: ImageObserver?): Boolean =
        delegate.drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, observer)
    override fun drawImage(img: Image, dx1: Int, dy1: Int, dx2: Int, dy2: Int, sx1: Int, sy1: Int, sx2: Int, sy2: Int, bgcolor: Color?, observer: ImageObserver?): Boolean =
        delegate.drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, bgcolor, observer)
    override fun draw(s: Shape) = delegate.draw(s)
    override fun drawImage(img: Image, xform: AffineTransform, obs: ImageObserver?): Boolean =
        delegate.drawImage(img, xform, obs)
    override fun drawImage(img: BufferedImage, op: BufferedImageOp?, x: Int, y: Int) =
        delegate.drawImage(img, op, x, y)
    override fun drawRenderedImage(img: RenderedImage, xform: AffineTransform) =
        delegate.drawRenderedImage(img, xform)
    override fun drawRenderableImage(img: RenderableImage, xform: AffineTransform) =
        delegate.drawRenderableImage(img, xform)
    override fun fill(s: Shape) = delegate.fill(s)
    override fun hit(rect: Rectangle, s: Shape, onStroke: Boolean): Boolean =
        delegate.hit(rect, s, onStroke)
    override fun getDeviceConfiguration(): GraphicsConfiguration = delegate.deviceConfiguration
    override fun setComposite(comp: Composite) { delegate.composite = comp }
    override fun setPaint(paint: Paint) { delegate.paint = paint }
    override fun setStroke(s: Stroke) { delegate.stroke = s }
    override fun setRenderingHint(hintKey: RenderingHints.Key, hintValue: Any?) =
        delegate.setRenderingHint(hintKey, hintValue)
    override fun getRenderingHint(hintKey: RenderingHints.Key): Any? = delegate.getRenderingHint(hintKey)
    override fun setRenderingHints(hints: Map<*, *>) { delegate.setRenderingHints(hints) }
    override fun addRenderingHints(hints: Map<*, *>) { delegate.addRenderingHints(hints) }
    override fun getRenderingHints(): RenderingHints = delegate.renderingHints
    override fun dispose() = delegate.dispose()
}

/**
 * The terminal's scrollbar: a thin thumb in the terminal's own colours that paints only when the
 * buffer extends beyond the visible screen.
 *
 * It keeps a fixed width of [WIDTH_PX] down the right edge rather than resizing between 0 and 10px.
 * Toggling preferred width dynamically between 0 and 10px causes the terminal panel beside it to
 * lose and gain a column every time output runs off the top of the viewport, sending `SIGWINCH`
 * to the running process and triggering an infinite layout/redraw loop with TUIs like Antigravity.
 * When the buffer fits on screen, the gutter blends seamlessly into the terminal background; when
 * scrollable, the thumb appears.
 */
private class TerminalScrollBar(private val settings: NopTerminalSettings) : JScrollBar(VERTICAL) {

    /** Whether the buffer currently extends past the visible screen. */
    var scrollable: Boolean = false
        private set

    init {
        isOpaque = false
        isFocusable = false
        setUI(TerminalScrollBarUI(settings))
        addAdjustmentListener { refresh() }
    }

    override fun getPreferredSize(): Dimension {
        val height = super.getPreferredSize().height
        return Dimension(WIDTH_PX, height)
    }

    private fun refresh() {
        val model = model ?: return
        val needed = model.maximum - model.minimum > model.extent
        if (needed == scrollable) return
        scrollable = needed
        repaint()
    }

    private companion object {
        /** Slim, like an IDE's overlay scrollbars — it is a position indicator, not a control. */
        const val WIDTH_PX = 10
    }
}

/**
 * A flat scrollbar with no arrow buttons, painted from the terminal's live colours so it re-themes
 * with everything else when the user toggles light/dark.
 */
private class TerminalScrollBarUI(private val settings: NopTerminalSettings) : BasicScrollBarUI() {

    override fun createDecreaseButton(orientation: Int): JButton = hiddenButton()

    override fun createIncreaseButton(orientation: Int): JButton = hiddenButton()

    private fun hiddenButton(): JButton = JButton().apply {
        val none = Dimension(0, 0)
        preferredSize = none
        minimumSize = none
        maximumSize = none
        isFocusable = false
        border = null
    }

    override fun paintTrack(g: Graphics, c: JComponent, bounds: Rectangle) {
        g.color = settings.bg
        g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height)
    }

    override fun paintThumb(g: Graphics, c: JComponent, bounds: Rectangle) {
        val bar = scrollbar as? TerminalScrollBar
        if (bar != null && !bar.scrollable) return
        if (bounds.isEmpty || !scrollbar.isEnabled) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = thumbColor()
            val inset = 2
            val width = bounds.width - inset * 2
            g2.fillRoundRect(bounds.x + inset, bounds.y + inset, width, bounds.height - inset * 2, width, width)
        } finally {
            g2.dispose()
        }
    }

    /** The text colour, well faded: legible against either background without competing with output. */
    private fun thumbColor(): Color =
        Color(settings.fg.red, settings.fg.green, settings.fg.blue, if (isThumbRollover) 160 else 96)
}
