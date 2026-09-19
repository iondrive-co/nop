package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import org.jetbrains.jewel.ui.theme.defaultTabStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import iondrive.nop.agent.Account
import iondrive.nop.agent.AgentConfig
import iondrive.nop.agent.Accounts
import iondrive.nop.agent.DEFAULT_CHOICE
import iondrive.nop.agent.Login
import iondrive.nop.agent.Provider
import iondrive.nop.agent.UsageReading
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * nop's only settings window, and it is about accounts alone.
 *
 * Theme and word wrap keep their own toggles in the chrome. Building nop's first general settings
 * window is a larger piece of work than this feature should drag in, and no second section is
 * asking for one — so this dialog stays what it is: the list of vendor accounts, what each one runs
 * as, and how to sign one in or out.
 *
 * Nothing gates it. chad asked for a password at startup, but that password unlocked nothing — it
 * was handed to a function whose own docstring calls it unused, and the one encrypted field was an
 * empty string encrypted with an empty password. A gate here would be weaker still: the vendors'
 * credential files sit on disk for their own CLIs to read, so anyone who can reach this machine can
 * run `claude` in a terminal and be signed in as you. See [AgentConfig].
 */
@Composable
fun AccountsDialog(
    config: AgentConfig,
    readings: Map<String, UsageReading>,
    modelsFor: (Account) -> List<String>,
    onSave: (AgentConfig) -> Unit,
    onLogIn: (Account) -> Unit,
    onLogOut: (Account) -> Unit = { Login.logOut(it) },
    onClose: () -> Unit,
) {
    DialogFrame(title = "Agent accounts", onClose = onClose, size = DpSize(640.dp, 660.dp)) {
        var draft by remember(config) { mutableStateOf(config) }
        var adding by remember { mutableStateOf(false) }

        fun update(next: AgentConfig) {
            draft = next
            onSave(next)
        }

        Text("Agent accounts", fontWeight = FontWeight.SemiBold)

        Column(
            // Takes the slack in the window rather than capping at a height of its own. The pickers
            // on each row open a menu *inside* this window now that it is a window, and a list with
            // room under it is the difference between a menu that drops and one that has to be
            // shoved back up over the row that opened it.
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            draft.accounts.forEach { account ->
                key(account.name) {
                    AccountEditor(
                        account = account,
                        reading = readings[account.name],
                        models = modelsFor(account),
                        others = draft.accounts.map { it.name }.filterNot { it == account.name },
                        onChange = { changed ->
                            update(draft.copy(accounts = draft.accounts.map { if (it.name == account.name) changed else it }))
                        },
                        onLogIn = { onLogIn(account) },
                        onLogOut = {
                            onLogOut(account)
                            // Bounce the draft so the row's "signed in" line re-reads the file.
                            update(draft.copy())
                        },
                        onRemove = {
                            update(
                                draft.copy(
                                    accounts = draft.accounts
                                        .filterNot { it.name == account.name }
                                        // And anybody who was handing their work to it. A nomination
                                        // left pointing at a removed account is a handover that
                                        // silently does not happen at the one moment it was wanted,
                                        // which is worse than never having set it.
                                        .map { if (it.handoverTo == account.name) it.copy(handoverTo = null) else it },
                                ),
                            )
                        },
                    )
                    Divider(orientation = Orientation.Horizontal)
                }
            }
            if (draft.accounts.isEmpty()) {
                Text("Nothing configured yet. Add an account, then sign it in.", color = AgentMuted)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { adding = true }) { Text("Add account") }
            Box(modifier = Modifier.weight(1f))
            DefaultButton(onClick = onClose) { Text("Done") }
        }

        if (adding) {
            AddAccountDialog(
                existing = draft.accounts.map { it.name },
                onAdd = { account ->
                    update(draft.copy(accounts = draft.accounts + account))
                    adding = false
                    onLogIn(account)
                },
                onCancel = { adding = false },
            )
        }

    }
}

/**
 * What the handover picker reads when nobody has been nominated — the behaviour nop has always had,
 * named so it can be chosen rather than only fallen into.
 */
internal const val HANDOVER_ASK: String = "ask me"

/** One account's row: what it runs as, whether it is signed in, and the two things you can do to it. */
@Composable
private fun AccountEditor(
    account: Account,
    reading: UsageReading?,
    models: List<String>,
    /** The other configured accounts, which are what this one may hand its work to. */
    others: List<String>,
    onChange: (Account) -> Unit,
    onLogIn: () -> Unit,
    onLogOut: () -> Unit,
    onRemove: () -> Unit,
) {
    // Whether the account can actually be used, which is not the same as whether its credential
    // file exists: a Claude token sits in that file long after it has died, and an account that
    // reads "signed in" for a month and then fails three minutes into a session is worse than one
    // that admits it. The poller already answered this off the composition thread; the file check
    // is only the stand-in until its first reading lands, because this is drawn on the UI thread
    // and the real answer costs a network round trip.
    val signedIn = when {
        !Login.hasCredentials(account) -> false
        reading?.unavailable in setOf("not signed in", "usage API refused the sign-in") -> false
        else -> true
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                account.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(account.provider.label, color = AgentMuted)
        }
        Text(account.home, color = AgentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChoicePicker(
                label = "Model",
                options = listOf(DEFAULT_CHOICE) + models,
                selected = account.model ?: DEFAULT_CHOICE,
                onSelect = { onChange(account.copy(model = it.takeIf { v -> v != DEFAULT_CHOICE })) },
            )
            ChoicePicker(
                label = "Thinking",
                options = listOf(DEFAULT_CHOICE) + account.provider.reasoningLevels,
                selected = account.reasoning ?: DEFAULT_CHOICE,
                onSelect = { onChange(account.copy(reasoning = it.takeIf { v -> v != DEFAULT_CHOICE })) },
            )
        }
        // A row of its own rather than a third control beside the two above: those two say what this
        // account runs as, and this says what happens when it stops being able to. The account
        // itself is not in the list — starting the exhausted account again is the one move that
        // certainly does not help, and offering it would be offering a loop.
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChoicePicker(
                label = "When it runs out switch to:",
                options = listOf(HANDOVER_ASK) + others,
                selected = account.handoverTo?.takeIf { it in others } ?: HANDOVER_ASK,
                onSelect = { onChange(account.copy(handoverTo = it.takeIf { v -> v != HANDOVER_ASK })) },
            )
        }
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (signedIn && reading != null && (reading.session != null || reading.weekly != null)) {
                UsageBar("session", reading.session)
                UsageBar("week", reading.weekly)
            }
            Text(
                if (signedIn) signedInLine(reading) else reading?.unavailable ?: "not signed in",
                color = if (signedIn) AgentMuted else ChangeColors.REMOVED,
            )
            Link(if (signedIn) "Sign in again" else "Sign in", onClick = onLogIn)
            if (signedIn) Link("Sign out", onClick = onLogOut)
            Link("Remove", onClick = onRemove)
        }
    }
}

/** An account's quota, or a status while the first reading is still on its way. */
private fun signedInLine(reading: UsageReading?): String =
    if (reading == null) "signed in · checking usage…" else usageLine(reading)

/**
 * A labelled picker over a short list of strings.
 *
 * Built from a `Popup` and a drawn chevron rather than Jewel's `Dropdown`, which renders its
 * chevron from an icon resource that does not resolve in this app — it came out as the magenta
 * square Jewel uses to mark a missing icon. Everything else in nop's chrome draws its own glyphs
 * for the same reason, so this follows suit rather than being the one control that depends on
 * theme resources being present.
 */
@Composable
private fun ChoicePicker(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val tint = if (JewelTheme.isDark) ProjectIconTintDark else ProjectIconTintLight

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AgentMuted)
        Box {
            OutlinedButton(onClick = { open = !open }) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selected,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                    Canvas(Modifier.size(9.dp)) { drawDisclosure(tint, collapsed = false) }
                }
            }
            if (open) {
                Popup(
                    popupPositionProvider = BelowAnchorProvider,
                    onDismissRequest = { open = false },
                    // Same reason LauncherButton does this: with dismissOnClickOutside the press
                    // that opens the popup is re-delivered to it as an outside click and shuts it
                    // again. It closes on a choice, or on the button.
                    properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
                ) {
                    ChoiceMenu(options = options, selected = selected) { choice ->
                        open = false
                        onSelect(choice)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceMenu(options: List<String>, selected: String, onPick: (String) -> Unit) {
    val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
    Column(
        modifier = Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(JewelTheme.globalColors.panelBackground)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
            .padding(4.dp),
    ) {
        options.forEach { option ->
            key(option) {
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                Text(
                    if (option == selected) "✓ $option" else "   $option",
                    fontWeight = if (option == selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(min = 160.dp, max = 280.dp)
                        .hoverable(interaction)
                        .background(
                            if (hovered) {
                                JewelTheme.defaultTabStyle.colors.backgroundHovered
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable { onPick(option) }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** Puts a popup directly under the control that opened it. */
private val BelowAnchorProvider: PopupPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        y = anchorBounds.bottom.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}

/**
 * Adding an account: a name and a provider. The credential directory is derived from the name and
 * created by the login that follows, so there is nothing else to ask for.
 */
@Composable
private fun AddAccountDialog(existing: List<String>, onAdd: (Account) -> Unit, onCancel: () -> Unit) {
    val name = rememberTextFieldState("")
    var provider by remember { mutableStateOf(Provider.Anthropic) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun submit() {
        val trimmed = name.text.toString().trim()
        error = when {
            trimmed.isEmpty() -> "Give the account a name"
            // The name becomes a directory, so anything that would escape it is refused here
            // rather than producing a home somewhere surprising.
            !trimmed.matches(Regex("[A-Za-z0-9 ._-]+")) -> "Letters, digits, spaces, dots, dashes and underscores only"
            trimmed in existing -> "There is already an account called that"
            else -> null
        }
        if (error == null) {
            onAdd(Account(trimmed, provider, Accounts.defaultHome(trimmed).toString()))
        }
    }

    DialogFrame(
        title = "Add an account",
        onClose = onCancel,
        size = DpSize(420.dp, 300.dp),
        onSubmit = ::submit,
    ) {
        Text("Add an account", fontWeight = FontWeight.SemiBold)
        TextField(state = name, modifier = Modifier.fillMaxWidth().focusRequester(focus))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Provider", color = AgentMuted)
            ChoicePicker(
                label = "",
                options = Provider.entries.map { it.label },
                selected = provider.label,
                onSelect = { chosen -> Provider.entries.firstOrNull { it.label == chosen }?.let { provider = it } },
            )
        }
        error?.let { Text(it, color = ChangeColors.REMOVED) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            DefaultButton(onClick = ::submit) { Text("Add and sign in") }
        }
    }
}
