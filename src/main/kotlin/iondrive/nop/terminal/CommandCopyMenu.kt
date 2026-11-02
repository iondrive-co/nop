package iondrive.nop.terminal

import com.jediterm.terminal.model.TerminalTextBuffer
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/** Resolves the clicked cells afresh; clipboard text always comes from the transcript. */
internal class CommandCopyMenu(private val commands: TerminalCommands) {
    fun addTo(menu: JPopupMenu, buffer: TerminalTextBuffer, row: Int, column: Int, cursor: Int) {
        val sources = commands.snippets
        if (sources.isEmpty()) return
        val matches: List<TerminalCommands.Match>
        buffer.lock()
        try {
            val wrapWidth = ((buffer.width - 16) / 2).coerceAtLeast(1)
            val context = sources.maxOf { it.text.count { c -> c == '\n' } + it.compact.length / wrapWidth + 8 }
            val first = maxOf(-buffer.historyLinesCount, row - context)
            val last = minOf(buffer.height, row + context + 1)
            matches = commands.find((first until last).map { buffer.getLine(it).text }, first, cursor, sources)
                .filter { match -> match.spans.any { it.row == row && column in it.start until it.end } }
        } finally {
            buffer.unlock()
        }
        for (match in matches.asReversed()) {
            menu.insert(JMenuItem(match.snippet.label).apply {
                toolTipText = "Copy the complete original text, preserving intentional newlines"
                addActionListener { commands.copy(match.snippet.text) }
            }, 0)
        }
        if (matches.isNotEmpty() && menu.componentCount > matches.size) menu.insert(JPopupMenu.Separator(), matches.size)
    }
}
