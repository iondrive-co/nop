package iondrive.nopdiff

/**
 * Turns a parsed [DiffFile] into the side-by-side render rows the desktop view produces. Within a
 * hunk, a run of deletions immediately followed by additions is paired positionally into CHANGE
 * rows (with inline word highlights from [WordDiff]); unpaired remainders become pure DELETE /
 * INSERT rows. Context lines are EQUAL rows. Each hunk is preceded by its `@@` separator.
 */
object SideBySide {

    fun build(file: DiffFile): List<RenderRow> {
        val out = ArrayList<RenderRow>()
        for (hunk in file.hunks) {
            out.add(RenderRow.HunkSeparator(hunk.header))

            val pendingDel = ArrayList<HunkLine>()
            val pendingAdd = ArrayList<HunkLine>()

            fun flushBlock() {
                val n = maxOf(pendingDel.size, pendingAdd.size)
                for (k in 0 until n) {
                    val d = pendingDel.getOrNull(k)
                    val a = pendingAdd.getOrNull(k)
                    when {
                        d != null && a != null -> {
                            val (oldSpans, newSpans) = WordDiff.spans(d.content, a.content)
                            out.add(
                                RenderRow.Line(
                                    DiffRow(RowKind.CHANGE, d.content, a.content, oldSpans, newSpans, d.oldLine, a.newLine),
                                ),
                            )
                        }
                        d != null -> out.add(
                            RenderRow.Line(DiffRow(RowKind.DELETE, d.content, null, emptyList(), emptyList(), d.oldLine, null)),
                        )
                        a != null -> out.add(
                            RenderRow.Line(DiffRow(RowKind.INSERT, null, a.content, emptyList(), emptyList(), null, a.newLine)),
                        )
                    }
                }
                pendingDel.clear()
                pendingAdd.clear()
            }

            for (hl in hunk.lines) {
                when (hl.type) {
                    LineType.CONTEXT -> {
                        flushBlock()
                        out.add(
                            RenderRow.Line(
                                DiffRow(RowKind.EQUAL, hl.content, hl.content, emptyList(), emptyList(), hl.oldLine, hl.newLine),
                            ),
                        )
                    }
                    LineType.DELETE -> pendingDel.add(hl)
                    LineType.ADD -> pendingAdd.add(hl)
                }
            }
            flushBlock()
        }
        return out
    }
}
