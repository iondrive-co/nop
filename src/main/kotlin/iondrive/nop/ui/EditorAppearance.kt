package iondrive.nop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult

internal data class EditorGuide(val start: Int, val endExclusive: Int, val column: Int)

/** Brace scopes supply continuous guides without treating aligned arguments as nested blocks. */
internal fun editorBraceGuides(text: String, tokens: List<Token>, indentColumns: Map<Int, Int>): List<EditorGuide> {
    val excluded = tokens.filter { it.kind == TokenKind.STRING || it.kind == TokenKind.COMMENT }
    val out = ArrayList<EditorGuide>()
    val stack = ArrayList<Pair<Int, Int>>()
    var excludedIndex = 0
    var lineStart = 0
    for (i in text.indices) {
        if (text[i] == '\n') lineStart = i + 1
        while (excludedIndex < excluded.size && excluded[excludedIndex].endExclusive <= i) excludedIndex++
        if (excludedIndex < excluded.size && i >= excluded[excludedIndex].start) continue
        when (text[i]) {
            '{' -> stack += i to (indentColumns[lineStart] ?: 0)
            '}' -> if (stack.isNotEmpty()) {
                val (start, column) = stack.removeAt(stack.lastIndex)
                if (lineStart > start) out += EditorGuide(start, i, column)
            }
        }
    }
    for ((start, column) in stack) out += EditorGuide(start, text.length, column)
    return out
}

internal fun editorBackground(isDark: Boolean): Color =
    if (isDark) Color(0xFF1E1F22) else Color.White

/** Blank lines carry the indentation shared by the lines on either side of them. */
internal fun editorIndentColumns(text: String): Map<Int, Int> {
    val lines = text.split('\n')
    val columns = IntArray(lines.size) { index ->
        val line = lines[index]
        if (line.isBlank()) -1 else {
            var column = 0
            for (char in line) {
                when (char) {
                    ' ' -> column++
                    '\t' -> column += 4 - column % 4
                    else -> break
                }
            }
            column
        }
    }
    var previous = 0
    val following = IntArray(columns.size)
    var next = 0
    for (i in columns.indices.reversed()) {
        if (columns[i] >= 0) next = columns[i]
        following[i] = next
    }
    for (i in columns.indices) {
        if (columns[i] >= 0) previous = columns[i]
        else columns[i] = minOf(previous, following[i])
    }
    var offset = 0
    return lines.indices.associate { index ->
        val start = offset
        offset += lines[index].length + 1
        start to columns[index]
    }
}

/** Draw only visible rows, using the same layout and scroll offset as the field. */
internal fun DrawScope.drawEditorGuides(
    layout: TextLayoutResult,
    indentColumns: Map<Int, Int>,
    braceGuides: List<EditorGuide>?,
    charWidth: Float,
    scroll: Float,
    isDark: Boolean,
) {
    val guideColor = if (isDark) Color(0xFF313338) else Color(0xFFF0F0F0)
    val marginColor = if (isDark) Color(0xFF2B2D30) else Color(0xFFF5F5F5)
    val margin = charWidth * 120 + 0.5f
    if (margin < size.width) drawLine(marginColor, Offset(margin, 0f), Offset(margin, size.height))
    if (braceGuides != null) {
        for (guide in braceGuides) {
            if (guide.start >= layout.layoutInput.text.length || guide.endExclusive > layout.layoutInput.text.length) continue
            val startLine = layout.getLineForOffset(guide.start)
            val endLine = layout.getLineForOffset(guide.endExclusive)
            val top = (layout.getLineBottom(startLine) - scroll).coerceAtLeast(0f)
            val bottom = (layout.getLineTop(endLine) - scroll).coerceAtMost(size.height)
            if (bottom <= top) continue
            val x = charWidth * guide.column + 0.5f
            drawLine(guideColor, Offset(x, top), Offset(x, bottom))
        }
        return
    }
    val first = layout.getLineForVerticalPosition(scroll)
    val last = layout.getLineForVerticalPosition(scroll + size.height)
    for (line in first..last) {
        val start = layout.getLineStart(line)
        // A wrapped continuation has no entry: guides belong to the source line's indentation.
        val indent = indentColumns[start] ?: continue
        val top = (layout.getLineTop(line) - scroll).coerceAtLeast(0f)
        val bottom = (layout.getLineBottom(line) - scroll).coerceAtMost(size.height)
        for (column in 0 until indent step 4) {
            val x = charWidth * column + 0.5f
            drawLine(guideColor, Offset(x, top), Offset(x, bottom))
        }
    }
}
