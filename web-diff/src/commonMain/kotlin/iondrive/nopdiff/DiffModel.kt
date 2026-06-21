package iondrive.nopdiff

// ---------------------------------------------------------------------------
// Rendering model — ported verbatim from nop's iondrive.nop.diff.DiffModel.
// A DiffRow is one displayed line, paired old (left) / new (right). The web
// renderer consumes these exactly as the desktop Compose view does.
// ---------------------------------------------------------------------------

enum class RowKind {
    EQUAL,    // both sides identical
    CHANGE,   // both sides present but different
    INSERT,   // only on new side
    DELETE,   // only on old side
}

/**
 * Inline span within a line: either UNCHANGED (no highlight) or CHANGED (highlight). Positions are
 * char offsets in the respective line. Spans cover the whole line contiguously and in order.
 */
data class InlineSpan(
    val startChar: Int,
    val endCharExclusive: Int,
    val changed: Boolean,
)

data class DiffRow(
    val kind: RowKind,
    val oldLine: String?,
    val newLine: String?,
    val oldSpans: List<InlineSpan>,
    val newSpans: List<InlineSpan>,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
)

// ---------------------------------------------------------------------------
// Parsed unified-diff model. Mirrors the shape chad's backend already produces
// (FileDiff / DiffHunk / DiffLine), so chad can feed its structured data in
// without re-serialising to text. See StructuredInput for the JS adapter.
// ---------------------------------------------------------------------------

enum class LineType { CONTEXT, ADD, DELETE }

/** One physical line inside a hunk, with its 1-based line numbers on each side (null where absent). */
data class HunkLine(
    val type: LineType,
    val content: String,
    val oldLine: Int?,
    val newLine: Int?,
)

data class Hunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    /** The raw `@@ -a,b +c,d @@ <section>` header line, shown as a separator. */
    val header: String,
    val lines: List<HunkLine>,
)

data class DiffFile(
    val oldPath: String?,
    val newPath: String?,
    val isNew: Boolean = false,
    val isDeleted: Boolean = false,
    val isRename: Boolean = false,
    val isBinary: Boolean = false,
    val hunks: List<Hunk> = emptyList(),
) {
    /** Best display path: the new path normally, the old path for deletions. */
    val displayPath: String
        get() = (newPath?.takeIf { it != "/dev/null" } ?: oldPath ?: "")
            .ifEmpty { oldPath ?: "" }

    val addedLines: Int get() = hunks.sumOf { h -> h.lines.count { it.type == LineType.ADD } }
    val deletedLines: Int get() = hunks.sumOf { h -> h.lines.count { it.type == LineType.DELETE } }

    /** File extension (lowercased, no dot) of the display path, for syntax highlighting. */
    val extension: String?
        get() {
            val name = displayPath.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            return if (dot in 1 until name.length - 1) name.substring(dot + 1).lowercase() else null
        }
}

/** A row in the rendered side-by-side view: either a hunk separator or a paired line. */
sealed interface RenderRow {
    /** The `@@ … @@` separator shown between hunks. */
    data class HunkSeparator(val text: String) : RenderRow

    /** One displayed line (old | new). */
    data class Line(val row: DiffRow) : RenderRow
}
