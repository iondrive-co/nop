package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import iondrive.nop.launchers.Launcher
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * Play button + popup that lists the project's launchers and lets the user add new ones, plus the
 * two things that are about starting work rather than about a script: a fresh terminal, and the
 * agent accounts. Sits to the right of the "Change…" button in the project panel.
 */
@Composable
fun LauncherButton(
    launchers: List<Launcher>,
    onRun: (Launcher) -> Unit,
    onNewTerminal: () -> Unit,
    onAgentAccounts: () -> Unit,
    onAdd: (Launcher) -> Unit,
    onDelete: (Launcher) -> Unit,
    readOnlyNames: Set<String> = emptySet(),
) {
    var expanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(onClick = { expanded = !expanded }) {
            Text("▶")
        }
        if (expanded) {
            Popup(
                popupPositionProvider = BelowAnchorProvider,
                onDismissRequest = { expanded = false },
                // dismissOnClickOutside=false avoids a race where the very click that opens the
                // popup is then re-delivered to it as an "outside" click and closes it again.
                // The popup is closed explicitly when an item is chosen or the button is re-clicked.
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
            ) {
                LauncherMenu(
                    launchers = launchers,
                    readOnlyNames = readOnlyNames,
                    onRun = { launcher ->
                        expanded = false
                        onRun(launcher)
                    },
                    onNewTerminal = {
                        expanded = false
                        onNewTerminal()
                    },
                    onAgentAccounts = {
                        expanded = false
                        onAgentAccounts()
                    },
                    onAddRequest = {
                        expanded = false
                        showAddDialog = true
                    },
                    onDelete = onDelete,
                )
            }
        }
    }

    if (showAddDialog) {
        LauncherAddDialog(
            existingNames = launchers.map { it.name }.toSet(),
            onSubmit = { launcher ->
                onAdd(launcher)
                showAddDialog = false
            },
            onCancel = { showAddDialog = false },
        )
    }
}

@Composable
private fun LauncherMenu(
    launchers: List<Launcher>,
    readOnlyNames: Set<String>,
    onRun: (Launcher) -> Unit,
    onNewTerminal: () -> Unit,
    onAgentAccounts: () -> Unit,
    onAddRequest: () -> Unit,
    onDelete: (Launcher) -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(JewelTheme.globalColors.panelBackground)
            .width(280.dp)
            .padding(6.dp),
    ) {
        if (launchers.isEmpty()) {
            Text(
                "No launchers yet",
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        } else {
            for (launcher in launchers) {
                LauncherRow(
                    launcher = launcher,
                    readOnly = launcher.name in readOnlyNames,
                    onRun = { onRun(launcher) },
                    onDelete = { onDelete(launcher) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onNewTerminal, modifier = Modifier.fillMaxWidth()) {
            Text("New Terminal")
        }
        Spacer(Modifier.height(4.dp))
        // The second way to the accounts dialog, beside the usage indicator's. This menu is already
        // where "start something in this project" lives, and setting an account up is what you come
        // looking for when the Agent tab has nothing to offer yet.
        OutlinedButton(onClick = onAgentAccounts, modifier = Modifier.fillMaxWidth()) {
            Text("Agent accounts…")
        }
        Spacer(Modifier.height(4.dp))
        DefaultButton(onClick = onAddRequest, modifier = Modifier.fillMaxWidth()) {
            Text("Add launcher…")
        }
    }
}

@Composable
private fun LauncherRow(launcher: Launcher, readOnly: Boolean, onRun: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onRun)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Column {
                Text(launcher.name)
                Text(
                    launcher.command,
                    color = ChangeColors.UNTRACKED,
                )
            }
        }
        // Auto-discovered launchers (e.g. from package.json) are not deletable here —
        // remove the script from its source file instead.
        if (!readOnly) {
            OutlinedButton(onClick = onDelete) { Text("✕") }
        }
    }
}

@Composable
private fun LauncherAddDialog(
    existingNames: Set<String>,
    onSubmit: (Launcher) -> Unit,
    onCancel: () -> Unit,
) {
    val nameState = rememberTextFieldState()
    val commandState = rememberTextFieldState()
    val nameText = nameState.text.toString().trim()
    val commandText = commandState.text.toString().trim()
    val nameClash = nameText in existingNames
    val canSubmit = canAddLauncher(nameText, commandText, existingNames)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    // Reads the fields afresh rather than using the values above. The `::submit` handed to
    // DialogFrame for Enter keeps the values from the dialog's first composition, when both fields
    // were empty, so a submit that trusted them could never be made from the keyboard.
    fun submit() {
        val name = nameState.text.toString().trim()
        val command = commandState.text.toString().trim()
        if (canAddLauncher(name, command, existingNames)) onSubmit(Launcher(name, command))
    }

    // A window rather than a centred popup: the middle of nop's window is often the tool region,
    // and a terminal there — an agent session, most of the time — is drawn over any popup. All
    // that showed of this one was whatever overhung the editor, and since it still held the
    // keyboard and ignored clicks outside it, a dialog hidden entirely behind the terminal left
    // the window looking frozen. See [DialogFrame].
    DialogFrame(
        title = "Add launcher",
        onClose = onCancel,
        size = DpSize(420.dp, 270.dp),
        onSubmit = ::submit,
    ) {
        Text("Add launcher", fontWeight = FontWeight.SemiBold)
        LabeledField("Name", nameState, Modifier.focusRequester(focus))
        LabeledField("Command", commandState)
        if (nameClash) Text("A launcher with that name already exists", color = ChangeColors.REMOVED)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            DefaultButton(onClick = ::submit, enabled = canSubmit) { Text("Add") }
        }
    }
}

private fun canAddLauncher(name: String, command: String, existingNames: Set<String>): Boolean =
    name.isNotEmpty() && command.isNotEmpty() && name !in existingNames &&
        '\t' !in name && '\n' !in name && '\n' !in command

@Composable
private fun LabeledField(label: String, state: TextFieldState, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label)
        TextField(state = state, modifier = modifier.fillMaxWidth())
    }
}

/** Positions a popup directly below the anchor, left-aligned. */
private val BelowAnchorProvider: PopupPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.left).coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val y = (anchorBounds.bottom).coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
