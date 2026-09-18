package iondrive.nop.ui

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.Ago
import iondrive.nop.Workspace
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * The window picker: what nop offers instead of leaving a closed window unreachable.
 *
 * Closing a window parks it with its tabs, and this card is where those come back — one row per
 * parked window naming it, how many projects it holds, when it was closed and which projects they
 * are. Clicking a row opens that window again; a row can also be renamed, or discarded for good
 * behind a confirmation, and there is a button for a window that never existed before.
 *
 * Naming the window the card was opened from lives here too: a window's name is a window-level
 * thing, and this is the one place that is about windows rather than about what is inside one.
 *
 * It shows in two places, which between them cover every way a user can end up wanting a window
 * back: [WindowPickerPanel] fills a window that has no project tabs (there is nothing else for that
 * window to show, and everything worth doing next is on this card), and [WindowPickerDialog] is the
 * same card raised over a working window from the bar's windows button.
 */
@Composable
fun WindowPickerPanel(
    parked: List<Workspace>,
    thisWindow: Workspace?,
    onOpenWindow: (Long) -> Unit,
    onRenameWindow: (Long, String) -> Unit,
    onDiscardWindow: (Long) -> Unit,
    onNewWindow: () -> Unit,
    onOpenProject: () -> Unit,
) {
    // The panel raises its own rename prompt: it fills the window rather than floating over it, so
    // there is no popup underneath for a second one to fight with.
    var renaming by remember { mutableStateOf<Workspace?>(null) }
    val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)

    Box(
        modifier = Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground),
        contentAlignment = Alignment.Center,
    ) {
        WindowPickerCard(
            modifier = Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, border, RoundedCornerShape(8.dp))
                .padding(20.dp),
            parked = parked,
            thisWindow = thisWindow,
            onOpenWindow = onOpenWindow,
            onRenameWindow = { id -> renaming = parked.firstOrNull { it.id == id } },
            onRenameThisWindow = { renaming = thisWindow },
            onDiscardWindow = onDiscardWindow,
            onNewWindow = onNewWindow,
            onOpenProject = onOpenProject,
        )
    }

    renaming?.let { window ->
        NewEntryDialog(
            title = if (window.name.isBlank()) "Name window" else "Rename window",
            description = if (window.id == thisWindow?.id) {
                "The name this window goes by in its title bar and in the window picker."
            } else {
                "The name this closed window goes by in the picker, and will go by when it opens."
            },
            initialText = window.name,
            confirmLabel = if (window.name.isBlank()) "Name" else "Rename",
            onSubmit = { name -> onRenameWindow(window.id, name); renaming = null; null },
            onCancel = { renaming = null },
        )
    }
}

/**
 * The picker over a window that is already showing a project.
 *
 * A window of its own, not a popup over nop's. The button that raises it sits above the tool
 * region, and a terminal there is drawn over any popup in the window: the card came up with
 * everything right of the editor missing. See [DialogFrame].
 */
@Composable
fun WindowPickerDialog(
    parked: List<Workspace>,
    thisWindow: Workspace?,
    onOpenWindow: (Long) -> Unit,
    onRenameWindow: (Long) -> Unit,
    onRenameThisWindow: () -> Unit,
    onDiscardWindow: (Long) -> Unit,
    onNewWindow: () -> Unit,
    onOpenProject: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Its height follows the list of closed windows, so only the width is fixed.
    DialogFrame(title = "Open a window", onClose = onDismiss, size = DpSize(460.dp, Dp.Unspecified)) {
        WindowPickerCard(
            parked = parked,
            thisWindow = thisWindow,
            onOpenWindow = { onDismiss(); onOpenWindow(it) },
            onRenameWindow = { onDismiss(); onRenameWindow(it) },
            onRenameThisWindow = { onDismiss(); onRenameThisWindow() },
            onDiscardWindow = onDiscardWindow,
            onNewWindow = { onDismiss(); onNewWindow() },
            onOpenProject = { onDismiss(); onOpenProject() },
        )
    }
}

@Composable
private fun WindowPickerCard(
    parked: List<Workspace>,
    thisWindow: Workspace?,
    onOpenWindow: (Long) -> Unit,
    onRenameWindow: (Long) -> Unit,
    onRenameThisWindow: () -> Unit,
    onDiscardWindow: (Long) -> Unit,
    onNewWindow: () -> Unit,
    onOpenProject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = if (JewelTheme.isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
    // The window a discard is being confirmed for. One at a time, and clicking anything else in the
    // card puts the question away — a destructive answer should need the click it asked for.
    var confirming by remember { mutableStateOf<Long?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Open a window", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            text = if (parked.isEmpty()) {
                "Nothing is waiting — every window you closed has been opened again."
            } else {
                "These windows were closed, but their projects are still in them. " +
                    "Pick one up where you left it, or start something new."
            },
            color = muted,
        )
        if (parked.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(top = 8.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (window in parked) {
                    key(window.id) {
                        if (confirming == window.id) {
                            DiscardConfirm(
                                window = window,
                                onDiscard = { confirming = null; onDiscardWindow(window.id) },
                                onKeep = { confirming = null },
                            )
                        } else {
                            ParkedRow(
                                window = window,
                                onOpen = { confirming = null; onOpenWindow(window.id) },
                                onRename = { confirming = null; onRenameWindow(window.id) },
                                onDiscard = { confirming = window.id },
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardButton(text = "New window", onClick = { confirming = null; onNewWindow() })
            CardButton(text = "Open a project…", onClick = { confirming = null; onOpenProject() })
            // A window nobody has named goes by whatever project it is showing, so the button says
            // what it would be doing: giving it a name of its own, rather than changing one.
            if (thisWindow != null) {
                CardButton(
                    text = if (thisWindow.name.isBlank()) "Name this window…" else "Rename this window…",
                    onClick = { confirming = null; onRenameThisWindow() },
                )
            }
        }
    }
}

/** One parked window: its name, what it holds, when it was put away, and what to do with it. */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ParkedRow(
    window: Workspace,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDiscard: () -> Unit,
) {
    val isDark = JewelTheme.isDark
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hoverBg = if (isDark) Color(0xFF2D2F33) else Color(0xFFEBECEE)
    val muted = if (isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
    val names = remember(window.tabs) { window.tabs.joinToString(" · ") { it.label } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) hoverBg else Color.Transparent)
            .hoverable(interaction)
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(window.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = "${projectCount(window.projects.size)} · closed ${Ago.of(window.closedAt)}",
                color = muted,
            )
            Text(names, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Only on hover: the row is for opening the window, and two more targets on every row at
        // rest would make the card read as a settings screen rather than a way back in.
        if (hovered) {
            RowIcon(glyph = "✎", tooltip = "Rename this window", onClick = onRename)
            RowIcon(glyph = "✕", tooltip = "Discard this window", onClick = onDiscard, danger = true)
        }
    }
}

@Composable
private fun DiscardConfirm(window: Workspace, onDiscard: () -> Unit, onKeep: () -> Unit) {
    val muted = if (JewelTheme.isDark) Color(0xFF8B8F99) else Color(0xFF7A7E87)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Discard “${window.title}” and its ${projectCount(window.projects.size)}?",
            color = muted,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        CardButton(text = "Discard", onClick = onDiscard, danger = true)
        CardButton(text = "Keep it", onClick = onKeep)
    }
}

private fun projectCount(n: Int): String = if (n == 1) "1 project" else "$n projects"

@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RowIcon(glyph: String, tooltip: String, onClick: () -> Unit, danger: Boolean = false) {
    val isDark = JewelTheme.isDark
    val tint = when {
        danger -> ChangeColors.REMOVED
        isDark -> Color(0xFF9DA3AB)
        else -> Color(0xFF6B7079)
    }
    org.jetbrains.jewel.ui.component.Tooltip(tooltip = { Text(tooltip) }) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = tint)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CardButton(text: String, onClick: () -> Unit, danger: Boolean = false) {
    val isDark = JewelTheme.isDark
    val border = if (isDark) Color(0xFF4A4D53) else Color(0xFFC6C9CF)
    val color = if (danger) ChangeColors.REMOVED else Color.Unspecified
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = color)
    }
}
