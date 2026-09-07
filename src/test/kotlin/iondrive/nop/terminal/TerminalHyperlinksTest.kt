package iondrive.nop.terminal

import com.jediterm.terminal.ui.hyperlinks.LinkInfoEx
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.MouseEvent
import javax.swing.JPanel

/**
 * URL detection in launcher output, and the link objects handed to JediTerm. Pure logic — no
 * terminal widget — so it runs headless alongside the rest of the suite.
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
}
