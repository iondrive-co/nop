package iondrive.nop.ui

import androidx.compose.ui.graphics.Color
import iondrive.nop.git.ChangeKind

object ChangeColors {
    val MODIFIED = Color(0xFF6897BB)
    val ADDED = Color(0xFF629755)
    val REMOVED = Color(0xFFB35E5E)
    val UNTRACKED = Color(0xFF808080)
    val CONFLICT = Color(0xFFCC7832)

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
        ChangeKind.UNTRACKED -> "?"
        ChangeKind.MISSING -> "!"
        ChangeKind.CONFLICT -> "C"
    }
}
