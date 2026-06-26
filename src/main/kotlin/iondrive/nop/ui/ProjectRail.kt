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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import iondrive.nop.ProjectTabs
import iondrive.nop.RailItem
import iondrive.nop.RailLayout
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import java.nio.file.Files
import java.nio.file.Path

private val RAIL_WIDTH = 38.dp
private val ADD_TAB_HEIGHT = 36.dp
private val CLOSE_ZONE = 18.dp
// Caps how long a (vertically-rotated) project name may grow, so one long name can't make the
// tab absurdly tall — anything longer is ellipsized.
private val TAB_NAME_MAX = 150.dp
private val TAB_MIN_HEIGHT = 52.dp

/**
 * A slim vertical rail of project tabs down the left edge. The top tab is a "+" that drops a list
 * of recently used projects (plus an "Open…" item to browse for a new one); every other tab names
 * a project that has been opened and stays put. The name runs vertically so the rail can be narrow.
 * Clicking a tab switches the workspace to that project, and the little "x" removes it from the rail.
 * The rail mirrors the persistent project list, so the same tabs return on the next launch.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ProjectRail(
    items: List<RailItem>,
    activeProject: Path?,
    recentProjects: List<Path>,
    onSelect: (Path) -> Unit,
    onClose: (Path) -> Unit,
    onOpenRecent: (Path) -> Unit,
    onOpenOther: () -> Unit,
    onAddSeparator: (String) -> Unit,
    onRenameSeparator: (Int, String) -> Unit,
    onRemoveSeparator: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    isDark: Boolean,
    width: Dp = RAIL_WIDTH,
) {
    val railBg = if (isDark) Color(0xFF2B2D30) else Color(0xFFF2F3F5)
    val divider = if (isDark) Color(0xFF1E1F22) else Color(0xFFD9DBE0)
    val iconTint = if (isDark) ProjectIconTintDark else ProjectIconTintLight
    val openProjects = remember(items) { RailLayout.projects(items) }

    // Drag-reorder state, shared across all rows so the dragged row tracks the pointer while the
    // others reflow. A pending separator name prompt (add or rename) is surfaced as a dialog below.
    val reorder = remember { RailReorder() }
    var sepDialog by remember { mutableStateOf<SepDialog?>(null) }

    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(railBg),
    ) {
        AddProjectTab(
            openProjects = openProjects,
            recentProjects = recentProjects,
            iconTint = iconTint,
            onOpenRecent = onOpenRecent,
            onOpenOther = onOpenOther,
            onAddSeparator = { sepDialog = SepDialog.Add },
        )
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(divider))
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            items.forEachIndexed { index, item ->
                // Key by stable per-row identity (not slot position) so a running drag's
                // pointerInput survives the list reordering under it.
                key(keyOf(item)) {
                    ReorderableRow(item = item, items = items, reorder = reorder, onMove = onMoveItem) {
                        when (item) {
                            is RailItem.Project -> ProjectTab(
                                project = item.path,
                                active = item.path == activeProject,
                                isDark = isDark,
                                onClick = { onSelect(item.path) },
                                onClose = { onClose(item.path) },
                            )
                            is RailItem.Separator -> SeparatorRow(
                                name = item.name,
                                isDark = isDark,
                                onRename = { sepDialog = SepDialog.Rename(index, item.name) },
                                onRemove = { onRemoveSeparator(index) },
                            )
                        }
                    }
                }
            }
        }
    }

    when (val d = sepDialog) {
        is SepDialog.Add -> NewEntryDialog(
            title = "New separator",
            description = "A bold label to group the project tabs below it. Drag it into place.",
            confirmLabel = "Add",
            onSubmit = { name -> onAddSeparator(name); sepDialog = null; null },
            onCancel = { sepDialog = null },
        )
        is SepDialog.Rename -> NewEntryDialog(
            title = "Rename separator",
            description = "Rename this group label.",
            initialText = d.current,
            confirmLabel = "Rename",
            onSubmit = { name -> onRenameSeparator(d.index, name); sepDialog = null; null },
            onCancel = { sepDialog = null },
        )
        null -> {}
    }
}

/** A pending separator name prompt: adding a new one, or renaming the separator at [index]. */
private sealed interface SepDialog {
    data object Add : SepDialog
    data class Rename(val index: Int, val current: String) : SepDialog
}

/** Shared drag-reorder state for the rail. One row drags at a time; the rest reflow around it. */
private class RailReorder {
    var draggingKey by mutableStateOf<String?>(null)
    // Vertical offset of the dragged row from its settled slot, in px. Reset to 0 each time the
    // row crosses a neighbour and we commit a move, so it always measures from the current slot.
    var delta by mutableStateOf(0f)
    // Measured heights per row key, so a drag knows how far to travel before swapping a neighbour.
    val heights = mutableStateMapOf<String, Int>()
}

/** Stable per-row identity for drag keys: projects by path, separators by their runtime id. */
private fun keyOf(item: RailItem): String = when (item) {
    is RailItem.Project -> "p:${item.path}"
    is RailItem.Separator -> "s:${item.id}"
}

/**
 * Wraps one rail row with drag-to-reorder. While dragging, the row follows the pointer (translation
 * + raised above its neighbours); each time it travels past half a neighbour's height we commit a
 * one-step move so the list reflows live. The drag only engages once the pointer passes the touch
 * slop, so a plain click still selects the project (and the rail still scrolls via the wheel).
 */
@Composable
private fun ReorderableRow(
    item: RailItem,
    items: List<RailItem>,
    reorder: RailReorder,
    onMove: (Int, Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val key = keyOf(item)
    // rememberUpdatedState so the long-lived drag coroutine always sees the current order/callback
    // even though pointerInput(key) is not restarted on a reorder.
    val itemsUpdated by rememberUpdatedState(items)
    val onMoveUpdated by rememberUpdatedState(onMove)
    val dragging = reorder.draggingKey == key

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { reorder.heights[key] = it.height }
            // zIndex/graphicsLayer are always present (not conditionally inserted) so the modifier
            // chain — and the pointerInput node below it — isn't rebuilt when a drag starts/ends.
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = if (dragging) reorder.delta else 0f }
            .pointerInput(key) {
                detectDragGestures(
                    onDragStart = { reorder.draggingKey = key; reorder.delta = 0f },
                    onDragEnd = { reorder.draggingKey = null; reorder.delta = 0f },
                    onDragCancel = { reorder.draggingKey = null; reorder.delta = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        reorder.delta += amount.y
                        val ordered = itemsUpdated.map(::keyOf)
                        val from = ordered.indexOf(key)
                        if (from >= 0) {
                            if (reorder.delta > 0f && from < ordered.lastIndex) {
                                val nextH = reorder.heights[ordered[from + 1]] ?: 0
                                if (nextH > 0 && reorder.delta > nextH / 2f) {
                                    onMoveUpdated(from, from + 1)
                                    reorder.delta -= nextH
                                }
                            } else if (reorder.delta < 0f && from > 0) {
                                val prevH = reorder.heights[ordered[from - 1]] ?: 0
                                if (prevH > 0 && -reorder.delta > prevH / 2f) {
                                    onMoveUpdated(from, from - 1)
                                    reorder.delta += prevH
                                }
                            }
                        }
                    },
                )
            },
    ) {
        content()
    }
}

/**
 * A standalone group label in the rail — bold, rotated to run bottom-to-top like the project tabs,
 * with a divider rule above it. Right-click to rename or remove. Purely visual: it groups the tabs
 * below it without any containment behaviour.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SeparatorRow(
    name: String,
    isDark: Boolean,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    val rule = if (isDark) Color(0xFF3A3D42) else Color(0xFFCDD0D6)
    val labelColor = if (isDark) Color(0xFFCED0D6) else Color(0xFF3C4049)
    ContextMenuArea(items = {
        listOf(
            ContextMenuItem("Rename…", onRename),
            ContextMenuItem("Remove", onRemove),
        )
    }) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(1.dp).background(rule))
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = name,
                color = labelColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .heightIn(max = TAB_NAME_MAX)
                    .vertical()
                    .rotate(-90f),
            )
        }
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
    onAddSeparator: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Tooltip(tooltip = { Text("Open a project") }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ADD_TAB_HEIGHT)
                    .clickable { expanded = !expanded },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(16.dp)) { drawPlusIcon(iconTint) }
            }
        }
        if (expanded) {
            RecentProjectsPopup(
                openProjects = openProjects,
                recentProjects = recentProjects,
                onDismiss = { expanded = false },
                onOpenRecent = { expanded = false; onOpenRecent(it) },
                onOpenOther = { expanded = false; onOpenOther() },
                onAddSeparator = { expanded = false; onAddSeparator() },
            )
        }
    }
}

/**
 * Popup anchored to the right of the "+" tab listing recent projects that aren't already open,
 * newest first, plus an "Open…" item to browse. Stale (deleted) directories are filtered out.
 */
@Composable
private fun RecentProjectsPopup(
    openProjects: List<Path>,
    recentProjects: List<Path>,
    onDismiss: () -> Unit,
    onOpenRecent: (Path) -> Unit,
    onOpenOther: () -> Unit,
    onAddSeparator: () -> Unit,
) {
    val visible = remember(recentProjects, openProjects) {
        ProjectTabs.recentMenu(recentProjects, openProjects).filter { Files.isDirectory(it) }
    }
    val offsetX = with(LocalDensity.current) { RAIL_WIDTH.roundToPx() }

    Popup(
        onDismissRequest = onDismiss,
        offset = IntOffset(offsetX, 0),
        properties = PopupProperties(focusable = true),
    ) {
        val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
        val bg = JewelTheme.globalColors.panelBackground
        Column(
            modifier = Modifier
                .width(320.dp)
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
                    RecentRow(
                        title = p.fileName?.toString() ?: p.toString(),
                        subtitle = p.toString(),
                        onClick = { onOpenRecent(p) },
                    )
                }
                Separator()
            }
            RecentRow(title = "Add separator…", subtitle = null, onClick = onAddSeparator)
            RecentRow(title = "Open…", subtitle = null, onClick = onOpenOther)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(title: String, subtitle: String?, onClick: () -> Unit) {
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

@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ProjectTab(
    project: Path,
    active: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val activeBg = if (isDark) Color(0xFF1E1F22) else Color(0xFFFFFFFF)
    val hoverBg = if (isDark) Color(0xFF323539) else Color(0xFFE6E8EC)
    val rowBg = when {
        active -> activeBg
        hovered -> hoverBg
        else -> Color.Transparent
    }
    val accent = projectTint(project, isDark)
    val name = project.fileName?.toString() ?: project.toString()
    val muted = if (isDark) Color(0xFF9AA0AA) else Color(0xFF6B7079)
    // Color.Unspecified lets the active tab inherit the theme's default (full-strength) text colour.
    val textColor = if (active) Color.Unspecified else muted

    Tooltip(tooltip = { Text(project.toString()) }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TAB_MIN_HEIGHT)
                .background(rowBg)
                .hoverable(interaction)
                .clickable(onClick = onClick),
        ) {
            // Left accent bar in the project's identity colour, only for the active tab.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(if (active) accent else Color.Transparent),
            )
            Column(
                modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Reserve the close-affordance zone whether or not it's showing, so the name
                // doesn't shift when a tab is hovered/activated. Shown on hover or when active.
                Box(modifier = Modifier.size(CLOSE_ZONE), contentAlignment = Alignment.Center) {
                    if (hovered || active) {
                        CloseButton(isDark = isDark, onClose = onClose)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                // The project name, rotated to run bottom-to-top so the rail can stay slim.
                Text(
                    text = name,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // heightIn caps the rotated *length*: vertical() swaps the axes, so the height
                    // limit here becomes the text's width limit, beyond which the name ellipsizes.
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .heightIn(max = TAB_NAME_MAX)
                        .vertical()
                        .rotate(-90f),
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CloseButton(isDark: Boolean, onClose: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val base = if (isDark) Color(0xFF9AA0AA) else Color(0xFF6B7079)
    val tint = if (hovered) {
        if (isDark) Color(0xFFE6E8EC) else Color(0xFF1F2329)
    } else base
    val bg = if (hovered) {
        if (isDark) Color(0xFF45494F) else Color(0xFFD0D3D8)
    } else Color.Transparent
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .hoverable(interaction)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(8.dp)) { drawCloseIcon(tint) }
    }
}

/**
 * Centred empty state shown when every project tab has been closed. Nudges the user toward the
 * "+" tab (and lets them click straight through to the project picker).
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun EmptyProjectState(onAdd: () -> Unit) {
    val muted = if (JewelTheme.isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
    Box(
        modifier = Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No project open")
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("Open a project…", color = muted)
            }
        }
    }
}

/**
 * Swaps a composable's measured width and height so a `rotate(-90f)`'d child (e.g. a line of text)
 * occupies an upright, tall-and-narrow footprint instead of its original wide one.
 */
private fun Modifier.vertical(): Modifier = layout { measurable, constraints ->
    // Measure the child against swapped constraints so a horizontally-laid line of text gets the
    // tab's *height* as its available width — otherwise the narrow rail would clip it to a few
    // characters. The resulting placeable is then reported with width/height swapped.
    val placeable = measurable.measure(
        Constraints(
            minWidth = constraints.minHeight,
            maxWidth = constraints.maxHeight,
            minHeight = constraints.minWidth,
            maxHeight = constraints.maxWidth,
        )
    )
    layout(placeable.height, placeable.width) {
        placeable.place(
            x = -(placeable.width / 2 - placeable.height / 2),
            y = -(placeable.height / 2 - placeable.width / 2),
        )
    }
}

private fun DrawScope.drawPlusIcon(tint: Color) {
    val c = size.width / 2f
    drawLine(tint, Offset(c, 2.5f), Offset(c, size.height - 2.5f), strokeWidth = 1.5f, cap = StrokeCap.Round)
    drawLine(tint, Offset(2.5f, c), Offset(size.width - 2.5f, c), strokeWidth = 1.5f, cap = StrokeCap.Round)
}

private fun DrawScope.drawCloseIcon(tint: Color) {
    val pad = 0.5f
    drawLine(tint, Offset(pad, pad), Offset(size.width - pad, size.height - pad), strokeWidth = 1.3f, cap = StrokeCap.Round)
    drawLine(tint, Offset(size.width - pad, pad), Offset(pad, size.height - pad), strokeWidth = 1.3f, cap = StrokeCap.Round)
}
