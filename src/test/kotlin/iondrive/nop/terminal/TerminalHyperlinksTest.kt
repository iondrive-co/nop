package iondrive.nop.terminal

import com.jediterm.terminal.HyperlinkStyle
import com.jediterm.terminal.ArrayTerminalDataStream
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.terminal.ui.hyperlinks.LinkInfoEx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.event.MouseEvent
import javax.swing.JPanel

/**
 * URL detection in launcher output, the link objects handed to JediTerm, and how
 * [CellStylePreservingLinks] writes them into a (headless) widget's buffer.
 */
class TerminalHyperlinksTest {

    private fun urls(line: String): List<String> =
        UrlHyperlinkFilter.findUrls(line).map { line.substring(it.first, it.last + 1) }

    @Test
    fun `finds a bare url`() {
        assertEquals(listOf("http://localhost:5173/"), urls("  Local:   http://localhost:5173/"))
    }

    @Test
    fun `finds https and query strings`() {
        assertEquals(
            listOf("https://ci.example.com/jobs/42?tab=log&raw=1"),
            urls("job: https://ci.example.com/jobs/42?tab=log&raw=1"),
        )
    }

    @Test
    fun `finds several urls on one line`() {
        assertEquals(
            listOf("http://a.test/x", "https://b.test/y"),
            urls("mirrors: http://a.test/x and https://b.test/y"),
        )
    }

    @Test
    fun `drops sentence punctuation after a url`() {
        assertEquals(listOf("https://nop.test/docs"), urls("See https://nop.test/docs."))
        assertEquals(listOf("https://nop.test/a"), urls("try https://nop.test/a, or the other one"))
        assertEquals(listOf("http://host/x"), urls("bound to http://host/x:"))
    }

    @Test
    fun `drops a wrapping bracket but keeps a balanced one`() {
        assertEquals(listOf("http://host/x"), urls("(http://host/x)"))
        assertEquals(listOf("http://host/Foo_(bar)"), urls("wiki http://host/Foo_(bar)"))
        assertEquals(listOf("http://host/x"), urls("[http://host/x]"))
    }

    @Test
    fun `stops at quotes and angle brackets`() {
        assertEquals(listOf("http://host/x"), urls("""curl 'http://host/x'"""))
        assertEquals(listOf("http://host/x"), urls("""curl "http://host/x" -v"""))
        assertEquals(listOf("http://host/x"), urls("<http://host/x>"))
    }

    @Test
    fun `ignores text that only looks like a url`() {
        assertEquals(emptyList<String>(), urls("no links here"))
        assertEquals(emptyList<String>(), urls("scheme only: http://"))
        assertEquals(emptyList<String>(), urls("ftp://host/x is not linkified"))
        // A path that happens to mention the word, but no scheme.
        assertEquals(emptyList<String>(), urls("src/http/server.kt"))
    }

    @Test
    fun `a line with no url produces no link result`() {
        assertNull(UrlHyperlinkFilter().apply("plain build output"))
        assertNull(UrlHyperlinkFilter().apply(null))
    }

    @Test
    fun `link offsets cover exactly the url`() {
        val line = "ready at http://localhost:8080/ now"
        val item = UrlHyperlinkFilter().apply(line)!!.items.single()
        assertEquals("http://localhost:8080/", line.substring(item.startOffset, item.endOffset))
    }

    @Test
    fun `navigating a link opens its url`() {
        val opened = mutableListOf<String>()
        val result = UrlHyperlinkFilter { opened += it }.apply("see https://nop.test/x")!!
        result.items.single().linkInfo.navigate()
        assertEquals(listOf("https://nop.test/x"), opened)
    }

    @Test
    fun `a link offers open and copy in its context menu`() {
        val opened = mutableListOf<String>()
        val info = UrlHyperlinkFilter { opened += it }.apply("see https://nop.test/x")!!
            .items.single().linkInfo
        val click = MouseEvent(JPanel(), MouseEvent.MOUSE_CLICKED, 0L, 0, 1, 1, 1, true)
        val actions = LinkInfoEx.getPopupMenuGroupProvider(info)!!.getPopupMenuGroup(click)
        assertEquals(listOf("Open Link", "Copy Link Address"), actions.map { it.name })

        actions.first().actionPerformed(null)
        assertEquals(listOf("https://nop.test/x"), opened)
        assertTrue(actions.all { it.isEnabled(null) })
    }

    private fun linkedWidget(width: Int, opened: MutableList<String>, height: Int = 24): NopTerminalWidget {
        val settings = NopTerminalSettings(Color.WHITE, Color.BLACK, Color.BLUE)
        return NopTerminalWidget(width, height, settings).also {
            it.addAsyncHyperlinkFilter(
                CellStylePreservingLinks(
                    it.terminalTextBuffer, UrlHyperlinkFilter { url -> opened += url },
                    settings.hyperlinkColor, settings.hyperlinkHighlightingMode,
                ),
            )
        }
    }

    private val longUrl = "https://auth.example.test/authorize?client_id=demo&code_challenge=" +
        "abcdefghij".repeat(9) + "&redirect_uri=http%3A%2F%2Flocalhost%3A4567%2Fcallback"

    private fun feed(widget: NopTerminalWidget, output: String) {
        val emulator = JediEmulator(ArrayTerminalDataStream(output.toCharArray()), widget.terminal)
        while (emulator.hasNext()) emulator.next()
    }

    private fun assertLinkedRows(widget: NopTerminalWidget, rows: List<String>, opened: MutableList<String>) {
        val info = (widget.terminalTextBuffer.getLine(0).getStyleAt(2) as HyperlinkStyle).linkInfo
        rows.forEachIndexed { row, text ->
            for (column in 2 until text.length) {
                val style = widget.terminalTextBuffer.getLine(row).getStyleAt(column)
                assertTrue(style is HyperlinkStyle, "row $row column $column is linked")
                assertSame(info, (style as HyperlinkStyle).linkInfo)
            }
            info.navigate()
        }
        assertEquals(List(rows.size) { longUrl }, opened)
    }

    @Test
    fun `indented agent wraps link every row to the complete url`() {
        val opened = mutableListOf<String>()
        val widget = linkedWidget(64, opened)
        // An agent reserves two columns at each side and draws each row separately.
        val rows = longUrl.chunked(60).map { "  $it" }
        rows.forEachIndexed { row, text ->
            widget.terminal.cursorPosition(1, row + 1)
            widget.terminal.writeCharacters(text)
        }
        assertLinkedRows(widget, rows, opened)
        assertTrue(widget.terminalTextBuffer.getLine(1).getStyleAt(0) !is HyperlinkStyle)
    }

    @Test
    fun `soft wrapped urls skip continuation indentation`() {
        val opened = mutableListOf<String>()
        val widget = linkedWidget(64, opened)
        val rows = longUrl.chunked(62).map { "  $it" }
        // Filled rows autowrap; indentation is still output by the agent on each new row.
        rows.forEach { widget.terminal.writeCharacters(it) }
        assertLinkedRows(widget, rows, opened)
    }

    @Test
    fun `plain terminal autowrap preserves the complete url`() {
        val opened = mutableListOf<String>()
        val widget = linkedWidget(64, opened)
        feed(widget, longUrl)
        longUrl.indices.forEach { offset ->
            val style = widget.terminalTextBuffer.getLine(offset / 64).getStyleAt(offset % 64)
            assertTrue(style is HyperlinkStyle, "URL cell $offset is linked")
            (style as HyperlinkStyle).linkInfo.navigate()
        }
        assertEquals(List(longUrl.length) { longUrl }, opened)
    }

    @Test
    fun `padded agent rows preserve the full link across scrollback and screen`() {
        val opened = mutableListOf<String>()
        val widget = linkedWidget(64, opened, height = 3)
        val rows = longUrl.chunked(60).map { "  $it" }
        feed(widget, rows.joinToString("\r\n") { it.padEnd(64) })
        val buffer = widget.terminalTextBuffer
        val top = -buffer.historyLinesCount
        assertTrue(top < 0, "the URL starts in scrollback")
        val info = (buffer.getLine(top).getStyleAt(2) as HyperlinkStyle).linkInfo
        rows.forEachIndexed { row, text ->
            for (column in 2 until text.length) {
                val style = buffer.getLine(top + row).getStyleAt(column)
                assertTrue(style is HyperlinkStyle, "row $row column $column is linked")
                assertSame(info, (style as HyperlinkStyle).linkInfo)
            }
        }
        info.navigate()
        assertEquals(listOf(longUrl), opened)
    }

    @Test
    fun `short lines and separate urls do not join across newlines`() {
        val opened = mutableListOf<String>()
        val widget = linkedWidget(64, opened)
        val first = "https://example.test/" + "x".repeat(41)
        widget.terminal.writeCharacters(first)
        widget.terminal.cursorPosition(1, 2)
        widget.terminal.writeCharacters("  https://other.test/docs")
        widget.terminal.cursorPosition(1, 3)
        widget.terminal.writeCharacters("  Continue here")
        (widget.terminalTextBuffer.getLine(0).getStyleAt(0) as HyperlinkStyle).linkInfo.navigate()
        (widget.terminalTextBuffer.getLine(1).getStyleAt(2) as HyperlinkStyle).linkInfo.navigate()
        assertEquals(listOf(first, "https://other.test/docs"), opened)
        assertTrue(widget.terminalTextBuffer.getLine(2).getStyleAt(2) !is HyperlinkStyle)
    }

    @Test
    fun `redrawing a continuation updates every row and keeps its background`() {
        val opened = mutableListOf<String>()
        val widget = linkedWidget(64, opened)
        val rows = longUrl.chunked(60).map { "  $it" }
        rows.forEachIndexed { row, text ->
            widget.terminal.cursorPosition(1, row + 1)
            widget.terminal.writeCharacters(text)
        }
        widget.terminal.cursorPosition(1, rows.size)
        widget.terminal.characterAttributes(TextStyle(null, selected))
        widget.terminal.writeCharacters(rows.last().replace("callback", "complete") + " then continue")
        val style = widget.terminalTextBuffer.getLine(rows.lastIndex).getStyleAt(2)
        assertEquals(selected, style!!.background)
        (widget.terminalTextBuffer.getLine(0).getStyleAt(2) as HyperlinkStyle).linkInfo.navigate()
        assertEquals(listOf(longUrl.replace("callback", "complete")), opened)
        assertTrue(widget.terminalTextBuffer.getLine(rows.lastIndex).getStyleAt(rows.last().length + 1) !is HyperlinkStyle)
    }

    private val selected = TerminalColor.rgb(0x26, 0x4f, 0x78)

    /** Styles of row 0's first [n] cells, after writing [runs] (text to its style) with links on. */
    private fun cellsAfterWriting(n: Int, vararg runs: Pair<String, TextStyle>): List<TextStyle> {
        val s = NopTerminalSettings(Color.WHITE, Color.BLACK, Color.BLUE)
        val widget = NopTerminalWidget(80, 24, s)
        widget.addAsyncHyperlinkFilter(
            CellStylePreservingLinks(widget.terminalTextBuffer, UrlHyperlinkFilter(), s.hyperlinkColor, s.hyperlinkHighlightingMode),
        )
        for ((text, style) in runs) {
            widget.terminal.characterAttributes(style)
            widget.terminal.writeCharacters(text)
        }
        val cells = mutableListOf<TextStyle>()
        for (entry in widget.terminalTextBuffer.getLine(0).entries) repeat(entry.text.length) { cells += entry.style }
        return cells.take(n)
    }

    @Test
    fun `a link keeps the background the program painted behind it`() {
        // Claude Code's own drag-selection: an SGR background over the cells, ending mid-URL.
        val sel = TextStyle(TerminalColor.WHITE, selected)
        val cells = cellsAfterWriting(
            22,
            "see " to sel,
            "https://nop" to sel,
            ".test/x and" to TextStyle.EMPTY,
        )
        val url = cells.subList(4, 22)
        assertTrue(url.all { it is HyperlinkStyle }, "every URL cell is a link")
        assertEquals(1, url.map { (it as HyperlinkStyle).linkInfo }.distinct().size, "one link, however many runs")
        assertTrue(url.subList(0, 11).all { it.background == selected }, "selected part stays selected")
        assertTrue(url.subList(11, 18).all { it.background == null }, "unselected part keeps the line's background")
        assertEquals(selected, cells[0].background)
    }

    @Test
    fun `a link keeps the cell's attributes`() {
        val bold = TextStyle.Builder().setOption(TextStyle.Option.BOLD, true).build()
        val cells = cellsAfterWriting(18, "https://nop.test/x" to bold)
        assertTrue(cells.all { it is HyperlinkStyle && it.hasOption(TextStyle.Option.BOLD) })
    }

    @Test
    fun `rewriting a linked line restores the new background`() {
        val s = NopTerminalSettings(Color.WHITE, Color.BLACK, Color.BLUE)
        val widget = NopTerminalWidget(80, 24, s)
        widget.addAsyncHyperlinkFilter(
            CellStylePreservingLinks(widget.terminalTextBuffer, UrlHyperlinkFilter(), s.hyperlinkColor, s.hyperlinkHighlightingMode),
        )
        widget.terminal.writeCharacters("https://nop.test/x")
        // The agent repaints the line selected: cursor home, same text, selection background.
        widget.terminal.cursorPosition(1, 1)
        widget.terminal.characterAttributes(TextStyle(null, selected))
        widget.terminal.writeCharacters("https://nop.test/x")
        val entries = widget.terminalTextBuffer.getLine(0).entries.filter { it.text.length > 0 && !it.text.isNul }
        assertTrue(entries.all { it.style is HyperlinkStyle && it.style.background == selected })
    }
}
