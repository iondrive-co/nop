package iondrive.nop.terminal

import com.jediterm.terminal.ui.TerminalPanel
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicLong
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.Timer
import javax.swing.border.LineBorder

/** Swing children in the terminal's reserved gutter; clicks never reach the CLI's mouse handler. */
internal class CommandCopyGutter(
    private val panel: TerminalPanel,
    private val commands: TerminalCommands,
    private val settings: NopTerminalSettings,
    private val geometry: () -> Geometry,
) {
    data class Geometry(val origin: Int, val rows: Int, val columnWidth: Int, val rowHeight: Int, val textX: Int, val cursor: Int)

    private val revision = AtomicLong()
    private var cachedRevision = -1L
    private var cachedGeometry: Geometry? = null
    private var cachedSources: List<TerminalCommands.Snippet>? = null
    private var matches = emptyList<TerminalCommands.Match>()
    private var groups = emptyList<List<TerminalCommands.Match>>()
    private var buttons = emptyList<JButton>()
    private var hovered: TerminalCommands.Match? = null
    private var copied: String? = null
    private val feedback = Timer(1800) {
        copied = null
        updateLabels()
    }.apply { isRepeats = false }

    fun clearHover() {
        if (hovered != null) {
            hovered = null
            panel.repaint()
        }
    }

    init {
        panel.terminalTextBuffer.addModelListener { revision.incrementAndGet() }
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val g = geometry()
                val row = g.origin + e.y / g.rowHeight.coerceAtLeast(1)
                val column = (e.x - g.textX) / g.columnWidth.coerceAtLeast(1)
                val next = matches.firstOrNull { match -> match.spans.any { it.row == row && column in it.start until it.end } }
                if (next != hovered) {
                    hovered = next
                    panel.repaint()
                }
            }

            override fun mouseExited(e: MouseEvent) {
                clearHover()
            }
        }
        panel.addMouseMotionListener(mouse)
        panel.addMouseListener(mouse)
    }

    /** Called after JediTerm has settled its scroll origin for this paint. */
    fun paint(g: Graphics2D) {
        refresh()
        val geo = geometry()
        val graphics = g.create() as Graphics2D
        try {
            graphics.color = settings.bg
            graphics.fillRect(0, 0, WIDTH, panel.height)
            val active = buttons.indices.filter { buttons[it].model.isRollover || buttons[it].hasFocus() }
                .flatMap { groups[it] }.toSet() + listOfNotNull(hovered)
            for (match in matches) {
                val shown = match.spans.filter { it.row in geo.origin until geo.origin + geo.rows }
                if (shown.isEmpty()) continue
                if (match in active) {
                    graphics.color = Color(settings.link.red, settings.link.green, settings.link.blue, 35)
                    for (span in shown) {
                        graphics.fillRect(geo.textX + span.start * geo.columnWidth,
                            (span.row - geo.origin) * geo.rowHeight,
                            (span.end - span.start) * geo.columnWidth, geo.rowHeight)
                    }
                }
                graphics.color = if (match in active) settings.link else muted()
                graphics.stroke = BasicStroke(1f)
                val x = WIDTH - 5
                val top = (shown.first().row - geo.origin) * geo.rowHeight + 2
                val bottom = (shown.last().row - geo.origin + 1) * geo.rowHeight - 3
                graphics.drawLine(x, top, x, bottom)
                graphics.drawLine(x, top, x + 3, top)
                graphics.drawLine(x, bottom, x + 3, bottom)
            }
            for (button in buttons) {
                if (button.background != settings.bg) button.background = settings.bg
                if (button.foreground != settings.fg) button.foreground = settings.fg
                if ((button.border as? LineBorder)?.lineColor != muted()) {
                    button.border = BorderFactory.createLineBorder(muted(), 1, true)
                }
            }
        } finally {
            graphics.dispose()
        }
    }

    private fun refresh() {
        val geo = geometry()
        if (geo.rowHeight <= 0 || geo.columnWidth <= 0) return
        val sources = commands.snippets
        val version = revision.get()
        if (version == cachedRevision && geo == cachedGeometry && sources === cachedSources) return
        cachedRevision = version
        cachedGeometry = geo
        cachedSources = sources
        val buffer = panel.terminalTextBuffer
        // Allow for the CLI's indentation and word wrapping, as well as source line breaks.
        val wrapWidth = ((buffer.width - 16) / 2).coerceAtLeast(1)
        val context = sources.maxOfOrNull { it.text.count { c -> c == '\n' } + it.compact.length / wrapWidth + 8 } ?: 0
        var first: Int
        val lines: List<String>
        buffer.lock()
        try {
            first = maxOf(-buffer.historyLinesCount, geo.origin - context)
            val last = minOf(buffer.height, geo.origin + geo.rows + context)
            lines = (first until last).map { buffer.getLine(it).text }
        } finally {
            buffer.unlock()
        }
        matches = commands.find(lines, first, geo.cursor, sources).filter { match ->
            match.spans.any { it.row in geo.origin until geo.origin + geo.rows }
        }
        val next = matches.groupBy { maxOf(it.spans.first().row, geo.origin) }.values.toList()
        if (next != groups) {
            buttons.forEach(panel::remove)
            groups = next
            buttons = groups.map { group ->
                JButton().apply {
                    font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
                    margin = Insets(0, 3, 0, 3)
                    isFocusable = false
                    isRolloverEnabled = true
                    addChangeListener { panel.repaint() }
                    addActionListener {
                        // A redraw or scroll can move a command between the last paint and a click.
                        refresh()
                        if (parent !== panel || this !in buttons) return@addActionListener
                        if (group.size == 1) copy(group.single(), this) else choose(group, this)
                    }
                    panel.add(this)
                }
            }
        }
        updateLabels()
        for ((index, group) in groups.withIndex()) {
            val y = (maxOf(group.first().spans.first().row, geo.origin) - geo.origin) * geo.rowHeight
            buttons[index].setBounds(3, y, WIDTH - 13, geo.rowHeight.coerceAtMost(25))
        }
    }

    private fun updateLabels() {
        for ((index, group) in groups.withIndex()) {
            val single = group.singleOrNull()
            val label = when {
                single != null && single.snippet.text == copied -> "Copied"
                single != null -> single.snippet.label
                else -> "Copy (${group.size})…"
            }
            buttons[index].text = label
            buttons[index].accessibleContext.accessibleName = label
            buttons[index].toolTipText = if (single != null)
                "Copy the entire original ${if (single.snippet.command) "command" else "code block"}, preserving intentional newlines"
            else "Choose a command or code block to copy"
        }
    }

    private fun copy(match: TerminalCommands.Match, button: JButton) {
        runCatching { commands.copy(match.snippet.text) }.onSuccess {
            copied = match.snippet.text
            updateLabels()
            feedback.restart()
        }.onFailure {
            button.text = "Try again"
            button.toolTipText = "Clipboard unavailable. Click to try copying again."
        }
    }

    private fun choose(group: List<TerminalCommands.Match>, button: JButton) {
        JPopupMenu().apply {
            isLightWeightPopupEnabled = false
            for (match in group) {
                add(JMenuItem(match.snippet.text.replace('\n', ' ').let {
                    if (it.length > 70) it.take(67) + "…" else it
                }).apply { addActionListener { copy(match, button) } })
            }
            show(button, 0, button.height)
        }
    }

    private fun muted() = Color((settings.fg.red + settings.bg.red * 3) / 4,
        (settings.fg.green + settings.bg.green * 3) / 4, (settings.fg.blue + settings.bg.blue * 3) / 4)

    companion object {
        const val WIDTH = 112
    }
}
