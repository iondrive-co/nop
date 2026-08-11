package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import iondrive.nop.GroupedStrip
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.styling.TabStyle

// Tall enough to sit level with the editor tabs Jewel's metrics size, and fixed so a collapsed group
// doesn't change the bar's height.
private val STRIP_HEIGHT = 30.dp
// Caps how wide a tab may grow, so one long file name can't push the rest off-screen.
private val TAB_MAX_WIDTH = 220.dp
// How long a drag has to be held over a collapsed group before it opens to take the dragged tab.
// Long enough that a drag passing over one on its way somewhere else doesn't spring it open.
private const val DRAG_EXPAND_MS = 600L

/**
 * The horizontal strip above the editor: the open tabs, split into named groups.
 *
 * A group is the horizontal twin of the project rail's separators — a bold label heading the tabs
 * after it, collapsible, renamable, and draggable with its tabs in tow (both strips run on
 * [GroupedStrip], which owns that behaviour). The difference is that every tab belongs to a group:
 * a session starts with one called "MR1", exactly one group is *active* at a time, and whatever the
 * user opens next lands in that one. The "+" at the end of the bar adds a group and arms it.
 *
 * Tabs drag between groups a slot at a time; a group header drags as a unit over its neighbours;
 * and resting a dragged tab on a collapsed group opens it and drops the tab inside, which is the
 * only way in — see [GroupedStrip.expandUnderDrag].
 *
 * [onTabsClosed] runs the per-tab teardown the strip itself knows nothing about (flushing edit
 * buffers, stopping launcher processes) for every tab any of these actions removes.
 *
 * The far end of the bar carries the two controls that apply to whatever is open rather than to one
 * tab: the word-wrap toggle and the "+" that adds a group.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TabStripBar(
    state: TabsState,
    style: TabStyle,
    labelFor: @Composable (Tab) -> String,
    onTabsClosed: (List<Tab>) -> Unit,
    wrapLines: Boolean = false,
    onToggleWrap: () -> Unit = {},
) {
    val isDark = JewelTheme.isDark
    val items = state.strip
    // The slots actually drawn: headers and tabs, with the tabs of any collapsed group folded away
    // into their header's block so they neither render nor drag on their own.
    val blocks = remember(items) { GroupedStrip.visibleBlocks(items) }
    // Rebuilt every composition on purpose: `state.tabs` is one long-lived snapshot list, so keying a
    // remember on it would compare it against itself and hand back a map that never sees a new tab.
    val tabsById = state.tabs.associateBy { it.id }

    val reorder = remember { StripReorder() }
    var groupDialog by remember { mutableStateOf<GroupDialog?>(null) }

    // Hover-to-open: a drag held over a collapsed group opens it after a beat and slides the dragged
    // tab in at the front, so a tab can be moved *into* a closed group and not just over it. Keyed on
    // `items` too, so a reorder mid-drag (including this one) restarts the wait.
    LaunchedEffect(items, reorder.draggingKey, reorder.expandGroupId) {
        val dragKey = reorder.draggingKey ?: return@LaunchedEffect
        val groupId = reorder.expandGroupId ?: return@LaunchedEffect
        delay(DRAG_EXPAND_MS)
        val header = items.indexOfFirst { it is StripItem.Header && it.group.id == groupId }
        val from = items.indexOfFirst { keyOf(it) == dragKey }
        val step = GroupedStrip.expandUnderDrag(items, from, header) { reorder.widths[keyOf(it)] ?: 0 }
        if (step != null) {
            state.applyStrip(step.items)
            // Re-anchor rather than rebase by the distance travelled. The tab has been *placed* where
            // the pointer was resting, and the group that just unfolded is usually far wider than the
            // offset the drag had built up — so subtracting the travel leaves the drag wound up
            // against the boundary it just crossed, and the very next pointer sample steps the tab
            // straight back out of the group it was dropped into. Zero has no wind-up to unwind, and
            // dragStep ignores a zero delta, so the drop stays put until the user moves again.
            reorder.delta = 0f
        }
        reorder.expandGroupId = null
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(STRIP_HEIGHT).background(style.colors.background),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            blocks.forEach { block ->
                val item = items[block.start]
                // Key by stable per-slot identity (not position) so a running drag's pointerInput
                // survives the strip reordering under it.
                key(keyOf(item)) {
                    ReorderableSlot(item = item, items = items, reorder = reorder, state = state) {
                        when (item) {
                            is StripItem.Header -> GroupHeader(
                                group = item.group,
                                tabCount = state.tabsIn(item.group.id).size,
                                active = item.group.id == state.activeGroupId,
                                first = block.start == 0,
                                isDark = isDark,
                                style = style,
                                onActivate = {
                                    state.selectGroup(item.group.id)
                                    // An armed group that hides its tabs is just confusing; picking
                                    // one to open into unfolds it.
                                    state.setCollapsed(item.group.id, collapsed = false)
                                },
                                onToggleCollapse = { state.toggleCollapse(item.group.id) },
                                onRename = { groupDialog = GroupDialog.Rename(item.group.id, item.group.name) },
                                onAddGroup = { state.addGroup() },
                                onClose = { onTabsClosed(state.removeGroup(item.group.id)) },
                            )
                            is StripItem.Slot -> {
                                val tab = tabsById[item.tabId]
                                if (tab != null) {
                                    EditorTab(
                                        label = labelFor(tab),
                                        selected = tab.id == state.selectedId,
                                        isDark = isDark,
                                        style = style,
                                        otherGroups = state.groups.filter { it.id != state.groupOf(tab.id) },
                                        onClick = { state.select(tab.id) },
                                        onClose = { onTabsClosed(listOf(tab)); state.close(tab.id) },
                                        onCloseOthers = if (state.tabs.size > 1) {
                                            { onTabsClosed(state.closeOthers(tab.id)) }
                                        } else {
                                            null
                                        },
                                        onMoveToGroup = { state.moveTabToGroup(tab.id, it) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // Pinned outside the scroll area so a strip full of tabs can still reach them.
        WrapToggleButton(isDark = isDark, enabled = wrapLines, onClick = onToggleWrap)
        AddGroupButton(isDark = isDark, onClick = { state.addGroup() })
    }

    when (val dialog = groupDialog) {
        is GroupDialog.Rename -> NewEntryDialog(
            title = "Rename tab group",
            description = "Rename this group of editor tabs.",
            initialText = dialog.current,
            confirmLabel = "Rename",
            onSubmit = { name -> state.renameGroup(dialog.id, name); groupDialog = null; null },
            onCancel = { groupDialog = null },
        )
        null -> {}
    }
}

/** A pending group prompt. Only renaming asks for text — a new group is named for you. */
private sealed interface GroupDialog {
    data class Rename(val id: Long, val current: String) : GroupDialog
}

/** Shared drag-reorder state for the strip. One slot drags at a time; the rest reflow around it. */
private class StripReorder {
    var draggingKey by mutableStateOf<String?>(null)
    // Horizontal offset of the dragged slot from where it settled, in px. Reset to 0 each time the
    // slot crosses a neighbour and we commit a move, so it always measures from the current slot.
    var delta by mutableStateOf(0f)
    // Whether the dragged slot carries its group. Decided once at drag start (see the drag handler)
    // so the granularity can't flip part-way through a drag.
    var dragAsGroup by mutableStateOf(false)
    // Id of the collapsed group the drag is currently held over, which hover-to-open expands once the
    // drag has rested on it. Null whenever the drag isn't over a closed group.
    var expandGroupId by mutableStateOf<Long?>(null)
    // Measured widths per slot key, so a drag knows how far to travel before swapping a neighbour.
    val widths = mutableStateMapOf<String, Int>()

    /** Drops the drag: the slot snaps back into place and stops eyeing a group to open. */
    fun settle() {
        draggingKey = null
        delta = 0f
        expandGroupId = null
    }
}

/** Stable per-slot identity for drag keys: tabs by id, groups by their runtime id. */
private fun keyOf(item: StripItem): String = when (item) {
    is StripItem.Header -> "g:${item.group.id}"
    is StripItem.Slot -> "t:${item.tabId}"
}

/**
 * Wraps one strip slot with drag-to-reorder, the horizontal counterpart of the project rail's
 * reorderable row. While dragging, the slot follows the pointer (translation + raised above its
 * neighbours); each time it travels past half a neighbour's width we swap the two so the strip
 * reflows live — see [GroupedStrip.dragStep], which owns the whole decision. A group header that
 * heads tabs drags as its group, taking them along and hopping a neighbouring group whole;
 * everything else steps one visible slot at a time, which is how a tab moves between groups. A
 * closed group is dragged *over*, never into — resting on one opens it instead. The drag only
 * engages once the pointer passes the touch slop, so a plain click still selects the tab.
 */
@Composable
private fun ReorderableSlot(
    item: StripItem,
    items: List<StripItem>,
    reorder: StripReorder,
    state: TabsState,
    content: @Composable () -> Unit,
) {
    val key = keyOf(item)
    // rememberUpdatedState so the long-lived drag coroutine always sees the current order even
    // though pointerInput(key) is not restarted on a reorder.
    val itemsUpdated by rememberUpdatedState(items)
    val dragging = reorder.draggingKey == key

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .onSizeChanged { reorder.widths[key] = it.width }
            // zIndex/graphicsLayer are always present (not conditionally inserted) so the modifier
            // chain — and the pointerInput node below it — isn't rebuilt when a drag starts/ends.
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationX = if (dragging) reorder.delta else 0f }
            .pointerInput(key) {
                detectDragGestures(
                    onDragStart = {
                        reorder.draggingKey = key
                        reorder.delta = 0f
                        // Pinned for the whole drag: a header owning tabs moves as a group, while one
                        // owning none stays a bare label that can be dropped between any two tabs.
                        // Deciding once keeps a label from turning into a group mid-drag as it picks
                        // up tabs on the way past them.
                        val cur = itemsUpdated
                        reorder.dragAsGroup =
                            GroupedStrip.groupSpan(cur, cur.indexOfFirst { keyOf(it) == key }) > 1
                    },
                    onDragEnd = { reorder.settle() },
                    onDragCancel = { reorder.settle() },
                    onDrag = { change, amount ->
                        change.consume()
                        reorder.delta += amount.x
                        val cur = itemsUpdated
                        val width = { slot: StripItem -> reorder.widths[keyOf(slot)] ?: 0 }
                        val from = cur.indexOfFirst { keyOf(it) == key }
                        val step = GroupedStrip.dragStep(
                            items = cur,
                            from = from,
                            asGroup = reorder.dragAsGroup,
                            delta = reorder.delta,
                            canLand = TabGroups::canLand,
                            extent = width,
                        )
                        if (step != null) {
                            state.applyStrip(step.items)
                            reorder.delta -= step.travelled
                        }
                        // Whether the slot now rests on a closed group, which hover-to-open expands if
                        // the drag stays there. Read off the order the step just produced, since the
                        // offset was rebased to match it. A group header never opens one — a group
                        // can't nest inside another — so it only ever passes over them.
                        val after = step?.items ?: cur
                        reorder.expandGroupId = if (reorder.dragAsGroup) null else {
                            GroupedStrip.collapsedUnderDrag(
                                after,
                                after.indexOfFirst { keyOf(it) == key },
                                reorder.delta,
                                width,
                            )?.let { (after[it] as StripItem.Header).group.id }
                        }
                    },
                )
            },
    ) {
        content()
    }
}

/**
 * A group label in the tab bar — bold and letter-spaced like the rail's separators, with a
 * disclosure chevron marking its state and a rule separating it from the group before it. The
 * *active* group (the one new files open into) carries the same accent underline the selected tab
 * does, so it's obvious where the next click in the tree will land. Clicking the label arms the
 * group; clicking the chevron folds its tabs away. Right-click renames, adds or closes one.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GroupHeader(
    group: TabGroup,
    tabCount: Int,
    active: Boolean,
    first: Boolean,
    isDark: Boolean,
    style: TabStyle,
    onActivate: () -> Unit,
    onToggleCollapse: () -> Unit,
    onRename: () -> Unit,
    onAddGroup: () -> Unit,
    onClose: () -> Unit,
) {
    val rule = if (isDark) Color(0xFF43454A) else Color(0xFFD0D2D5)
    val labelColor = when {
        active -> if (isDark) Color(0xFFDFE1E5) else Color(0xFF1F2329)
        else -> if (isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
    }
    ContextMenuArea(items = {
        listOf(
            ContextMenuItem(if (group.collapsed) "Expand Group" else "Collapse Group", onToggleCollapse),
            ContextMenuItem("Rename Group…", onRename),
            ContextMenuItem("New Group", onAddGroup),
            ContextMenuItem("Close Group", onClose),
        )
    }) {
        Row(modifier = Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            // Rules between groups frame each label so it reads as a divider rather than another tab.
            if (!first) {
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().padding(vertical = 5.dp).background(rule))
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clickable(onClick = onToggleCollapse)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(9.dp)) { drawDisclosure(labelColor, group.collapsed) }
            }
            // Deliberately no tooltip: this row is also the right-click target for the group menu,
            // and a tooltip popping up under the pointer covers the menu it just opened.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clickable(onClick = onActivate)
                    // Padding ahead of the underline keeps the armed-group rule clear of the selected
                    // tab's, so the two accents read as two marks rather than one long line.
                    .padding(end = 8.dp)
                    .underline(active, style.colors.underlineSelected, style.metrics.underlineThickness),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // A folded-away group says how much it's hiding, since its tabs can't.
                    text = if (group.collapsed && tabCount > 0) "${group.name} · $tabCount" else group.name,
                    color = labelColor,
                    // A monospaced, letter-spaced face sets the group label apart from the file
                    // names beside it so it reads as a heading rather than another tab.
                    fontFamily = NopFonts.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = TAB_MAX_WIDTH),
                )
            }
        }
    }
}

/**
 * One editor tab: the file's label, an accent underline while selected, and a close "x" once the
 * pointer is on it. Drawn from the theme's editor-tab style so it matches the rest of the IDE
 * chrome; nop draws it itself (rather than using Jewel's tab strip) because the tabs here have to
 * interleave with group headers and drag between them.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EditorTab(
    label: String,
    selected: Boolean,
    isDark: Boolean,
    style: TabStyle,
    otherGroups: List<TabGroup>,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onCloseOthers: (() -> Unit)?,
    onMoveToGroup: (Long) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected -> style.colors.backgroundSelected
        hovered -> style.colors.backgroundHovered
        else -> style.colors.background
    }
    val content = when {
        selected -> style.colors.contentSelected
        hovered -> style.colors.contentHovered
        else -> style.colors.content
    }

    ContextMenuArea(items = {
        buildList {
            onCloseOthers?.let { add(ContextMenuItem("Close Other Tabs", it)) }
            // A submenu isn't available here, so each group gets its own flat entry — the
            // discoverable alternative to dragging the tab across.
            otherGroups.forEach { group -> add(ContextMenuItem("Move to ${group.name}") { onMoveToGroup(group.id) }) }
        }
    }) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .background(background)
                .underline(selected, style.colors.underlineSelected, style.metrics.underlineThickness)
                .hoverable(interaction)
                .clickable(onClick = onClick),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(style.metrics.closeContentGap),
            ) {
                Text(
                    text = label,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = TAB_MAX_WIDTH),
                )
                // Reserve the close zone whether or not it's showing, so the label doesn't shift as
                // the pointer moves across the strip. Shown on hover or when selected.
                Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    if (hovered || selected) CloseButton(isDark = isDark, onClose = onClose)
                }
            }
        }
    }
}

/**
 * An accent rule along the bottom edge, marking the selected tab and the armed group.
 *
 * Painted rather than laid out: the strip scrolls horizontally, so its children are measured against
 * an unbounded width, and a `fillMaxWidth()` underline inside one is silently a no-op — it sizes to
 * nothing and never appears. Drawing after the content sidesteps the constraint entirely.
 */
private fun Modifier.underline(show: Boolean, color: Color, thickness: Dp): Modifier =
    if (!show) this else drawWithContent {
        drawContent()
        val height = thickness.toPx()
        drawRect(
            color = color,
            topLeft = Offset(0f, size.height - height),
            size = Size(size.width, height),
        )
    }

/**
 * The word-wrap toggle, beside the "+". Applies to every file tab and every diff at once — it's a
 * reading preference, not a per-tab one — and is remembered across restarts. Lit in the same accent
 * the blame toggle uses when on, so the two on/off buttons in the chrome read alike.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WrapToggleButton(isDark: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = when {
        enabled -> if (isDark) Color(0xFF6DA9FF) else Color(0xFF2F6FE0)
        isDark -> ProjectIconTintDark
        else -> ProjectIconTintLight
    }
    Tooltip(tooltip = { Text(if (enabled) "Turn off word wrap" else "Wrap long lines in files and diffs") }) {
        Box(
            modifier = Modifier.fillMaxHeight().width(28.dp).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(13.dp)) { drawWrapIcon(tint) }
        }
    }
}

/** The "+" at the end of the bar: adds a group and arms it, so the next file opened lands there. */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AddGroupButton(isDark: Boolean, onClick: () -> Unit) {
    val tint = if (isDark) ProjectIconTintDark else ProjectIconTintLight
    Tooltip(tooltip = { Text("New tab group") }) {
        Box(
            modifier = Modifier.fillMaxHeight().width(28.dp).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(13.dp)) { drawPlusIcon(tint) }
        }
    }
}
