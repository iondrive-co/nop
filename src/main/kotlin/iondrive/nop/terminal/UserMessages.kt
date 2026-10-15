package iondrive.nop.terminal

import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.model.TerminalTextBuffer

/**
 * Picks out, among the prompts an agent CLI echoes into its conversation, the ones the user typed, so
 * the terminal can paint them apart from the ones nop typed in.
 *
 * Claude Code and Codex echo every prompt they are sent as a band of rows in a background of their
 * own, Claude's dark grey and Codex's pale one, headed by their prompt marker (`❯`, `›`). Codex pads
 * the band with a blank row above and below. What nop types in is echoed the same way: messages from
 * other agents and services, the prompt that resumes a run once its usage limit resets, and the line
 * a handover starts the next CLI with. Each of those begins with one of [nopPrompts], and every other
 * echoed prompt is the user's.
 *
 * Codex draws its input box as just such a band. Only the cursor tells the two apart: it is in the
 * box, and never in an echo.
 */
internal class UserMessages(nopPrompts: List<String>) {

    /** The prompts, with the spaces taken out: an echo wraps them wherever its width runs out. */
    private val prompts = nopPrompts.map(::squeezed).filter { it.isNotEmpty() }
    private val longest = prompts.maxOfOrNull { it.length } ?: 0

    /**
     * The rows a terminal holds, from [top] (the oldest line of scrollback, negative when there is
     * any) up to [bottom], exclusive. Rows are numbered as [TerminalTextBuffer.getLine] numbers them.
     */
    interface Rows {
        val top: Int
        val bottom: Int

        /** The background [row] starts with, or null when it starts on the terminal's own. */
        fun background(row: Int): TerminalColor?

        fun text(row: Int): String
    }

    /**
     * The user's messages that show in `from until to`, one range of rows each and cut to that span,
     * in order. A band that begins above [from] is read from where it begins, which is where the
     * marker and the start of its text are. [cursor] is the row the terminal's cursor is on.
     */
    fun find(rows: Rows, from: Int, to: Int, cursor: Int): List<IntRange> {
        val end = minOf(to, rows.bottom)
        var row = maxOf(from, rows.top)
        if (row >= end) return emptyList()
        rows.background(row)?.let { bg ->
            while (row > rows.top && rows.background(row - 1) == bg) row--
        }
        val found = ArrayList<IntRange>()
        while (row < end) {
            val bg = rows.background(row)
            if (bg == null) {
                row++
                continue
            }
            val start = row
            while (row < rows.bottom && rows.background(row) == bg) row++
            val band = start until row
            if (cursor !in band && isUsers(rows, band)) {
                val shown = maxOf(band.first, from)..minOf(band.last, end - 1)
                if (!shown.isEmpty()) found += shown
            }
        }
        return found
    }

    /** Whether [band] echoes a prompt, and one that does not begin as any of nop's do. */
    private fun isUsers(rows: Rows, band: IntRange): Boolean {
        // Claude's marker is on the band's first row; Codex's is on its second, under the padding.
        val head = (band.first..minOf(band.last, band.first + 1)).firstOrNull { textAfterMarker(rows.text(it)) >= 0 }
            ?: return false
        val line = rows.text(head)
        val text = StringBuilder(squeezed(line.substring(textAfterMarker(line))))
        var next = head + 1
        while (text.length < longest && next <= band.last) text.append(squeezed(rows.text(next++)))
        return prompts.none { text.startsWith(it) }
    }

    companion object {
        /** What Claude Code (`❯`) and Codex (`›`) head an echoed prompt with. */
        private const val MARKERS = "❯›"

        /** Where the text after a prompt marker starts on [text], or -1 when [text] does not start with one. */
        internal fun textAfterMarker(text: String): Int {
            var i = 0
            while (i < text.length && TerminalSession.isBlank(text[i])) i++
            if (i >= text.length || text[i] !in MARKERS) return -1
            i++
            return if (i == text.length || TerminalSession.isBlank(text[i])) i else -1
        }

        private fun squeezed(text: String): String = text.filterNot(TerminalSession::isBlank)

        /**
         * [buffer] as [find] reads it. Call with the buffer locked. A cell written on the terminal's
         * default background carries [defaultBackground], which counts as no background of its own.
         */
        fun rowsOf(buffer: TerminalTextBuffer, defaultBackground: TerminalColor?): Rows = object : Rows {
            override val top: Int = -buffer.historyLinesCount
            override val bottom: Int = buffer.height

            override fun background(row: Int): TerminalColor? {
                val bg = buffer.getLine(row).getStyleAt(0)?.background
                return if (bg == null || bg == defaultBackground) null else bg
            }

            override fun text(row: Int): String = buffer.getLine(row).text
        }
    }
}
