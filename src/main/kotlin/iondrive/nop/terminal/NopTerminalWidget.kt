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
 * The terminal's scrollbar: nothing at all until the buffer is taller than the screen, then a thin
 * thumb in the terminal's own colours.
 *
 * Hidden means *gone*, not blank: JediTerm's layout hands every child the width it asks for without
 * consulting `isVisible`, so a bar that merely stopped painting would still hold its strip of the
 * terminal. Reporting a zero preferred width is what gives those columns back to the text.
 *
 * The bar re-checks itself on adjustment events. Swing raises one for *any* change to the model, not
 * just a moved thumb — including the range changes that happen as lines scroll off the top — which
 * is exactly when "is there anything to scroll to" can change, and the listener list survives
 * JediTerm swapping in the terminal's own model after construction.
 */
private class TerminalScrollBar(private val settings: NopTerminalSettings) : JScrollBar(VERTICAL) {

    /** Whether the buffer currently extends past the visible screen. */
    private var scrollable = false

    init {
        isOpaque = false
        isFocusable = false
        setUI(TerminalScrollBarUI(settings))
        addAdjustmentListener { refresh() }
    }

    override fun getPreferredSize(): Dimension {
        val height = super.getPreferredSize().height
        return if (scrollable) Dimension(WIDTH_PX, height) else Dimension(0, height)
    }

    private fun refresh() {
        val model = model ?: return
        val needed = model.maximum - model.minimum > model.extent
        if (needed == scrollable) return
        scrollable = needed
        isVisible = needed
        // The terminal beside it grows or shrinks by the bar's width, so the whole widget has to be
        // laid out again — revalidating the bar alone would leave the text where it was.
        parent?.revalidate()
        parent?.repaint()
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
