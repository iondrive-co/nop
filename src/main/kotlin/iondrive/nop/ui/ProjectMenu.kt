package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import iondrive.nop.ProjectTabs
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.nio.file.Files
import java.nio.file.Path

/**
 * The word "Project" over the file tree, which is also the way to a different one: clicking it drops
 * the list of recent projects, and the way to browse for one that isn't on it.
 *
 * It lives here rather than on the project bar because this is where the eye already is when the
 * question is "which project am I in" — the bar is a row of the ones already open, and the "+" at the
 * end of it makes another tab on the one in front. A label that opens a menu has to say so, so it
 * carries a chevron and lifts under the pointer the way the buttons beside it do.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ProjectMenuLabel(
    recentProjects: List<Path>,
    openProjects: List<Path>,
    onOpenProject: (Path) -> Unit,
    onOpenOther: () -> Unit,
) {
    val isDark = JewelTheme.isDark
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var expanded by remember { mutableStateOf(false) }
    // How far down the popup has to start to clear the label it hangs from, measured rather than
    // assumed: the header's height follows the theme's text metrics.
    var labelHeight by remember { mutableStateOf(0) }

    val tint = if (isDark) ProjectIconTintDark else ProjectIconTintLight
    val hoverBg = if (isDark) Color(0xFF393B40) else Color(0xFFD8DBE0)

    Box {
        Row(
            modifier = Modifier
                .onSizeChanged { labelHeight = it.height }
                .clip(RoundedCornerShape(4.dp))
                .background(if (hovered || expanded) hoverBg else Color.Transparent)
                .hoverable(interaction)
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("Project")
            Canvas(Modifier.size(9.dp)) { drawDisclosure(tint, collapsed = false) }
        }
        if (expanded) {
            ProjectMenuPopup(
                recentProjects = recentProjects,
                openProjects = openProjects,
                offsetY = labelHeight,
                onDismiss = { expanded = false },
                onOpenProject = { expanded = false; onOpenProject(it) },
                onOpenOther = { expanded = false; onOpenOther() },
            )
        }
    }
}

/**
 * The menu itself: recent projects this window hasn't already got a tab for (newest first, stale
 * directories filtered out), and the way to one that isn't listed. Picking a project opens it as a
 * tab of this window.
 */
@Composable
private fun ProjectMenuPopup(
    recentProjects: List<Path>,
    openProjects: List<Path>,
    offsetY: Int,
    onDismiss: () -> Unit,
    onOpenProject: (Path) -> Unit,
    onOpenOther: () -> Unit,
) {
    val visible = remember(recentProjects, openProjects) {
        ProjectTabs.recentMenu(recentProjects, openProjects).filter { Files.isDirectory(it) }
    }

    Popup(
        onDismissRequest = onDismiss,
        offset = IntOffset(0, offsetY),
        properties = PopupProperties(focusable = true),
    ) {
        val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
        Column(
            modifier = Modifier
                .width(320.dp)
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, border, RoundedCornerShape(6.dp))
                .padding(vertical = 4.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (visible.isEmpty()) {
                MenuPassiveText("No other recent projects")
            } else {
                for (p in visible) {
                    MenuRow(
                        title = p.fileName?.toString() ?: p.toString(),
                        subtitle = p.toString(),
                        onClick = { onOpenProject(p) },
                    )
                }
            }
            MenuSeparator()
            MenuRow(title = "Open…", subtitle = null, onClick = onOpenOther)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MenuRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(title)
        if (subtitle != null) {
            val muted = if (JewelTheme.isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
            Text(subtitle, color = muted)
        }
    }
}

@Composable
private fun MenuPassiveText(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val muted = if (JewelTheme.isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
        Text(text, color = muted)
    }
}

@Composable
private fun MenuSeparator() {
    val color = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFE3E5EA)
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(color))
}
