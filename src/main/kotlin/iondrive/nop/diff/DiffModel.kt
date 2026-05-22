package iondrive.nop.diff

enum class RowKind {
    EQUAL,    // both sides identical
    CHANGE,   // both sides present but different
    INSERT,   // only on new side
    DELETE,   // only on old side
}

/**
 * Inline span within a line: either UNCHANGED (no highlight) or CHANGED (highlight). The same enum
 * is used for both old- and new-side lines; positions refer to char offsets in the respective line.
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

data class DiffResult(
    val rows: List<DiffRow>,
) {
    val hasChanges: Boolean get() = rows.any { it.kind != RowKind.EQUAL }
}
