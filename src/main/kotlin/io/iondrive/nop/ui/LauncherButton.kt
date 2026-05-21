package io.iondrive.nop.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.iondrive.nop.launchers.Launcher
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * Play button + popup that lists the project's launchers and lets the user add new ones.
 * Sits to the right of the "Change…" button in the project panel.
 */
@Composable
fun LauncherButton(
    launchers: List<Launcher>,
    onRun: (Launcher) -> Unit,
    onAdd: (Launcher) -> Unit,
    onDelete: (Launcher) -> Unit,
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
                    onRun = { launcher ->
                        expanded = false
                        onRun(launcher)
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
    onRun: (Launcher) -> Unit,
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
                LauncherRow(launcher = launcher, onRun = { onRun(launcher) }, onDelete = { onDelete(launcher) })
            }
        }
        Spacer(Modifier.height(4.dp))
        DefaultButton(onClick = onAddRequest, modifier = Modifier.fillMaxWidth()) {
            Text("Add launcher…")
        }
    }
}

@Composable
private fun LauncherRow(launcher: Launcher, onRun: () -> Unit, onDelete: () -> Unit) {
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
        OutlinedButton(onClick = onDelete) { Text("✕") }
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
    val canSubmit = nameText.isNotEmpty() && commandText.isNotEmpty() && !nameClash &&
        '\t' !in nameText && '\n' !in nameText && '\n' !in commandText

    Popup(
        popupPositionProvider = CenterPopupProvider,
        onDismissRequest = onCancel,
        // Don't auto-dismiss on click-outside: the same click that opened the dialog by selecting
        // the menu item would otherwise be re-interpreted as an outside click and close us instantly.
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(JewelTheme.globalColors.panelBackground)
                .width(420.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Add launcher")
            LabeledField("Name", nameState)
            LabeledField("Command", commandState)
            if (nameClash) Text("A launcher with that name already exists", color = ChangeColors.REMOVED)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                DefaultButton(
                    onClick = { onSubmit(Launcher(nameText, commandText)) },
                    enabled = canSubmit,
                ) { Text("Add") }
            }
        }
    }
}

@Composable
private fun LabeledField(label: String, state: TextFieldState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label)
        TextField(state = state, modifier = Modifier.fillMaxWidth())
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

/** Centers a popup in the window — used for the add-launcher dialog. */
private val CenterPopupProvider: PopupPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = (windowSize.width - popupContentSize.width) / 2,
        y = (windowSize.height - popupContentSize.height) / 3,
    )
}
