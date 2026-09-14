package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import iondrive.nop.ProjectTab
import iondrive.nop.StripDrag
import iondrive.nop.Workspace
import iondrive.nop.Workspaces
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.ContextSubmenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import java.nio.file.Path

// One row of the bar, sized to match the editor tab strip below it so the two read as stacked
// chrome rather than two unrelated bands.
private val BAR_HEIGHT = 30.dp
private val ADD_TAB_WIDTH = 28.dp
// Caps how wide a project tab may grow, so one long name can't push the rest off the bar.
private val TAB_MAX_WIDTH = 220.dp

/**
 * The horizontal bar of project tabs along the top of the window, under the title. Every tab names a
 * project this window has open; clicking one switches the workspace to it, and the little "x"
 * removes it from the bar. The "+" straight after the last tab opens another tab on whatever project
 * is selected — two tabs on one project are two places to work in it, not a mistake — and a tab's
 * right-click menu renames it, which is how those two stop reading as the same tab twice.
 *
 * A project isn't reached from this bar at all: the word "Project" over the file tree is the way to
 * one, which is where the eye already is when the question is "which project". What lives at the far
 * right instead is the windows button — the way back to a window that was closed, and the way to
 * name this one.
 *
 * The bar holds one window's tabs and nothing else. Grouping a long list of projects is what separate
 * windows are for — a tab's context menu moves it to another window — so the bar itself is a flat
 * strip that can be dragged into whatever order suits.
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
    tabs: List<ProjectTab>,
    activeTab: Long?,
    dirtyProjects: Set<Path>,
    windows: List<Workspace>,
    windowId: Long,
    onSelect: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNewTab: () -> Unit,
    onRenameTab: (Long, String) -> Unit,
    onReorder: (List<ProjectTab>) -> Unit,
    onOpenOther: () -> Unit,
    onNewWindow: (String) -> Unit,
    onRenameWindow: (String) -> Unit,
    onRenameOtherWindow: (Long, String) -> Unit,
    onShowWindow: (Long) -> Unit,
    onDiscardWindow: (Long) -> Unit,
    onMoveToWindow: (Long, Long) -> Unit,
    onMoveToNewWindow: (Long, String) -> Unit,
    isDark: Boolean,
) {
    // The bar sits a shade darker than the workspace below it, so the two read as separate surfaces
    // without needing a heavy border between them.
    val barBg = if (isDark) Color(0xFF1E1F22) else Color(0xFFF7F8FA)
    // Subtle border between the tabs and the windows button that ends the bar.
    val divider = if (isDark) Color(0xFF323438) else Color(0xFFE0E1E3)
    val iconTint = if (isDark) ProjectIconTintDark else ProjectIconTintLight
    val thisWindow = remember(windows, windowId) { windows.firstOrNull { it.id == windowId } }
    val otherWindows = remember(windows, windowId) { windows.filter { it.id != windowId } }
    val parked = remember(windows) { Workspaces.parked(windows) }
    val selected = remember(tabs, activeTab) { tabs.firstOrNull { it.id == activeTab } }

    // Drag-reorder state, shared across all tabs so the dragged tab tracks the pointer while the
    // others reflow. A pending name prompt (rename, move-to-new) is a dialog below.
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
        FlowRow(modifier = Modifier.weight(1f)) {
            for (tab in tabs) {
                // Key by the tab's id (not slot position) so a running drag's pointerInput survives
                // the list reordering under it — and so two tabs on one project stay distinct.
                key(tab.id) {
                    ReorderableTab(
                        tab = tab,
                        tabs = tabs,
                        reorder = reorder,
                        onReorder = onReorder,
                    ) {
                        ProjectTabView(
                            tab = tab,
                            active = tab.id == activeTab,
                            dirty = tab.path in dirtyProjects,
                            windows = otherWindows,
                            isDark = isDark,
                            onClick = { onSelect(tab.id) },
                            onClose = { onClose(tab.id) },
                            onRename = { dialog = BarDialog.RenameTab(tab.id, tab.label) },
                            onMoveToWindow = { id -> onMoveToWindow(tab.id, id) },
                            onMoveToNewWindow = { dialog = BarDialog.MoveToNewWindow(tab.id) },
                        )
                    }
                }
            }
            // Another tab on the project in front — the one gesture that opens a second view of a
            // project, so it sits where the row of tabs ends rather than anywhere else. A window
            // with no tabs has no project to make another of, and shows nothing here.
            if (selected != null) {
                NewTabButton(label = selected.label, iconTint = iconTint, onClick = onNewTab)
            }
        }
        Box(modifier = Modifier.width(1.dp).height(BAR_HEIGHT).background(divider))
        // The way back to a window that was closed, and to this window's name. At the far right,
        // away from the tabs: it is about the window, not about what is in it.
        WindowsTab(
            parked = parked.size,
            iconTint = iconTint,
            onClick = { picking = true },
        )
    }

    if (picking) {
        WindowPickerPopup(
            parked = parked,
            thisWindow = thisWindow,
            onOpenWindow = onShowWindow,
            onRenameWindow = { id ->
                dialog = BarDialog.RenameOtherWindow(id, windows.firstOrNull { it.id == id }?.name.orEmpty())
            },
            onRenameThisWindow = { dialog = BarDialog.RenameWindow(thisWindow?.name.orEmpty()) },
            onDiscardWindow = onDiscardWindow,
            // Straight to a new window, no name prompt: naming one is the picker's own "Name this
            // window…", and an unnamed window is titled by its project until it is used.
            onNewWindow = { onNewWindow("") },
            onOpenProject = onOpenOther,
            onDismiss = { picking = false },
        )
    }

    when (val d = dialog) {
        is BarDialog.RenameTab -> NewEntryDialog(
            title = "Rename tab",
            description = "What this tab says on the bar. Leave it empty to go back to the project's own name.",
            initialText = d.current,
            confirmLabel = "Rename",
            onSubmit = { name -> onRenameTab(d.tab, name); dialog = null; null },
            onCancel = { dialog = null },
        )
        is BarDialog.RenameWindow -> NewEntryDialog(
            title = if (d.current.isBlank()) "Name window" else "Rename window",
            description = "The name this window goes by in its title bar and in the window picker.",
            initialText = d.current,
            confirmLabel = if (d.current.isBlank()) "Name" else "Rename",
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
            onSubmit = { name -> onMoveToNewWindow(d.tab, name); dialog = null; null },
            onCancel = { dialog = null },
        )
        null -> {}
    }
}

/** A pending name prompt raised from the bar. */
private sealed interface BarDialog {
    data class RenameTab(val tab: Long, val current: String) : BarDialog
    data class RenameWindow(val current: String) : BarDialog
    data class RenameOtherWindow(val id: Long, val current: String) : BarDialog
    data class MoveToNewWindow(val tab: Long) : BarDialog
}

/** Shared drag-reorder state for the bar. One tab drags at a time; the rest reflow around it. */
private class BarReorder {
    var draggingKey by mutableStateOf<Long?>(null)
    // Horizontal offset of the dragged tab from its settled slot, in px. Reset to 0 each time the
    // tab crosses a neighbour and we commit a move, so it always measures from the current slot.
    var delta by mutableStateOf(0f)
    // Measured widths per tab, so a drag knows how far to travel before swapping a neighbour.
    val widths = mutableStateMapOf<Long, Int>()

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
    tab: ProjectTab,
    tabs: List<ProjectTab>,
    reorder: BarReorder,
    onReorder: (List<ProjectTab>) -> Unit,
    content: @Composable () -> Unit,
) {
    // rememberUpdatedState so the long-lived drag coroutine always sees the current order/callback
    // even though pointerInput(tab.id) is not restarted on a reorder.
    val tabsUpdated by rememberUpdatedState(tabs)
    val onReorderUpdated by rememberUpdatedState(onReorder)
    val dragging = reorder.draggingKey == tab.id

    Box(
        // A fixed height rather than fillMaxHeight: the bar is as many rows tall as the tabs need,
        // and a tab should be one row of it, not all of them.
        modifier = Modifier
            .height(BAR_HEIGHT)
            .onSizeChanged { reorder.widths[tab.id] = it.width }
            // zIndex/graphicsLayer are always present (not conditionally inserted) so the modifier
            // chain — and the pointerInput node below it — isn't rebuilt when a drag starts/ends.
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationX = if (dragging) reorder.delta else 0f }
            .pointerInput(tab.id) {
                detectDragGestures(
                    onDragStart = {
                        reorder.draggingKey = tab.id
                        reorder.delta = 0f
                    },
                    onDragEnd = { reorder.settle() },
                    onDragCancel = { reorder.settle() },
                    onDrag = { change, amount ->
                        change.consume()
                        reorder.delta += amount.x
                        val cur = tabsUpdated
                        val from = cur.indexOfFirst { it.id == tab.id }
                        val sizes = cur.map { reorder.widths[it.id] ?: 0 }
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

/**
 * The "+" at the end of the row of tabs: another tab on the project that is showing. It names that
 * project in its tooltip, since what it opens depends on which tab is selected.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NewTabButton(label: String, iconTint: Color, onClick: () -> Unit) {
    Tooltip(tooltip = { Text("Another tab on $label") }) {
        Box(
            modifier = Modifier
                .width(ADD_TAB_WIDTH)
                .height(BAR_HEIGHT)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(13.dp)) { drawPlusIcon(iconTint) }
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
        0 -> "Windows — open one, or name this one"
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
 * One project tab. Right-click renames it, closes it, or hands it to another window — the last of
 * those being the replacement for dragging it under a named separator, now that a window is what a
 * group of projects is.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ProjectTabView(
    tab: ProjectTab,
    active: Boolean,
    dirty: Boolean,
    windows: List<Workspace>,
    isDark: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onRename: () -> Unit,
    onMoveToWindow: (Long) -> Unit,
    onMoveToNewWindow: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Held here rather than left to ContextMenuArea's own so the tooltip below can be turned off
    // while the menu is up: the pointer is still over the tab, so the tooltip would otherwise hang
    // on over the menu it just opened, covering an entry.
    val menu = remember { ContextMenuState() }

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
    val muted = if (isDark) Color(0xFF808488) else Color(0xFF6B7079)
    // Color.Unspecified lets the active tab inherit the theme's default (full-strength) text colour.
    val textColor = if (active) Color.Unspecified else muted

    ContextMenuArea(items = {
        listOf(
            ContextMenuItem("Close tab", onClose),
            ContextMenuItem("Rename tab…", onRename),
            // Where it could go, in one entry rather than a line per window: the windows are a list
            // that grows, and they would otherwise push "Close tab" around as they came and went.
            // The list is built when the submenu opens, so it is never stale.
            ContextSubmenu("Move to") {
                windows.map { w -> ContextMenuItem(w.title) { onMoveToWindow(w.id) } } +
                    ContextMenuItem("New window…", onMoveToNewWindow)
            },
        )
    }, state = menu) {
        Tooltip(
            tooltip = { Text(tab.path.toString()) },
            enabled = menu.status is ContextMenuState.Status.Closed,
        ) {
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
                        text = tab.label,
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
