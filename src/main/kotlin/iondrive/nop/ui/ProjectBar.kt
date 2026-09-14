package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import iondrive.nop.ProjectTabs
import iondrive.nop.StripDrag
import iondrive.nop.Workspace
import iondrive.nop.Workspaces
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import java.nio.file.Files
import java.nio.file.Path

// One row of the bar, sized to match the editor tab strip below it so the two read as stacked
// chrome rather than two unrelated bands.
private val BAR_HEIGHT = 30.dp
private val ADD_TAB_WIDTH = 30.dp
// Caps how wide a project tab may grow, so one long name can't push the rest off the bar.
private val TAB_MAX_WIDTH = 220.dp

/**
 * The horizontal bar of project tabs along the top of the window, under the title. The leading "+"
 * drops a list of recently used projects (plus "Open…" to browse for a new one, and the window
 * commands), and every other tab names a project this window has open. Clicking a tab switches the
 * workspace to that project; the little "x" removes it from the bar.
 *
 * The bar holds one window's projects and nothing else. Grouping a long list of projects is what
 * separate windows are for now — a project tab's context menu moves it to another window — so the
 * bar itself is a flat strip of tabs that can be dragged into whatever order suits.
 *
 * Tabs flow onto further rows rather than running off the end, for the same reason the editor tab
 * strip does: a tab you can't see is a tab you've lost. The bar grows downward by [BAR_HEIGHT] a row
 * and the workspace beneath it gives up the space.
 */
@OptIn(
    ExperimentalJewelApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun ProjectBar(
    projects: List<Path>,
    activeProject: Path?,
    dirtyProjects: Set<Path>,
    recentProjects: List<Path>,
    windows: List<Workspace>,
    windowId: Long,
    onSelect: (Path) -> Unit,
    onClose: (Path) -> Unit,
    onOpenRecent: (Path) -> Unit,
    onOpenOther: () -> Unit,
    onReorder: (List<Path>) -> Unit,
    onNewWindow: (String) -> Unit,
    onRenameWindow: (String) -> Unit,
    onRenameOtherWindow: (Long, String) -> Unit,
    onShowWindow: (Long) -> Unit,
    onDiscardWindow: (Long) -> Unit,
    onMoveToWindow: (Path, Long) -> Unit,
    onMoveToNewWindow: (Path, String) -> Unit,
    isDark: Boolean,
) {
    // The bar sits a shade darker than the workspace below it, so the two read as separate surfaces
    // without needing a heavy border between them.
    val barBg = if (isDark) Color(0xFF1E1F22) else Color(0xFFF7F8FA)
    // Subtle border between the "+" and the tabs it opens.
    val divider = if (isDark) Color(0xFF323438) else Color(0xFFE0E1E3)
    val iconTint = if (isDark) ProjectIconTintDark else ProjectIconTintLight
    val thisWindow = remember(windows, windowId) { windows.firstOrNull { it.id == windowId } }
    val otherWindows = remember(windows, windowId) { windows.filter { it.id != windowId } }
    val parked = remember(windows) { Workspaces.parked(windows) }
    // A project already open in *some* window is left out of the recent list, here as much as in the
    // window that holds it: a project lives in one window, and the way to bring one here is to move
    // its tab over from that window rather than to open a second copy of it.
    val openAnywhere = remember(windows) { Workspaces.allProjects(windows) }

    // Drag-reorder state, shared across all tabs so the dragged tab tracks the pointer while the
    // others reflow. A pending name prompt (new window, rename, move-to-new) is a dialog below.
    val reorder = remember { BarReorder() }
    var dialog by remember { mutableStateOf<BarDialog?>(null) }
    var picking by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BAR_HEIGHT)
            .background(barBg),
        verticalAlignment = Alignment.Top,
    ) {
        AddProjectTab(
            openProjects = openAnywhere,
            recentProjects = recentProjects,
            iconTint = iconTint,
            onOpenRecent = onOpenRecent,
            onOpenOther = onOpenOther,
            onNewWindow = { dialog = BarDialog.NewWindow },
            onRenameWindow = { dialog = BarDialog.RenameWindow(thisWindow?.name.orEmpty()) },
        )
        // The way back to a window that was closed. A button of its own rather than a line in the
        // "+" menu: a parked window is invisible until something says it is there, and a menu behind
        // an icon that means "open a project" is not something anyone thinks to look inside.
        WindowsTab(parked = parked.size, iconTint = iconTint, onClick = { picking = true })
        Box(modifier = Modifier.width(1.dp).height(BAR_HEIGHT).background(divider))
        FlowRow(modifier = Modifier.weight(1f)) {
            for (project in projects) {
                // Key by the project path (not slot position) so a running drag's pointerInput
                // survives the list reordering under it.
                key(project) {
                    ReorderableTab(
                        project = project,
                        projects = projects,
                        reorder = reorder,
                        onReorder = onReorder,
                    ) {
                        ProjectTab(
                            project = project,
                            active = project == activeProject,
                            dirty = project in dirtyProjects,
                            windows = otherWindows,
                            isDark = isDark,
                            onClick = { onSelect(project) },
                            onClose = { onClose(project) },
                            onMoveToWindow = { id -> onMoveToWindow(project, id) },
                            onMoveToNewWindow = { dialog = BarDialog.MoveToNewWindow(project) },
                        )
                    }
                }
            }
        }
    }

    if (picking) {
        WindowPickerPopup(
            parked = parked,
            onOpenWindow = onShowWindow,
            onRenameWindow = { id ->
                dialog = BarDialog.RenameOtherWindow(id, windows.firstOrNull { it.id == id }?.name.orEmpty())
            },
            onDiscardWindow = onDiscardWindow,
            // Straight to a new window, no name prompt: the picker's button is the plain "another
            // window" gesture, and the "+" menu's "New window…" is the one that asks for a name —
            // which is what the ellipsis on it says. An unnamed window is titled by its project
            // until the user renames it.
            onNewWindow = { onNewWindow("") },
            onOpenProject = onOpenOther,
            onDismiss = { picking = false },
        )
    }

    when (val d = dialog) {
        is BarDialog.NewWindow -> NewEntryDialog(
            title = "New window",
            description = "Opens another nop window, named. Move projects into it from a tab's right-click menu.",
            confirmLabel = "Open",
            onSubmit = { name -> onNewWindow(name); dialog = null; null },
            onCancel = { dialog = null },
        )
        is BarDialog.RenameWindow -> NewEntryDialog(
            title = "Rename window",
            description = "The name this window goes by in its title bar and in the window picker.",
            initialText = d.current,
            confirmLabel = "Rename",
            onSubmit = { name -> onRenameWindow(name); dialog = null; null },
            onCancel = { dialog = null },
        )
        is BarDialog.RenameOtherWindow -> NewEntryDialog(
            title = "Rename window",
            description = "The name this closed window goes by in the picker, and will go by when it opens.",
            initialText = d.current,
            confirmLabel = "Rename",
            onSubmit = { name -> onRenameOtherWindow(d.id, name); dialog = null; null },
            onCancel = { dialog = null },
        )
        is BarDialog.MoveToNewWindow -> NewEntryDialog(
            title = "Move to new window",
            description = "Opens a new named window and moves this project tab into it.",
            confirmLabel = "Move",
            onSubmit = { name -> onMoveToNewWindow(d.project, name); dialog = null; null },
            onCancel = { dialog = null },
        )
        null -> {}
    }
}

/** A pending name prompt raised from the bar. */
private sealed interface BarDialog {
    data object NewWindow : BarDialog
    data class RenameWindow(val current: String) : BarDialog
    data class RenameOtherWindow(val id: Long, val current: String) : BarDialog
    data class MoveToNewWindow(val project: Path) : BarDialog
}

/** Shared drag-reorder state for the bar. One tab drags at a time; the rest reflow around it. */
private class BarReorder {
    var draggingKey by mutableStateOf<Path?>(null)
    // Horizontal offset of the dragged tab from its settled slot, in px. Reset to 0 each time the
    // tab crosses a neighbour and we commit a move, so it always measures from the current slot.
    var delta by mutableStateOf(0f)
    // Measured widths per tab, so a drag knows how far to travel before swapping a neighbour.
    val widths = mutableStateMapOf<Path, Int>()

    /** Drops the drag: the tab snaps back into its slot. */
    fun settle() {
        draggingKey = null
        delta = 0f
    }
}

/**
 * Wraps one project tab with drag-to-reorder. While dragging, the tab follows the pointer
 * (translation + raised above its neighbours); each time it travels past half a neighbour's width we
 * swap it with that neighbour so the bar reflows live — see [StripDrag.step], which owns that
 * decision. The drag only engages once the pointer passes the touch slop, so a plain click still
 * selects the project.
 */
@Composable
private fun ReorderableTab(
    project: Path,
    projects: List<Path>,
    reorder: BarReorder,
    onReorder: (List<Path>) -> Unit,
    content: @Composable () -> Unit,
) {
    // rememberUpdatedState so the long-lived drag coroutine always sees the current order/callback
    // even though pointerInput(project) is not restarted on a reorder.
    val projectsUpdated by rememberUpdatedState(projects)
    val onReorderUpdated by rememberUpdatedState(onReorder)
    val dragging = reorder.draggingKey == project

    Box(
        // A fixed height rather than fillMaxHeight: the bar is as many rows tall as the tabs need,
        // and a tab should be one row of it, not all of them.
        modifier = Modifier
            .height(BAR_HEIGHT)
            .onSizeChanged { reorder.widths[project] = it.width }
            // zIndex/graphicsLayer are always present (not conditionally inserted) so the modifier
            // chain — and the pointerInput node below it — isn't rebuilt when a drag starts/ends.
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationX = if (dragging) reorder.delta else 0f }
            .pointerInput(project) {
                detectDragGestures(
                    onDragStart = {
                        reorder.draggingKey = project
                        reorder.delta = 0f
                    },
                    onDragEnd = { reorder.settle() },
                    onDragCancel = { reorder.settle() },
                    onDrag = { change, amount ->
                        change.consume()
                        reorder.delta += amount.x
                        val cur = projectsUpdated
                        val from = cur.indexOf(project)
                        val sizes = cur.map { reorder.widths[it] ?: 0 }
                        val step = StripDrag.step(sizes, from, reorder.delta)
                        if (step != null) {
                            onReorderUpdated(Workspaces.move(cur, from, step.to))
                            reorder.delta -= step.travelled
                        }
                    },
                )
            },
    ) {
        content()
    }
}

@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AddProjectTab(
    openProjects: List<Path>,
    recentProjects: List<Path>,
    iconTint: Color,
    onOpenRecent: (Path) -> Unit,
    onOpenOther: () -> Unit,
    onNewWindow: () -> Unit,
    onRenameWindow: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Tooltip(tooltip = { Text("Open a project, or another window") }) {
            Box(
                modifier = Modifier
                    .width(ADD_TAB_WIDTH)
                    .height(BAR_HEIGHT)
                    .clickable { expanded = !expanded },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(14.dp)) { drawPlusIcon(iconTint) }
            }
        }
        if (expanded) {
            AddProjectPopup(
                openProjects = openProjects,
                recentProjects = recentProjects,
                onDismiss = { expanded = false },
                onOpenRecent = { expanded = false; onOpenRecent(it) },
                onOpenOther = { expanded = false; onOpenOther() },
                onNewWindow = { expanded = false; onNewWindow() },
                onRenameWindow = { expanded = false; onRenameWindow() },
            )
        }
    }
}

/**
 * The bar's windows button: opens the picker, and says how many windows are parked behind it so a
 * closed window announces itself rather than waiting to be looked for.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WindowsTab(parked: Int, iconTint: Color, onClick: () -> Unit) {
    val tip = when (parked) {
        0 -> "Windows"
        1 -> "Windows — 1 closed and waiting"
        else -> "Windows — $parked closed and waiting"
    }
    Tooltip(tooltip = { Text(tip) }) {
        Row(
            modifier = Modifier
                .height(BAR_HEIGHT)
                .clickable(onClick = onClick)
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Canvas(Modifier.size(13.dp)) { drawWindowsIcon(iconTint) }
            if (parked > 0) {
                Text(parked.toString(), color = iconTint, fontSize = 10.sp)
            }
        }
    }
}

/**
 * Popup hanging below the "+" tab: recent projects that aren't open in any window (newest first,
 * stale directories filtered out), and the commands to open one that isn't listed. Which windows
 * exist belongs to the picker next door, not to a section down here.
 */
@Composable
private fun AddProjectPopup(
    openProjects: List<Path>,
    recentProjects: List<Path>,
    onDismiss: () -> Unit,
    onOpenRecent: (Path) -> Unit,
    onOpenOther: () -> Unit,
    onNewWindow: () -> Unit,
    onRenameWindow: () -> Unit,
) {
    val visible = remember(recentProjects, openProjects) {
        ProjectTabs.recentMenu(recentProjects, openProjects).filter { Files.isDirectory(it) }
    }
    val offsetY = with(LocalDensity.current) { BAR_HEIGHT.roundToPx() }

    Popup(
        onDismissRequest = onDismiss,
        offset = IntOffset(0, offsetY),
        properties = PopupProperties(focusable = true),
    ) {
        val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
        val bg = JewelTheme.globalColors.panelBackground
        Column(
            modifier = Modifier
                .width(320.dp)
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(6.dp))
                .padding(vertical = 4.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (visible.isEmpty()) {
                PassiveText("No recent projects")
            } else {
                for (p in visible) {
                    MenuRow(
                        title = p.fileName?.toString() ?: p.toString(),
                        subtitle = p.toString(),
                        onClick = { onOpenRecent(p) },
                    )
                }
            }
            Separator()
            MenuRow(title = "Open…", subtitle = null, onClick = onOpenOther)
            MenuRow(title = "New window…", subtitle = null, onClick = onNewWindow)
            MenuRow(title = "Rename this window…", subtitle = null, onClick = onRenameWindow)
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
private fun PassiveText(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val muted = if (JewelTheme.isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
        Text(text, color = muted)
    }
}

@Composable
private fun Separator() {
    val color = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFE3E5EA)
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(color))
}

/**
 * One project tab. Right-click hands it to another window — the replacement for dragging it under a
 * named separator, now that a window is what a group of projects is.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ProjectTab(
    project: Path,
    active: Boolean,
    dirty: Boolean,
    windows: List<Workspace>,
    isDark: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onMoveToWindow: (Long) -> Unit,
    onMoveToNewWindow: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // The active tab is a step *lighter* than the bar behind it — it should look lifted off the
    // bar, the way the workspace surface does, and that lift is the whole of the marking. Hover
    // lands between the two.
    val activeBg = if (isDark) Color(0xFF2B2D30) else Color(0xFFFFFFFF)
    val hoverBg = if (isDark) Color(0xFF2D2F33) else Color(0xFFEBECEE)
    val tabBg = when {
        active -> activeBg
        hovered -> hoverBg
        else -> Color.Transparent
    }
    val name = project.fileName?.toString() ?: project.toString()
    val muted = if (isDark) Color(0xFF808488) else Color(0xFF6B7079)
    // Color.Unspecified lets the active tab inherit the theme's default (full-strength) text colour.
    val textColor = if (active) Color.Unspecified else muted

    ContextMenuArea(items = {
        buildList {
            add(ContextMenuItem("Close tab", onClose))
            for (w in windows) add(ContextMenuItem("Move to ${w.title}") { onMoveToWindow(w.id) })
            add(ContextMenuItem("Move to new window…", onMoveToNewWindow))
        }
    }) {
        Tooltip(tooltip = { Text(project.toString()) }) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(tabBg)
                    .hoverable(interaction)
                    .clickable(onClick = onClick),
            ) {
                Row(
                    modifier = Modifier.fillMaxHeight().padding(start = 8.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    // Reserve the dot's room whether or not it's showing, so a project going dirty
                    // doesn't shuffle the tabs beside it along the bar.
                    Box(modifier = Modifier.size(7.dp)) {
                        // A small dot ahead of the name marks a project with uncommitted changes.
                        if (dirty) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(50))
                                    .background(ChangeColors.MODIFIED),
                            )
                        }
                    }
                    Text(
                        text = name,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = TAB_MAX_WIDTH),
                    )
                    // Reserve the close-affordance zone whether or not it's showing, so the name
                    // doesn't shift as the pointer moves across the bar. Shown on hover or active.
                    Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                        if (hovered || active) {
                            CloseButton(isDark = isDark, onClose = onClose)
                        }
                    }
                }
            }
        }
    }
}
