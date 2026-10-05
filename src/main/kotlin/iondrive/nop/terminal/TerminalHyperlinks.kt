package iondrive.nop.terminal

import com.jediterm.terminal.HyperlinkStyle
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.model.CharBuffer
import com.jediterm.terminal.model.TerminalLine
import com.jediterm.terminal.model.TerminalTextBuffer
import com.jediterm.terminal.model.hyperlinks.AsyncHyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import com.jediterm.terminal.ui.TerminalAction
import com.jediterm.terminal.ui.TerminalActionPresentation
import com.jediterm.terminal.ui.hyperlinks.LinkInfoEx
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.lang.reflect.Field
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Turns the http(s) URLs a launcher prints ("Local: http://localhost:5173/", a stack trace's docs
 * link, a CI job URL) into real links: JediTerm underlines them, a click opens the browser, and a
 * right-click offers Open/Copy. Registered per session in [TerminalSession.getOrCreateWidget].
 *
 * [apply] runs on the terminal's writer thread for every line that reaches the screen, so it stays
 * cheap: a substring probe before the regex, and a null result (JediTerm's "no links here") rather
 * than an empty [LinkResult].
 */
class UrlHyperlinkFilter(private val openUrl: (String) -> Unit = ::openUrlInBrowser) : HyperlinkFilter {

    override fun apply(line: String?): LinkResult? {
        val text = line ?: return null
        if (!text.contains("http", ignoreCase = true)) return null
        val items = findUrls(text).map { range ->
            val url = text.substring(range.first, range.last + 1)
            // JediTerm's end offset is exclusive, like Matcher.end().
            LinkResultItem(range.first, range.last + 1, linkInfo(url))
        }
        return if (items.isEmpty()) null else LinkResult(items)
    }

    private fun linkInfo(url: String) = LinkInfoEx.Builder()
        .setNavigateCallback { openUrl(url) }
        // Right-clicking the link itself adds these to the terminal's own menu (JediTerm puts them
        // last): dragging a selection over a URL exactly is the fiddly part of copying one.
        .setPopupMenuGroupProvider {
            listOf(
                TerminalAction(TerminalActionPresentation("Open Link", emptyList())) { openUrl(url); true },
                TerminalAction(TerminalActionPresentation("Copy Link Address", emptyList())) {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
                    true
                },
            )
        }
        .build()

    companion object {
        /**
         * Matches from the scheme up to the first character a URL can't contain. Quotes, angle
         * brackets and backticks stop the match so `curl 'http://host/x'` and `<http://host/x>`
         * link the URL and not the punctuation around it.
         */
        private val URL = Regex("""https?://[^\s<>"'`\\^{}|]+""", RegexOption.IGNORE_CASE)

        /** Sentence punctuation that follows a URL far more often than it ends one. */
        private const val TRAILING = ".,;:!?"

        /**
         * The ranges of [line] that are http(s) URLs, in order.
         *
         * The trailing-character trim is what makes this usable on real output: log lines end
         * "see http://host/docs." and wrap URLs in brackets, while a Wikipedia-style
         * "http://host/Foo_(bar)" genuinely ends in a paren — so closers are dropped only when the
         * match has more of them than openers.
         */
        fun findUrls(line: String): List<IntRange> = URL.findAll(line).mapNotNull { match ->
            val end = trimmedEnd(match.value)
            // A trim that ate the whole host ("http://." -> "http://") leaves no link behind.
            val hostStart = match.value.indexOf("//") + 2
            if (end > hostStart) match.range.first..(match.range.first + end - 1) else null
        }.toList()

        private fun trimmedEnd(url: String): Int {
            var end = url.length
            while (end > 0) {
                val c = url[end - 1]
                val unbalanced = when (c) {
                    ')' -> url.countIn(end, '(') < url.countIn(end, ')')
                    ']' -> url.countIn(end, '[') < url.countIn(end, ']')
                    else -> false
                }
                if (c in TRAILING || unbalanced) end-- else break
            }
            return end
        }

        private fun String.countIn(end: Int, c: Char): Int = (0 until end).count { this[it] == c }
    }
}

/**
 * Puts [links]'s hyperlinks into the buffer without wiping out what the cells they cover looked like.
 *
 * JediTerm's own path (`TextProcessing.applyLinkResults`) rewrites every cell of a link with one
 * fixed style — [linkColor]'s foreground, *its* background, no attributes — so whatever the program
 * had painted there is gone. That is why a URL never showed as selected in an agent tab: Claude Code
 * draws its own drag-selection as an SGR background on the cells (the `#264f78` band), and the link
 * pass, which runs on every write to a line, put the URL's cells straight back to the plain link
 * style. Code-block and diff backgrounds behind a URL went the same way, as did bold.
 *
 * So this writes the links itself, run by run of the cells' existing styles: link foreground, but
 * the cell's own background and attributes. A cell already holding a link (the line was written
 * again) keeps the background it carries, so re-applying is idempotent. Every run of one URL shares
 * one `LinkInfo`, which is what JediTerm's hover and click go by.
 *
 * It needs the lines behind the text, which the public [AsyncHyperlinkFilter.LineInfo] does not
 * expose; [linesOf] reads them off JediTerm's `LineInfoImpl`. Should a JediTerm upgrade move that
 * field, links fall back to JediTerm's own writing — still links, just flat again — and
 * `TerminalHyperlinksTest` fails.
 */
internal class CellStylePreservingLinks(
    private val buffer: TerminalTextBuffer,
    private val links: HyperlinkFilter,
    private val linkColor: TextStyle,
    private val mode: HyperlinkStyle.HighlightMode,
) : AsyncHyperlinkFilter {

    override fun apply(lineInfo: AsyncHyperlinkFilter.LineInfo): CompletableFuture<LinkResult?> {
        val text = lineInfo.line ?: return CompletableFuture.completedFuture(null)
        val result = links.apply(text) ?: return CompletableFuture.completedFuture(null)
        val lines = linesOf(lineInfo) ?: return CompletableFuture.completedFuture(result)
        buffer.lock()
        try {
            val width = buffer.width
            // Same staleness check JediTerm makes before it writes: the lines must still spell [text].
            if (joined(lines, width) != text) return CompletableFuture.completedFuture(null)
            for (item in result.items) {
                lines.forEachIndexed { i, line ->
                    val from = maxOf(item.startOffset, i * width) - i * width
                    val to = minOf(item.endOffset, (i + 1) * width) - i * width
                    if (from < to) writeLink(line, from, to, item.linkInfo)
                }
            }
        } finally {
            buffer.unlock()
        }
        // Null: the links are in place, and a result would have JediTerm flatten them again.
        return CompletableFuture.completedFuture(null)
    }

    private fun writeLink(line: TerminalLine, from: Int, to: Int, info: LinkInfo) {
        val chars = line.text
        val styles = arrayOfNulls<TextStyle>(to)
        var x = 0
        for (entry in line.entries) {
            val len = entry.text.length
            for (c in x until minOf(x + len, to)) styles[c] = entry.style
            x += len
            if (x >= to) break
        }
        var start = from
        while (start < to) {
            val cell = styles[start] ?: TextStyle.EMPTY
            var end = start + 1
            while (end < to && styles[end] == cell) end++
            line.writeString(start, CharBuffer(chars.substring(start, end)), linkStyle(cell, info))
            start = end
        }
    }

    private fun linkStyle(cell: TextStyle, info: LinkInfo): HyperlinkStyle {
        // Reverse video swaps the pair, so a link foreground would become the cell's fill: keep both.
        val inverse = cell.hasOption(TextStyle.Option.INVERSE)
        val builder = HyperlinkStyle(
            if (inverse) cell.foreground else linkColor.foreground,
            cell.background ?: linkColor.background,
            info,
            mode,
        ).toBuilder()
        for (option in TextStyle.Option.entries) if (cell.hasOption(option)) builder.setOption(option, true)
        return builder.build() as HyperlinkStyle
    }

    private fun joined(lines: List<TerminalLine>, width: Int): String = buildString {
        lines.forEachIndexed { i, line ->
            val t = line.text
            append(t)
            if (i < lines.size - 1 && t.length < width) append(" ".repeat(width - t.length))
        }
    }

    companion object {
        private val linesField: Field? = runCatching {
            Class.forName("com.jediterm.terminal.model.hyperlinks.TextProcessing\$LineInfoImpl")
                .getDeclaredField("myLinesToProcess")
                .apply { isAccessible = true }
        }.getOrNull()

        /** The buffer lines JediTerm joined into [lineInfo]'s text, or null if it can't be read. */
        @Suppress("UNCHECKED_CAST")
        internal fun linesOf(lineInfo: AsyncHyperlinkFilter.LineInfo): List<TerminalLine>? {
            val field = linesField ?: return null
            if (!field.declaringClass.isInstance(lineInfo)) return null
            return runCatching { field.get(lineInfo) as List<TerminalLine> }.getOrNull()
        }
    }
}

/**
 * Hands a URL to the desktop's browser, on a daemon thread so a slow launch never stalls the EDT.
 *
 * `xdg-open` first on Linux: it honours the user's `x-scheme-handler/http` default (and `$BROWSER`),
 * whereas [Desktop.browse] goes through libgio, which a jlinked runtime can't be relied on to have.
 * We wait only long enough to see a failure — a handler that stays alive (a browser starting from
 * cold) is a success, not something to wait for.
 */
fun openUrlInBrowser(url: String) {
    Thread {
        if (isLinux && launchedViaXdgOpen(url)) return@Thread
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }.apply { name = "nop-open-url"; isDaemon = true }.start()
}

private val isLinux: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

private fun launchedViaXdgOpen(url: String): Boolean = runCatching {
    val p = ProcessBuilder("xdg-open", url).redirectErrorStream(true).start()
    !p.waitFor(3, TimeUnit.SECONDS) || p.exitValue() == 0
}.getOrDefault(false)
