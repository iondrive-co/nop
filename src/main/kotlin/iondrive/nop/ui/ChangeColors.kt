package iondrive.nop.ui

import androidx.compose.ui.graphics.Color
import iondrive.nop.git.ChangeKind

object ChangeColors {
    val MODIFIED = Color(0xFF6B9ED8)  // Brighter blue for modified
    val ADDED = Color(0xFF5FAD65)     // Fresh green for added
    val REMOVED = Color(0xFFE05555)   // Cleaner red for removed
    val UNTRACKED = Color(0xFF6F737A) // Muted grey for untracked
    val CONFLICT = Color(0xFFCF8E6D)  // Orange for conflicts (matches keyword color)

    fun forKind(kind: ChangeKind): Color = when (kind) {
        ChangeKind.MODIFIED -> MODIFIED
        ChangeKind.ADDED -> ADDED
        ChangeKind.REMOVED, ChangeKind.MISSING -> REMOVED
        ChangeKind.UNTRACKED -> UNTRACKED
        ChangeKind.CONFLICT -> CONFLICT
    }

    fun prefixFor(kind: ChangeKind): String = when (kind) {
        ChangeKind.MODIFIED -> "M"
        ChangeKind.ADDED -> "A"
        ChangeKind.REMOVED -> "D"
        ChangeKind.UNTRACKED -> "U"
        ChangeKind.MISSING -> "D"
        ChangeKind.CONFLICT -> "C"
    }
}
