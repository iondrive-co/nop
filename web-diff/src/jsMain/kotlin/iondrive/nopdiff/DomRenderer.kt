package iondrive.nopdiff

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node

/**
 * Renders parsed [DiffFile]s into a DOM container as real HTML elements (no innerHTML, so diff
 * content can never inject markup). Mirrors nop's desktop side-by-side layout: a per-file header,
 * `@@` hunk separators, paired old/new line numbers and code, per-kind row tints, inline word
 * highlights, and per-line syntax colouring driven by the ported tokenizers.
 */
object DomRenderer {

    fun render(container: Element, files: List<DiffFile>) {
        container.clear()
        if (files.isEmpty()) {
            container.appendChild(note("No changes to display."))
            return
        }
        for (file in files) container.appendChild(renderFile(file))
    }

    private fun renderFile(file: DiffFile): HTMLElement {
        val root = el("div", "nd-file")
        root.appendChild(renderHeader(file))

        when {
            file.isBinary -> root.appendChild(note("Binary file not shown."))
            file.hunks.isEmpty() ->
                root.appendChild(note(if (file.isRename) "Renamed with no content changes." else "No line changes."))
            else -> {
                val scroll = el("div", "nd-scroll")
                val table = el("table", "nd-table")
                val tbody = el("tbody")
                val tokenize = tokenizerForExtension(file.extension)
                for (rr in SideBySide.build(file)) {
                    when (rr) {
                        is RenderRow.HunkSeparator -> tbody.appendChild(hunkRow(rr.text))
                        is RenderRow.Line -> tbody.appendChild(lineRow(rr.row, tokenize))
                    }
                }
                table.appendChild(tbody)
                scroll.appendChild(table)
                root.appendChild(scroll)
            }
        }
        return root
    }

    private fun renderHeader(file: DiffFile): HTMLElement {
        val header = el("div", "nd-file-header")
        val path = el("span", "nd-path")
        path.textContent = when {
            file.isRename && file.oldPath != null && file.newPath != null -> "${file.oldPath} → ${file.newPath}"
            else -> file.displayPath
        }
        header.appendChild(path)

        if (file.isNew) header.appendChild(badge("new", "nd-badge-new"))
        if (file.isDeleted) header.appendChild(badge("deleted", "nd-badge-del"))
        if (file.isRename) header.appendChild(badge("renamed", "nd-badge-ren"))
        if (file.isBinary) header.appendChild(badge("binary", "nd-badge-bin"))

        if (!file.isBinary && (file.addedLines > 0 || file.deletedLines > 0)) {
            val stats = el("span", "nd-stats")
            if (file.addedLines > 0) stats.appendChild(stat("+${file.addedLines}", "nd-stat-add"))
            if (file.deletedLines > 0) stats.appendChild(stat("-${file.deletedLines}", "nd-stat-del"))
            header.appendChild(stats)
        }
        return header
    }

    private fun hunkRow(text: String): HTMLElement {
        val tr = el("tr", "nd-hunk")
        val td = el("td")
        td.setAttribute("colspan", "4")
        td.textContent = text
        tr.appendChild(td)
        return tr
    }

    private fun lineRow(row: DiffRow, tokenize: ((String) -> List<Token>)?): HTMLElement {
        val tr = el("tr", "nd-row ${kindClass(row.kind)}")
        tr.appendChild(gutter(row.oldLineNumber, "nd-old"))
        tr.appendChild(codeCell(row.oldLine, row.oldSpans, tokenize, "nd-old"))
        tr.appendChild(gutter(row.newLineNumber, "nd-new"))
        tr.appendChild(codeCell(row.newLine, row.newSpans, tokenize, "nd-new"))
        return tr
    }

    private fun gutter(lineNumber: Int?, sideClass: String): HTMLElement {
        val td = el("td", "nd-gutter $sideClass")
        if (lineNumber != null) td.textContent = lineNumber.toString()
        return td
    }

    private fun codeCell(
        text: String?,
        spans: List<InlineSpan>,
        tokenize: ((String) -> List<Token>)?,
        sideClass: String,
    ): HTMLElement {
        val td = el("td", "nd-code $sideClass")
        if (text != null) renderCode(td, text, spans, tokenize)
        return td
    }

    /**
     * Paints one line: union the syntax-token boundaries with the changed-word boundaries, then emit
     * a run per segment carrying its syntax class and/or the inline-change class. Plain runs become
     * bare text nodes to keep the DOM light.
     */
    private fun renderCode(td: HTMLElement, text: String, spans: List<InlineSpan>, tokenize: ((String) -> List<Token>)?) {
        if (text.isEmpty()) return
        val n = text.length
        val kinds = arrayOfNulls<TokenKind>(n)
        tokenize?.invoke(text)?.forEach { t ->
            var i = t.start.coerceIn(0, n)
            val end = t.endExclusive.coerceIn(i, n)
            while (i < end) { kinds[i] = t.kind; i++ }
        }
        val changed = BooleanArray(n)
        for (s in spans) {
            if (!s.changed) continue
            var i = s.startChar.coerceIn(0, n)
            val end = s.endCharExclusive.coerceIn(i, n)
            while (i < end) { changed[i] = true; i++ }
        }

        var i = 0
        while (i < n) {
            val k = kinds[i]
            val ch = changed[i]
            var j = i + 1
            while (j < n && kinds[j] == k && changed[j] == ch) j++
            val piece = text.substring(i, j)
            if (k == null && !ch) {
                td.appendChild(document.createTextNode(piece))
            } else {
                val span = el("span")
                val cls = StringBuilder()
                if (k != null) cls.append(tokClass(k))
                if (ch) { if (cls.isNotEmpty()) cls.append(' '); cls.append("nd-word") }
                span.className = cls.toString()
                span.textContent = piece
                td.appendChild(span)
            }
            i = j
        }
    }

    private fun kindClass(kind: RowKind): String = when (kind) {
        RowKind.EQUAL -> "nd-equal"
        RowKind.CHANGE -> "nd-change"
        RowKind.INSERT -> "nd-insert"
        RowKind.DELETE -> "nd-delete"
    }

    private fun tokClass(kind: TokenKind): String = when (kind) {
        TokenKind.KEYWORD -> "tok-keyword"
        TokenKind.STRING -> "tok-string"
        TokenKind.COMMENT -> "tok-comment"
        TokenKind.NUMBER -> "tok-number"
        TokenKind.LITERAL -> "tok-literal"
        TokenKind.PUNCT -> "tok-punct"
        TokenKind.HEADING -> "tok-heading"
        TokenKind.EMPHASIS -> "tok-emphasis"
    }

    // ---- small DOM helpers ----

    private fun el(tag: String, cls: String? = null): HTMLElement {
        val e = document.createElement(tag) as HTMLElement
        if (cls != null) e.className = cls
        return e
    }

    private fun badge(label: String, cls: String): HTMLElement =
        el("span", "nd-badge $cls").also { it.textContent = label }

    private fun stat(label: String, cls: String): HTMLElement =
        el("span", cls).also { it.textContent = label }

    private fun note(text: String): HTMLElement =
        el("div", "nd-note").also { it.textContent = text }

    private fun Element.clear() {
        var c: Node? = firstChild
        while (c != null) { removeChild(c); c = firstChild }
    }
}
