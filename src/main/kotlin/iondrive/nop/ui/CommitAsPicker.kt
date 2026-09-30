package iondrive.nop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import iondrive.nop.git.CommitIdentity
import iondrive.nop.git.IdentityChoice
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip

/** Who a commit goes in as — null for the repository's default — and whether it becomes the repository's own first. */
data class CommitAs(val identity: CommitIdentity?, val save: Boolean)

/** Who the next commit is made as: [picked], or else the repository's default, which [choices] lists first. */
internal fun commitIdentity(choices: List<IdentityChoice>, picked: CommitIdentity?): CommitIdentity? =
    picked ?: choices.firstOrNull()?.identity

/**
 * Whether "Save for this repo" would change anything for [identity]. It would not when that is
 * already the identity the repository's own config sets. A default found in the global config can
 * still be saved, which keeps this repository on it when the global one changes.
 */
internal fun canSaveIdentity(choices: List<IdentityChoice>, identity: CommitIdentity?): Boolean {
    if (identity == null) return false
    val default = choices.firstOrNull() ?: return true
    return !(default.identity == identity && default.source == IdentityChoice.REPO)
}

/**
 * The commit panel's "As <identity> ▾" dropdown and the "Save for this repo" tick beside it. The
 * two are separate items of the caller's row, so in a narrow panel the tick wraps whole onto the
 * next line instead of squeezing its label onto two.
 *
 * [choices] come from [iondrive.nop.git.GitRepo.commitIdentities], the repository's default first;
 * picking that one hands [onPick] null, so the panel follows the default if the config changes.
 * "Someone else…" asks for a name and email. [onOpen] is called as the list opens, for a caller to
 * re-read the config a terminal may have changed since.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CommitAsPicker(
    choices: List<IdentityChoice>,
    picked: CommitIdentity?,
    onPick: (CommitIdentity?) -> Unit,
    save: Boolean,
    onSaveChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var entering by remember { mutableStateOf(false) }
    val current = commitIdentity(choices, picked)
    // A typed identity is found nowhere, so it is listed while it is the pick and not after.
    val rows = if (picked != null && choices.none { it.identity == picked }) {
        choices + IdentityChoice(picked, IdentityChoice.ENTERED)
    } else {
        choices
    }
    val source = rows.firstOrNull { it.identity == current }?.source

    PanelDropdown(
        expanded = expanded,
        onDismiss = { expanded = false },
        maxWidth = 520.dp,
        anchor = {
            Tooltip(tooltip = { Text(if (current == null) "Who this commit is made as" else "Commits as $current ($source)") }) {
                OutlinedButton(onClick = {
                    if (!expanded) onOpen()
                    expanded = !expanded
                }) {
                    Text(
                        "As ${current ?: "…"} ▾",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 280.dp),
                    )
                }
            }
        },
    ) { width ->
        val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
        Column(
            modifier = Modifier
                .width(width)
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(JewelTheme.globalColors.panelBackground)
                .border(1.dp, border, RoundedCornerShape(6.dp))
                .padding(vertical = 4.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            for (choice in rows) {
                IdentityRow(choice, chosen = choice.identity == current) {
                    expanded = false
                    onPick(if (choice == choices.firstOrNull()) null else choice.identity)
                }
            }
            MenuRow("Someone else…") {
                expanded = false
                entering = true
            }
        }
    }
    val savable = canSaveIdentity(choices, current)
    Tooltip(tooltip = { Text("Also set user.name and user.email in this repository's git config, for every later commit here") }) {
        CheckboxRow(
            text = "Save for this repo",
            checked = save && savable,
            onCheckedChange = onSaveChange,
            enabled = savable,
        )
    }

    if (entering) {
        CommitIdentityDialog(
            onUse = {
                entering = false
                onPick(it)
            },
            onCancel = { entering = false },
        )
    }
}

@Composable
private fun IdentityRow(choice: IdentityChoice, chosen: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            choice.identity.toString(),
            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(choice.source, color = ChangeColors.UNTRACKED, maxLines = 1)
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Asks for the name and email of an identity none of the listed ones is. */
@Composable
private fun CommitIdentityDialog(onUse: (CommitIdentity) -> Unit, onCancel: () -> Unit) {
    val name = rememberTextFieldState()
    val email = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun submit() {
        val n = name.text.toString().trim()
        val e = email.text.toString().trim()
        error = identityError(n, e)
        if (error == null) onUse(CommitIdentity(n, e))
    }

    DialogFrame(title = "Commit as someone else", onClose = onCancel, size = DpSize(420.dp, Dp.Unspecified), onSubmit = ::submit) {
        Text("Commit as someone else", fontWeight = FontWeight.SemiBold)
        TextField(
            state = name,
            placeholder = { Text("Name") },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        TextField(
            state = email,
            placeholder = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = ChangeColors.REMOVED) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            DefaultButton(onClick = ::submit) { Text("Use") }
        }
    }
}

/**
 * Why [name] and [email] cannot make an identity, or null when they can. Angle brackets and line
 * breaks are refused because a commit header uses them to mark where the email starts and ends.
 */
internal fun identityError(name: String, email: String): String? = when {
    name.isEmpty() -> "Give a name"
    email.isEmpty() -> "Give an email address"
    (name + email).any { it in "<>\n\r" } -> "Neither can contain < > or a line break"
    else -> null
}
