package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.ShortPath
import iondrive.nop.git.ChangeKind
import iondrive.nop.git.CommitFileChange
import iondrive.nop.git.GitRepo
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import java.io.File

/**
 * The file a viewer tab is showing, named in a small box at the top centre of it.
 *
 * The open-tab list names a file by its last segment only, and a project holds many `build.gradle.kts`
 * and `index.ts`; the box gives its path in the repository. [isNew] adds "(new)" when the change on
 * screen is the one that creates the file, so an empty left side reads as "did not exist before" rather
 * than "was emptied".
 *
 * The box lies over the top of the content instead of taking a row of its own, so the viewer gives up
 * no height for it, and its arrow folds it to a small tab for whoever wants the line under it back.
 * That choice is [collapsed], one setting for every viewer.
 */
internal data class FileNameBadge(
    val path: String,
    val isNew: Boolean,
    val collapsed: Boolean,
    val onToggle: () -> Unit,
)

/**
 * The badge for the tab on screen, provided by [TabbedViewerPanel] and drawn by whichever view is under
 * it at the top of its content — below any find or problem bar, so it never covers a field. Null
 * everywhere outside the viewer, which is how the diffs in the tool panel go without one.
 */
internal val LocalFileNameBadge = compositionLocalOf<FileNameBadge?> { null }

/**
 * The path shown for [tab] and whether its change creates the file, or null for a tab that is not a
 * view of one file's content. A file outside the repository is named by its absolute path, `~`-relative.
 *
 * A commit diff answers from the tab's change type here; [CommitDiffView] also checks the parent tree,
 * because a diff opened from a blame line is always recorded as a modification.
 */
internal fun fileNameOf(tab: Tab, repo: GitRepo?): Pair<String, Boolean>? {
    fun pathOf(file: File): String =
        repo?.let { repoRelativePath(it, file) } ?: ShortPath.of(file.toPath(), max = Int.MAX_VALUE)
    return when (tab) {
        is Tab.FileView -> pathOf(tab.file) to false
        is Tab.Diff -> tab.change.path to (tab.change.kind == ChangeKind.ADDED || tab.change.kind == ChangeKind.UNTRACKED)
        is Tab.CommitDiff -> tab.file.path to (tab.file.changeType == CommitFileChange.ADDED)
        is Tab.RevisionDiff -> pathOf(tab.file) to false
        is Tab.LocalDiff -> pathOf(tab.file) to false
        is Tab.LocalHistory -> null
    }
}

/**
 * Draws [LocalFileNameBadge] at the top centre of this box, if there is one. The box should be the
 * full width of the view, starting where its content starts.
 */
@OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun BoxScope.FileNameBadgeOverlay() {
    val badge = LocalFileNameBadge.current ?: return
    val isDark = JewelTheme.isDark
    val bg = if (isDark) Color(0xFF2B2D30) else Color(0xFFFFFFFF)
    val border = if (isDark) Color(0xFF4E5157) else Color(0xFFC9CCD6)
    val muted = if (isDark) Color(0xFF8C9098) else Color(0xFF6F737A)
    val strong = if (isDark) Color(0xFFDFE1E5) else Color(0xFF1E1F22)
    val style = TextStyle(fontFamily = NopFonts.Mono, fontSize = 11.sp)

    if (badge.collapsed) {
        // Hangs from the top edge, so folded it covers almost nothing of the line beneath.
        val shape = RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
        Box(Modifier.align(Alignment.TopCenter)) {
            Tooltip(tooltip = { Text("Show the file name") }) {
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 11.dp)
                        .clip(shape)
                        .background(bg)
                        .border(1.dp, border, shape)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = badge.onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Chevron(up = false, color = muted)
                }
            }
        }
        return
    }

    val shape = RoundedCornerShape(4.dp)
    val dir = badge.path.substringBeforeLast('/', "")
    val name = badge.path.substringAfterLast('/')
    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            // Clear of the view's edges, so a long path never reaches the scrollbar or the left gutter.
            .padding(top = 4.dp, start = 48.dp, end = 48.dp)
            .height(20.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            // Takes presses itself, so a click on the box doesn't land in the text it covers.
            .pointerInput(Unit) {}
            .padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dir.isNotEmpty()) {
            // Cut from the left when space runs out: the directories nearest the file say most.
            BasicText(
                "$dir/",
                style = style.copy(color = muted),
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        BasicText(name, style = style.copy(color = strong, fontWeight = FontWeight.Medium), maxLines = 1)
        if (badge.isNew) {
            BasicText(
                " (new)",
                style = style.copy(color = ChangeColors.ADDED, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
        Tooltip(tooltip = { Text("Hide the file name") }) {
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(16.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClick = badge.onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Chevron(up = true, color = muted)
            }
        }
    }
}

/** The fold arrow, drawn rather than set in type: the editor's monospace font has no glyph for it. */
@Composable
private fun Chevron(up: Boolean, color: Color) {
    Canvas(Modifier.size(width = 8.dp, height = 4.dp)) {
        val (w, h) = size.width to size.height
        val path = Path().apply {
            if (up) {
                moveTo(0f, h); lineTo(w / 2, 0f); lineTo(w, h)
            } else {
                moveTo(0f, 0f); lineTo(w / 2, h); lineTo(w, 0f)
            }
        }
        drawPath(path, color, style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
