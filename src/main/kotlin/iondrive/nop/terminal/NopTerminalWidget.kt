package iondrive.nop.terminal

import com.jediterm.terminal.ui.JediTermWidget
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollBar
import javax.swing.plaf.basic.BasicScrollBarUI

/**
 * JediTerm's widget with nop's scrollbar instead of the stock one.
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

    /**
     * Takes the settings back off the base class rather than keeping a property of its own: JediTerm
     * builds the scrollbar from inside *its* constructor, before this class's fields are assigned,
     * so `private val settings` would still be null here. `mySettingsProvider` is set before that
     * call, and is the very object handed in above.
     */
    override fun createScrollBar(): JScrollBar =
        TerminalScrollBar(mySettingsProvider as NopTerminalSettings)
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
