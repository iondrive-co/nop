package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import java.io.File
import java.nio.file.Path as FilePath

/** A file pin always opens the working file, including when pinned from a diff. */
internal fun pinnableFile(tab: Tab?): File? = when (tab) {
    is Tab.FileView -> tab.file.takeUnless { tab.readOnly }
    else -> jumpToSourceTarget(tab)
}?.absoluteFile?.normalize()

@Composable
internal fun PinIcon(pinned: Boolean, color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val w = size.width
        val h = size.height
        val head = Path().apply {
            moveTo(w * .28f, h * .08f)
            lineTo(w * .72f, h * .08f)
            lineTo(w * .65f, h * .45f)
            lineTo(w * .85f, h * .65f)
            lineTo(w * .15f, h * .65f)
            lineTo(w * .35f, h * .45f)
            close()
        }
        if (pinned) drawPath(head, color) else drawPath(head, color, style = Stroke(1.dp.toPx()))
        drawLine(color, Offset(w * .5f, h * .65f), Offset(w * .5f, h * .95f), 1.dp.toPx(), StrokeCap.Round)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalJewelApi::class)
@Composable
internal fun PinButton(pinned: Boolean, onClick: () -> Unit) {
    val label = if (pinned) "Unpin file" else "Pin file"
    val tint = if (pinned) Color(0xFF3574F0) else if (JewelTheme.isDark) Color(0xFF8C9098) else Color(0xFF6F737A)
    Tooltip(tooltip = { Text(label) }) {
        Box(
            Modifier.padding(start = 4.dp).size(16.dp).clip(RoundedCornerShape(3.dp))
                .semantics { contentDescription = label; selected = pinned }
                .clickable(onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { PinIcon(pinned, tint) }
    }
}

/** The caller bounds the section's height so long pin lists leave room for the project tree. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalJewelApi::class)
@Composable
internal fun PinnedFilesPanel(
    projectPath: FilePath,
    files: List<FilePath>,
    activeFile: File?,
    onOpen: (File) -> Unit,
    onRemove: (FilePath) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return
    val isDark = JewelTheme.isDark
    val muted = if (isDark) Color(0xFF8C9098) else Color(0xFF6F737A)
    val selection = if (isDark) Color(0xFF2E436E) else Color(0xFFD4E2FF)
    Column(modifier.semantics { contentDescription = "Pinned files" }) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (isDark) Color(0xFF4E5157) else Color(0xFFC9CCD6)))
        Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PinIcon(true, muted)
            Spacer(Modifier.width(6.dp))
            Text("Pins", fontSize = 12.sp)
        }
        ScrollableColumn(rememberLazyListState(), Modifier.fillMaxWidth().weight(1f)) {
            items(files, key = { it.toString() }) { path ->
                val label = if (path.startsWith(projectPath)) projectPath.relativize(path).toString() else path.toString()
                Row(
                    Modifier.fillMaxWidth().height(24.dp)
                        .background(if (activeFile?.toPath()?.toAbsolutePath()?.normalize() == path) selection else Color.Transparent)
                        .clickable(onClickLabel = "Open $label") { onOpen(path.toFile()) }
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
                    Tooltip(tooltip = { Text("Remove pin") }) {
                        Box(Modifier.semantics { contentDescription = "Remove pin for $label" }) {
                            CloseButton(isDark) { onRemove(path) }
                        }
                    }
                }
            }
        }
    }
}
