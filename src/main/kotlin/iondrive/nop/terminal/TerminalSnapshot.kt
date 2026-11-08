package iondrive.nop.terminal

import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StoredCursor
import com.jediterm.terminal.util.CharUtils

/** Restores the primary screen and its history without replaying obsolete TUI redraws. */
internal object TerminalSnapshot {
    fun replay(terminal: JediTerminal, modes: ByteArray, raw: ByteArray): ByteArray? {
        val buffer = terminal.terminalTextBuffer
        buffer.lock()
        try {
            // Alternate screens also own a saved primary screen, which the raw replay restores.
            if (buffer.isUsingAlternateBuffer) return null
            val pending = pendingControl(raw) ?: return null
            val out = StringBuilder("\u001b[?6l\u001b[?7h\u001b[r\u001b[0m\u001b[2J\u001b[H")
            fun attributes(value: TextStyle) = sgr(value, terminal.styleState.defaultForeground, terminal.styleState.defaultBackground)
            var style: TextStyle? = null
            var wrapped = false
            for (row in -buffer.historyLinesCount until buffer.height) {
                val line = buffer.getLine(row)
                if (row != -buffer.historyLinesCount && !wrapped) out.append("\r\n")
                // An empty continuation must still trigger the previous line's delayed wrap.
                if (wrapped && line.length() == 0) out.append(" \b")
                var cells = 0
                for (entry in line.entries) {
                    if (cells >= buffer.width) break
                    if (style != entry.style) { style = entry.style; out.append(attributes(entry.style)) }
                    val text = entry.text
                    if (entry.isNul) {
                        val count = minOf(text.length, buffer.width - cells)
                        if (count > 0) out.append("\u001b[${count}X\u001b[${count}C")
                        cells += count
                        continue
                    }
                    for (i in 0 until minOf(text.length, buffer.width - cells)) {
                        val ch = text[i]
                        if (ch != CharUtils.DWC) out.append(if (ch == '\u0000') ' ' else ch)
                        cells++
                    }
                }
                if (line.isWrapped && cells < buffer.width) out.append(" ".repeat(buffer.width - cells))
                wrapped = line.isWrapped
            }
            val bottom = scrollBottom.getInt(terminal)
            out.append("\u001b[${terminal.scrollRegionTop};${bottom}r")
            fun cursor(x: Int, y: Int, origin: Boolean) {
                val row = if (origin) y - terminal.scrollRegionTop + 1 else y
                out.append("\u001b[$row;${x.coerceAtMost(buffer.width)}H")
                if (x > buffer.width) {
                    // CUP cancels delayed wrapping. Reprint the final glyph to restore it.
                    val line = buffer.getLine(y - 1)
                    var column = buffer.width - 1
                    while (column > 0 && (line.charAt(column) == CharUtils.DWC || line.charAt(column).isLowSurrogate())) column--
                    out.append("\u001b[$row;${column + 1}H").append(attributes(line.getStyleAt(column) ?: TextStyle.EMPTY))
                    for (i in column until buffer.width) {
                        val ch = line.charAt(i)
                        if (ch != CharUtils.DWC) out.append(if (ch == '\u0000') ' ' else ch)
                    }
                }
            }
            (savedCursor.get(terminal) as? StoredCursor)?.let { saved ->
                if (saved.isOriginMode) out.append("\u001b[?6h")
                if (!saved.isAutoWrap) out.append("\u001b[?7l")
                cursor(saved.cursorX + 1, saved.cursorY, saved.isOriginMode)
                out.append(attributes(saved.textStyle)).append("\u001b7\u001b[?6l\u001b[?7h")
            }
            if (terminal.isOriginMode) out.append("\u001b[?6h")
            cursor(terminal.cursorX, terminal.cursorY, terminal.isOriginMode)
            out.append(attributes(terminal.styleState.current))
            return out.toString().toByteArray(Charsets.UTF_8) + modes + pending
        } finally {
            buffer.unlock()
        }
    }

    private val scrollBottom = JediTerminal::class.java.getDeclaredField("myScrollRegionBottom").apply { isAccessible = true }
    private val savedCursor = JediTerminal::class.java.getDeclaredField("myStoredCursor").apply { isAccessible = true }

    private fun sgr(style: TextStyle, defaultForeground: TerminalColor, defaultBackground: TerminalColor): String {
        val params = mutableListOf("0")
        for ((option, code) in options) if (style.hasOption(option)) params += code
        fun color(value: TerminalColor?, foreground: Boolean) {
            if (value == null) return
            // Theme colours remain defaults, rather than becoming fixed RGB colours in the replay.
            if (value === if (foreground) defaultForeground else defaultBackground) return
            val kind = if (foreground) "38" else "48"
            params += if (value.isIndexed) "$kind;5;${value.colorIndex}" else value.toColor().let { "$kind;2;${it.red};${it.green};${it.blue}" }
        }
        color(style.foreground, true)
        color(style.background, false)
        return "\u001b[${params.joinToString(";")}m"
    }

    private val options = mapOf(TextStyle.Option.BOLD to "1", TextStyle.Option.DIM to "2",
        TextStyle.Option.ITALIC to "3", TextStyle.Option.UNDERLINED to "4", TextStyle.Option.SLOW_BLINK to "5",
        TextStyle.Option.RAPID_BLINK to "6", TextStyle.Option.INVERSE to "7", TextStyle.Option.HIDDEN to "8")

    /** A control sequence split by the freeze must be completed by the next PTY read. */
    private fun pendingControl(raw: ByteArray): ByteArray? {
        var state = 0
        var start = 0
        var utf8 = 0
        for (i in raw.indices) {
            val ch = raw[i].toInt() and 255
            utf8 = when {
                ch in 0x80..0xbf -> (utf8 - 1).coerceAtLeast(0)
                ch in 0xc2..0xdf -> 1
                ch in 0xe0..0xef -> 2
                ch in 0xf0..0xf4 -> 3
                else -> 0
            }
            when (state) {
                0 -> if (ch == 27) { start = i; state = 1 }
                1 -> state = when (ch) { 27 -> { start = i; 1 }; 91 -> 2; 93 -> 3; 80, 88, 94, 95 -> 4; in 0x20..0x2f -> 1; else -> 0 }
                2 -> when { ch == 27 -> { start = i; state = 1 }; ch in 0x40..0x7e || ch == 24 || ch == 26 -> state = 0 }
                3, 4 -> if (ch == 27) state += 2 else if (ch == 7 && state == 3) state = 0
                5, 6 -> state = if (ch == 92) 0 else if (ch == 27) state else state - 2
            }
        }
        // The decoder can hold an unfinished character; use the byte replay in that case.
        if (utf8 > 0) return null
        return if (state == 0) ByteArray(0) else raw.copyOfRange(start, raw.size)
    }
}
