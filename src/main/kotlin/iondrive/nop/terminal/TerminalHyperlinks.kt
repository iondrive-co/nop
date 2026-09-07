package iondrive.nop.terminal

import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import com.jediterm.terminal.ui.TerminalAction
import com.jediterm.terminal.ui.TerminalActionPresentation
import com.jediterm.terminal.ui.hyperlinks.LinkInfoEx
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
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
