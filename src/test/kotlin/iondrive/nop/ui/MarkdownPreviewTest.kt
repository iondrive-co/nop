package iondrive.nop.ui

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BulletList
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownPreviewTest {

    private val parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .build()

    private fun firstOf(node: Node, predicate: (Node) -> Boolean): Node? {
        var child = node.firstChild
        while (child != null) {
            if (predicate(child)) return child
            val nested = firstOf(child, predicate)
            if (nested != null) return nested
            child = child.next
        }
        return null
    }

    @Test
    fun `headings parse with correct level`() {
        val doc = parser.parse("# H1\n## H2\n### H3\n")
        val h1 = firstOf(doc) { it is Heading && (it as Heading).level == 1 } as? Heading
        val h2 = firstOf(doc) { it is Heading && (it as Heading).level == 2 } as? Heading
        val h3 = firstOf(doc) { it is Heading && (it as Heading).level == 3 } as? Heading
        assertNotNull(h1); assertNotNull(h2); assertNotNull(h3)
    }

    @Test
    fun `inline annotated flattens bold and italic text`() {
        val doc = parser.parse("This is **bold** and *italic*.")
        val paragraph = firstOf(doc) { it is Paragraph }!!
        val annotated = inlineAnnotated(paragraph)
        assertEquals("This is bold and italic.", annotated.text)
        // bold and italic spans should both appear
        assertTrue(annotated.spanStyles.isNotEmpty(), "expected at least one span style")
    }

    @Test
    fun `fenced code block is detected`() {
        val doc = parser.parse(
            """
            text
            ```kotlin
            val x = 1
            ```
            """.trimIndent(),
        )
        val fence = firstOf(doc) { it is FencedCodeBlock } as? FencedCodeBlock
        assertNotNull(fence)
        assertTrue(fence!!.literal.contains("val x = 1"))
    }

    @Test
    fun `bullet list contains items`() {
        val doc = parser.parse("- one\n- two\n- three\n")
        val list = firstOf(doc) { it is BulletList } as? BulletList
        assertNotNull(list)
        var count = 0
        var item = list!!.firstChild
        while (item != null) { count += 1; item = item.next }
        assertEquals(3, count)
    }

    @Test
    fun `GFM table extension parses table block`() {
        val doc = parser.parse(
            """
            | A | B |
            |---|---|
            | 1 | 2 |
            """.trimIndent(),
        )
        val table = firstOf(doc) { it is TableBlock }
        assertNotNull(table, "tables extension should produce a TableBlock node")
    }

    @Test
    fun `GFM strikethrough is parsed as a node`() {
        val doc = parser.parse("hello ~~world~~")
        val strike = firstOf(doc) { it::class.simpleName == "Strikethrough" }
        assertNotNull(strike, "strikethrough extension should produce a Strikethrough node")
    }

    @Test
    fun `link inline becomes annotated string with the link text only`() {
        val doc = parser.parse("see [docs](https://example.com) please")
        val paragraph = firstOf(doc) { it is Paragraph }!!
        val annotated = inlineAnnotated(paragraph)
        assertEquals("see docs please", annotated.text)
    }

    @Test
    fun `image renders as bracketed alt text in v1`() {
        val doc = parser.parse("![alt](x.png)")
        val paragraph = firstOf(doc) { it is Paragraph }!!
        val annotated = inlineAnnotated(paragraph)
        assertTrue(annotated.text.contains("[image: alt]"), "got: ${annotated.text}")
    }
}
