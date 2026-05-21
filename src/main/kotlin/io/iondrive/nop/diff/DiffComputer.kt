package io.iondrive.nop.diff

import com.github.difflib.text.DiffRowGenerator

private const val OLD_OPEN = ""
private const val OLD_CLOSE = ""
private const val NEW_OPEN = ""
private const val NEW_CLOSE = ""

object DiffComputer {
    private val generator: DiffRowGenerator = DiffRowGenerator.create()
        .showInlineDiffs(true)
        .inlineDiffByWord(true)
        .oldTag { isOpen -> if (isOpen) OLD_OPEN else OLD_CLOSE }
        .newTag { isOpen -> if (isOpen) NEW_OPEN else NEW_CLOSE }
        .build()

    fun compute(oldText: String, newText: String): DiffResult {
        val oldLines = oldText.splitLines()
        val newLines = newText.splitLines()

        val rawRows = generator.generateDiffRows(oldLines, newLines)
        val rows = ArrayList<DiffRow>(rawRows.size)

        var oldLineNum = 1
        var newLineNum = 1
        for (raw in rawRows) {
            val kind = when (raw.tag) {
                com.github.difflib.text.DiffRow.Tag.EQUAL -> RowKind.EQUAL
                com.github.difflib.text.DiffRow.Tag.CHANGE -> RowKind.CHANGE
                com.github.difflib.text.DiffRow.Tag.INSERT -> RowKind.INSERT
                com.github.difflib.text.DiffRow.Tag.DELETE -> RowKind.DELETE
            }

            val (oldText, oldSpans) = parseTagged(raw.oldLine, isOldSide = true)
            val (newText, newSpans) = parseTagged(raw.newLine, isOldSide = false)

            val oldHasContent = kind == RowKind.EQUAL || kind == RowKind.CHANGE || kind == RowKind.DELETE
            val newHasContent = kind == RowKind.EQUAL || kind == RowKind.CHANGE || kind == RowKind.INSERT

            rows.add(
                DiffRow(
                    kind = kind,
                    oldLine = if (oldHasContent) oldText else null,
                    newLine = if (newHasContent) newText else null,
                    oldSpans = if (oldHasContent) oldSpans else emptyList(),
                    newSpans = if (newHasContent) newSpans else emptyList(),
                    oldLineNumber = if (oldHasContent) oldLineNum else null,
                    newLineNumber = if (newHasContent) newLineNum else null,
                ),
            )

            if (oldHasContent) oldLineNum++
            if (newHasContent) newLineNum++
        }

        return DiffResult(rows)
    }

    /** Splits text into lines preserving an empty trailing element if the text ends with '\n'. */
    private fun String.splitLines(): List<String> {
        if (isEmpty()) return emptyList()
        return split("\r\n", "\n").let {
            // Drop the final empty element produced when the text ends with a newline
            if (endsWith("\n") && it.lastOrNull() == "") it.dropLast(1) else it
        }
    }

    /**
     * java-diff-utils marks inline changes by wrapping changed substrings with our open/close
     * sentinels. This strips those sentinels and returns the clean text plus span ranges.
     */
    private fun parseTagged(tagged: String?, isOldSide: Boolean): Pair<String, List<InlineSpan>> {
        if (tagged == null) return "" to emptyList()
        val open = if (isOldSide) OLD_OPEN else NEW_OPEN
        val close = if (isOldSide) OLD_CLOSE else NEW_CLOSE

        val out = StringBuilder()
        val spans = mutableListOf<InlineSpan>()
        var i = 0
        var spanStart = -1
        var lastFlushedAt = 0

        while (i < tagged.length) {
            val ch = tagged[i]
            when {
                ch.toString() == open -> {
                    // record unchanged span up to here
                    if (out.length > lastFlushedAt) {
                        spans.add(InlineSpan(lastFlushedAt, out.length, changed = false))
                    }
                    spanStart = out.length
                    i++
                }
                ch.toString() == close -> {
                    // A close sentinel without a matching open (defensive — shouldn't happen with
                    // well-formed java-diff-utils output) used to emit `InlineSpan(-1, …)` which
                    // crashed annotateLine on substring(-1, _) when the row scrolled into view.
                    if (spanStart >= 0) {
                        spans.add(InlineSpan(spanStart, out.length, changed = true))
                        lastFlushedAt = out.length
                        spanStart = -1
                    }
                    i++
                }
                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        if (out.length > lastFlushedAt) {
            spans.add(InlineSpan(lastFlushedAt, out.length, changed = false))
        }
        return out.toString() to spans
    }
}
