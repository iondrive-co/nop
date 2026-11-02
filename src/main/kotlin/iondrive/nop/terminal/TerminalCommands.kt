package iondrive.nop.terminal

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.parser.Parser
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Original code from assistant messages. Screen text locates it, but never supplies clipboard text. */
internal class TerminalCommands(
    val copy: (String) -> Unit = {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(it), null)
    },
) {
    data class Snippet(val text: String, val command: Boolean) {
        val compact: String = text.filterNot(::displaySpace)
        val label: String get() = if (command) "Copy command" else "Copy code"
    }

    data class Span(val row: Int, val start: Int, val end: Int)
    data class Match(val snippet: Snippet, val spans: List<Span>)

    private val parser = Parser.builder().build()
    private val known = LinkedHashMap<String, Snippet>()

    @Volatile
    var snippets: List<Snippet> = emptyList()
        private set

    /** Called on the transcript reader's thread, including when loading a restored conversation. */
    @Synchronized
    fun addMessage(text: String): Boolean {
        var changed = false
        fun add(content: String, command: Boolean) {
            if (content.isBlank() || content in known) return
            known[content] = Snippet(content, command)
            changed = true
        }
        parser.parse(text).accept(object : AbstractVisitor() {
            override fun visit(code: Code) {
                // Single identifiers and file names would put a button on nearly every prose row.
                val content = code.literal
                if (content.any(Char::isWhitespace) || content.startsWith("./") || content.startsWith("/")) {
                    add(content, command = true)
                }
            }

            override fun visit(code: FencedCodeBlock) {
                val language = code.info.orEmpty().substringBefore(' ').lowercase()
                add(code.literal.removeSuffix("\n"), language in SHELLS)
            }

            override fun visit(code: IndentedCodeBlock) {
                add(code.literal.removeSuffix("\n"), command = false)
            }
        })
        if (changed) snippets = known.values.toList()
        return changed
    }

    /**
     * Matches whole snippets across terminal rows, ignoring display whitespace and box edges.
     * Distinct originals that look the same after wrapping are ambiguous and get no copy control.
     * [firstRow] uses the terminal buffer's row numbers, including negative scrollback rows.
     */
    fun find(lines: List<String>, firstRow: Int, cursor: Int, sources: List<Snippet> = snippets): List<Match> {
        if (sources.isEmpty()) return emptyList()
        val stride = (lines.maxOfOrNull { it.length } ?: 0) + 1
        val cells = IntArray(lines.sumOf { it.length })
        var cellCount = 0
        val screen = buildString {
            lines.forEachIndexed { index, line ->
                val first = line.indexOfFirst { !displaySpace(it) }
                val last = line.indexOfLast { !displaySpace(it) }
                for (column in line.indices) {
                    val c = line[column]
                    if (displaySpace(c) || (c == '│' && (column == first || column == last))) continue
                    append(c)
                    cells[cellCount++] = index * stride + column
                }
            }
        }
        val found = ArrayList<Match>()
        for ((compact, originals) in sources.groupBy { it.compact }) {
            if (compact.isEmpty() || originals.map { it.text }.distinct().size != 1) continue
            val snippet = originals.first()
            var at = screen.indexOf(compact)
            while (at >= 0) {
                val end = at + compact.length
                val spans = ArrayList<Span>()
                for (i in at until end) {
                    val cell = Span(firstRow + cells[i] / stride, cells[i] % stride, cells[i] % stride + 1)
                    val previous = spans.lastOrNull()
                    if (previous?.row == cell.row) {
                        spans[spans.lastIndex] = previous.copy(end = cell.end)
                    } else spans += cell
                }
                if (spans.none { it.row == cursor }) found += Match(snippet, spans)
                at = screen.indexOf(compact, at + 1)
            }
        }
        // A code block owns its embedded inline snippets, so it gets one control for the whole block.
        val accepted = ArrayList<Match>()
        for (match in found.sortedByDescending { it.snippet.compact.length }) {
            if (accepted.none { other -> match.spans.any { a -> other.spans.any { b ->
                    a.row == b.row && a.start < b.end && b.start < a.end
                } } }) accepted += match
        }
        return accepted.sortedWith(compareBy({ it.spans.first().row }, { it.spans.first().start }))
    }

    companion object {
        private val SHELLS = setOf("", "sh", "shell", "bash", "zsh", "fish", "console", "powershell", "ps1", "cmd", "bat")
        private fun displaySpace(c: Char): Boolean = c.isWhitespace() || c == '\u0000'
    }
}
