package iondrive.nop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text as JewelText

internal data class MdColors(
    val fg: Color,
    val fgMuted: Color,
    val codeBg: Color,
    val link: Color,
    val rule: Color,
    val quoteBar: Color,
) {
    companion object {
        val Dark = MdColors(
            fg = Color(0xFFA9B7C6),
            fgMuted = Color(0xFF808080),
            codeBg = Color(0x20FFFFFF),
            link = Color(0xFF6897BB),
            rule = Color(0x33FFFFFF),
            quoteBar = Color(0x55A9B7C6),
        )
        val Light = MdColors(
            fg = Color(0xFF1F2329),
            fgMuted = Color(0xFF6C737D),
            codeBg = Color(0x10000000),
            link = Color(0xFF1750EB),
            rule = Color(0x22000000),
            quoteBar = Color(0x55727D8E),
        )
    }
}

private val MARKDOWN_PARSER: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
    .build()

/** Parses [text] as commonmark + GFM and renders it as a stack of Compose nodes. */
@Composable
fun MarkdownPreview(text: String, modifier: Modifier = Modifier) {
    // Re-parse only when the text changes. The AST is cheap to walk so we don't memoize past this.
    val document = remember(text) { MARKDOWN_PARSER.parse(text) }
    val scroll = rememberScrollState()
    val colors = if (JewelTheme.isDark) MdColors.Dark else MdColors.Light
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(JewelTheme.globalColors.panelBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RenderChildren(document, colors)
        }
    }
}

@Composable
private fun RenderChildren(parent: Node, colors: MdColors) {
    var child = parent.firstChild
    while (child != null) {
        RenderBlock(child, colors)
        child = child.next
    }
}

@Composable
private fun RenderBlock(node: Node, colors: MdColors) {
    when (node) {
        is Document -> RenderChildren(node, colors)
        is Heading -> HeadingBlock(node, colors)
        is Paragraph -> JewelText(text = inlineAnnotated(node, colors), fontFamily = FontFamily.Default, fontSize = 14.sp, color = colors.fg)
        is BulletList -> ListBlockView(node, ordered = false, colors)
        is OrderedList -> ListBlockView(node, ordered = true, colors)
        is FencedCodeBlock -> CodeBlockView(node.literal ?: "", colors)
        is IndentedCodeBlock -> CodeBlockView(node.literal ?: "", colors)
        is BlockQuote -> BlockQuoteView(node, colors)
        is ThematicBreak -> ThematicRule(colors)
        is TableBlock -> TableBlockView(node, colors)
        is HtmlBlock -> JewelText(text = node.literal ?: "", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = colors.fgMuted)
        // Unknown / unsupported — render visible inline text so the user can still see it.
        else -> JewelText(text = inlineAnnotated(node, colors), fontFamily = FontFamily.Default, fontSize = 14.sp, color = colors.fg)
    }
}

@Composable
private fun HeadingBlock(node: Heading, colors: MdColors) {
    val size = when (node.level) {
        1 -> 26.sp
        2 -> 22.sp
        3 -> 18.sp
        4 -> 16.sp
        5 -> 15.sp
        else -> 14.sp
    }
    JewelText(
        text = inlineAnnotated(node, colors),
        fontFamily = FontFamily.Default,
        fontSize = size,
        fontWeight = FontWeight.SemiBold,
        color = colors.fg,
    )
}

@Composable
private fun ListBlockView(node: ListBlock, ordered: Boolean, colors: MdColors) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var idx = if (node is OrderedList) node.markerStartNumber ?: 1 else 1
        var item = node.firstChild
        while (item != null) {
            if (item is ListItem) {
                val marker = if (ordered) "${idx}." else "•"
                Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                    JewelText(
                        text = marker,
                        color = colors.fgMuted,
                        fontFamily = FontFamily.Default,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(end = 6.dp).width(if (ordered) 22.dp else 14.dp),
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RenderChildren(item, colors)
                    }
                }
                idx += 1
            }
            item = item.next
        }
    }
}

@Composable
private fun CodeBlockView(content: String, colors: MdColors) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.codeBg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        JewelText(
            text = content.trimEnd('\n'),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = colors.fg,
        )
    }
}

@Composable
private fun BlockQuoteView(node: BlockQuote, colors: MdColors) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .background(colors.quoteBar),
        ) { Spacer(Modifier.height(1.dp)) }
        Column(
            modifier = Modifier.padding(start = 8.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RenderChildren(node, colors)
        }
    }
}

@Composable
private fun ThematicRule(colors: MdColors) {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
}

@Composable
private fun TableBlockView(node: TableBlock, colors: MdColors) {
    Column(modifier = Modifier.fillMaxWidth()) {
        var section = node.firstChild
        while (section != null) {
            when (section) {
                is TableHead -> {
                    var row = section.firstChild
                    while (row != null) {
                        if (row is TableRow) TableRowView(row, header = true, colors)
                        row = row.next
                    }
                }
                is TableBody -> {
                    var row = section.firstChild
                    while (row != null) {
                        if (row is TableRow) TableRowView(row, header = false, colors)
                        row = row.next
                    }
                }
            }
            section = section.next
        }
    }
}

@Composable
private fun TableRowView(row: TableRow, header: Boolean, colors: MdColors) {
    Row(modifier = Modifier.fillMaxWidth()) {
        var cell = row.firstChild
        while (cell != null) {
            if (cell is TableCell) {
                JewelText(
                    text = inlineAnnotated(cell, colors),
                    fontFamily = FontFamily.Default,
                    fontSize = 13.sp,
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    color = colors.fg,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            cell = cell.next
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
}

/** Flatten an inline-bearing node (paragraph, heading, table cell, …) to a single styled string. */
internal fun inlineAnnotated(parent: Node, colors: MdColors = MdColors.Dark): AnnotatedString =
    buildAnnotatedString { appendInline(parent, colors) }

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(parent: Node, colors: MdColors) {
    var child = parent.firstChild
    while (child != null) {
        appendInlineNode(child, colors)
        child = child.next
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlineNode(node: Node, colors: MdColors) {
    when (node) {
        is Text -> append(node.literal ?: "")
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(node, colors) }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(node, colors) }
        is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(node, colors) }
        is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBg)) { append(node.literal ?: "") }
        is Link -> withStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)) { appendInline(node, colors) }
        is Image -> {
            // v1: render alt text in place of the image — TODO file calls this out.
            withStyle(SpanStyle(color = colors.fgMuted, fontStyle = FontStyle.Italic)) {
                append("[image: ")
                appendInline(node, colors)
                append("]")
            }
        }
        is SoftLineBreak, is HardLineBreak -> append("\n")
        is HtmlInline -> withStyle(SpanStyle(color = colors.fgMuted, fontFamily = FontFamily.Monospace)) {
            append(node.literal ?: "")
        }
        else -> appendInline(node, colors)
    }
}
