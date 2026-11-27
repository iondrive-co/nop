package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import iondrive.nop.Ago
import iondrive.nop.agent.Account
import iondrive.nop.agent.AccountHomes
import iondrive.nop.agent.AgentConfig
import iondrive.nop.agent.AgentMessages
import iondrive.nop.agent.Accounts
import iondrive.nop.agent.Backup
import iondrive.nop.agent.DEFAULT_CHOICE
import iondrive.nop.agent.Login
import iondrive.nop.agent.LocalModel
import iondrive.nop.agent.NetworkShare
import iondrive.nop.agent.Provider
import iondrive.nop.agent.ProjectSync
import iondrive.nop.agent.SyncFailure
import iondrive.nop.agent.SyncLink
import iondrive.nop.agent.SyncRemote
import iondrive.nop.agent.ResetOffer
import iondrive.nop.agent.ResetOutcome
import iondrive.nop.agent.UsageReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import kotlin.time.Duration.Companion.seconds

/**
 * nop's only settings window: the vendor accounts, what each one runs as, how to sign one in or
 * out, and where their sessions are backed up to.
 *
 * Theme and word wrap keep their own toggles in the chrome. The backup is here rather than in a
 * general settings window because what it backs up is these accounts' work.
 *
 * Nothing gates it. A gate here would protect nothing: the vendors' credential files sit on disk for
 * their own CLIs to read, so anyone who can reach the machine can run `claude` in a terminal and be
 * signed in as you. See [AgentConfig].
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
    /** The project the dialog was opened from, whose tab-messaging switch it shows. */
    project: File? = null,
    /** The root of [project]'s repository, which is what is chosen for syncing; [project] when it is not one. */
    syncRoot: File? = project,
    /** A reading taken after a reset was used, for the caller's map that [readings] comes from. */
    onReading: (Account, UsageReading) -> Unit = { _, _ -> },
) {
    DialogFrame(title = "Agent accounts", onClose = onClose, size = DpSize(640.dp, 710.dp)) {
        var draft by remember(config) { mutableStateOf(config) }
        var adding by remember { mutableStateOf(false) }
        var showingUsage by remember { mutableStateOf(false) }
        // What the last move of a home said, and about which account.
        var moveNote by remember { mutableStateOf<Pair<String, String>?>(null) }

        fun update(next: AgentConfig) {
            draft = next
            onSave(next)
        }

        Text("Agent accounts", fontWeight = FontWeight.SemiBold)

        val scroll = rememberScrollState()
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                draft.accounts.forEach { account ->
                    key(account.name, account.provider, account.home) {
                        AccountEditor(
                            account = account,
                            reading = readings[account.name],
                            models = modelsFor(account),
                            others = draft.accounts.map { it.name }.filterNot { it == account.name },
                            handoverModels = draft.accounts.firstOrNull { it.name == account.handoverTo }
                                ?.let(modelsFor).orEmpty(),
                            handoverIsLocal = draft.accounts.any {
                                it.name == account.handoverTo && it.provider == Provider.Local
                            },
                            onChange = { changed ->
                                update(draft.copy(accounts = draft.accounts.map { if (it.name == account.name) changed else it }))
                            },
                            onLogIn = { onLogIn(account) },
                            onLogOut = {
                                onLogOut(account)
                                // Bounce the draft so the row's "signed in" line re-reads the file.
                                update(draft.copy())
                            },
                            offersMove = AccountHomes.offersMove(account),
                            note = moveNote?.takeIf { it.first == account.name }?.second,
                            onMoveHome = {
                                val existed = java.nio.file.Files.exists(account.homePath)
                                moveNote = runCatching { AccountHomes.moveIntoNop(account) }.fold(
                                    onSuccess = { moved ->
                                        update(draft.copy(accounts = draft.accounts.map { if (it.name == account.name) moved else it }))
                                        account.name to if (existed) {
                                            "Moved. The old folder now links to it, for anything still running from there."
                                        } else {
                                            "Its folder was gone, so it has a fresh one there: sign it in again."
                                        }
                                    },
                                    onFailure = { account.name to "Could not move it: ${it.message}" },
                                )
                            },
                            onRemove = { update(draft.withoutAccount(account.name)) },
                            onUseReset = { offer -> useReset(account, offer, onReading) },
                        )
                        Divider(orientation = Orientation.Horizontal)
                    }
                }
                if (draft.accounts.isEmpty()) {
                    Text("Nothing configured yet. Add an account, then sign it in.", color = AgentMuted)
                }
                Panel(modifier = Modifier.padding(vertical = 4.dp), padding = 12.dp) {
                    BackupSection(config = draft, onChange = { update(it) })
                    Divider(orientation = Orientation.Horizontal)
                    SyncSection(config = draft, root = syncRoot, onChange = { update(it) })
                    project?.let {
                        Divider(orientation = Orientation.Horizontal)
                        TabMessagesSection(it)
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(scroll),
                style = NopScrollbarStyle,
                modifier = Modifier.fillMaxHeight(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { adding = true }) { Text("Add account") }
            OutlinedButton(onClick = { showingUsage = true }) { Text("Show usage") }
            Spacer(Modifier.weight(1f))
            DefaultButton(onClick = onClose) { Text("Done") }
        }

        if (adding) {
            AddAccountDialog(
                existing = draft.accounts.map { it.name },
                onAdd = { account ->
                    update(draft.copy(accounts = draft.accounts + account))
                    adding = false
                    if (account.provider != Provider.Local) onLogIn(account)
                },
                onCancel = { adding = false },
            )
        }

        if (showingUsage) {
            AgentUsageDialog(
                accounts = draft.accounts,
                readings = readings,
                onClose = { showingUsage = false },
            )
        }

    }
}

/**
 * What the handover picker reads when nobody has been nominated — the behaviour nop has always had,
 * named so it can be chosen rather than only fallen into.
 */
internal const val HANDOVER_ASK: String = "ask me"

/** The handover picker's choice for staying on the account and resuming it once its window resets. */
internal const val HANDOVER_RESUME: String = "wait, then resume"

/** The handover model picker's choice for running whatever model the account taking over is set to. */
internal const val HANDOVER_OWN_MODEL: String = "its own"

/** One account's row: what it runs as, whether it is signed in, and the two things you can do to it. */
@Composable
private fun AccountEditor(
    account: Account,
    reading: UsageReading?,
    models: List<String>,
    /** The other configured accounts, which are what this one may hand its work to. */
    others: List<String>,
    /** The models the account this one hands its work to can run, offered for that handover. */
    handoverModels: List<String>,
    handoverIsLocal: Boolean,
    onChange: (Account) -> Unit,
    onLogIn: () -> Unit,
    onLogOut: () -> Unit,
    /** Whether the home is outside nop's folder and can be moved into it. See [AccountHomes]. */
    offersMove: Boolean,
    /** What the last attempt to move the home said. */
    note: String?,
    onMoveHome: () -> Unit,
    onRemove: () -> Unit,
    /** Spends one of the account's usage-limit resets and says what happened. See [ResetsPanel]. */
    onUseReset: suspend (ResetOffer) -> ResetOutcome,
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
        if (account.provider == Provider.Local) {
            LocalAccountEditor(account, reading, onChange, onRemove)
            return@Column
        }
        Text(account.home, color = AgentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // A home outside nop's folder holds this account's logins and every conversation it has had,
        // somewhere that does not look like nop's to whoever tidies it up.
        if (offersMove) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Kept outside nop's folder", color = ChangeColors.CONFLICT)
                Link("Move it into nop's folder", onClick = onMoveHome)
            }
        }
        note?.let { Text(it, color = AgentMuted) }
        // Whether there are limits to draw, which then say for themselves what is used; the line
        // beside the links is left to say how old they are, or why there are none.
        val windows = signedIn && reading != null && reading.unavailable == null &&
            (reading.session != null || reading.weekly != null || reading.others.isNotEmpty())
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    !signedIn -> reading?.unavailable ?: "not signed in"
                    windows -> reading?.let(::staleLine) ?: "signed in"
                    else -> signedInLine(reading)
                },
                color = if (signedIn) AgentMuted else ChangeColors.REMOVED,
                modifier = Modifier.weight(1f, fill = false),
            )
            Link(if (signedIn) "Sign in again" else "Sign in", onClick = onLogIn)
            if (signedIn) Link("Sign out", onClick = onLogOut)
            Link("Remove", onClick = onRemove)
        }
        // What the provider's own usage page shows, and the resets it offers, under the account
        // they belong to rather than behind a sign-in in a browser.
        if (windows || (signedIn && reading?.resets != null)) {
            Panel(modifier = Modifier.padding(top = 6.dp), padding = 10.dp) {
                if (windows) LimitsTable(reading, hover = true)
                ResetsPanel(reading?.resets.takeIf { signedIn }, onUse = onUseReset)
            }
        }
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
                options = listOf(HANDOVER_ASK, HANDOVER_RESUME) + others,
                selected = when {
                    account.resumeAfterReset -> HANDOVER_RESUME
                    else -> account.handoverTo?.takeIf { it in others } ?: HANDOVER_ASK
                },
                onSelect = {
                    onChange(
                        account.copy(
                            handoverTo = it.takeIf { v -> v != HANDOVER_ASK && v != HANDOVER_RESUME },
                            // A model belongs to the account it was picked for.
                            handoverModel = account.handoverModel.takeIf { _ -> it == account.handoverTo },
                            resumeAfterReset = it == HANDOVER_RESUME,
                        ),
                    )
                },
            )
            if (!account.resumeAfterReset && account.handoverTo in others && !handoverIsLocal) {
                ChoicePicker(
                    label = "on",
                    options = (listOf(HANDOVER_OWN_MODEL, DEFAULT_CHOICE) + handoverModels).distinct(),
                    selected = account.handoverModel ?: HANDOVER_OWN_MODEL,
                    onSelect = { onChange(account.copy(handoverModel = it.takeIf { v -> v != HANDOVER_OWN_MODEL })) },
                )
            }
        }
    }
}

@Composable
private fun LocalAccountEditor(
    account: Account,
    reading: UsageReading?,
    onChange: (Account) -> Unit,
    onRemove: () -> Unit,
) {
    val saved = account.endpoint ?: LocalModel.DEFAULT_ENDPOINT
    key(saved) {
        val endpoint = rememberTextFieldState(saved)
        var error by remember { mutableStateOf<String?>(null) }
        Text("Server URL", color = AgentMuted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(state = endpoint, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = {
                    runCatching { LocalModel.endpoint(endpoint.text.toString()) }.fold(
                        onSuccess = {
                            error = null
                            onChange(account.copy(endpoint = it))
                        },
                        onFailure = { error = it.message },
                    )
                },
                enabled = endpoint.text.toString() != saved,
            ) { Text("Save") }
        }
        error?.let { Text(it, color = ChangeColors.REMOVED) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(localModelStatus(reading), color = if (reading?.unavailable == null) AgentMuted else ChangeColors.REMOVED,
            modifier = Modifier.weight(1f))
        Link("Remove", onClick = onRemove)
    }
}

internal fun localModelStatus(reading: UsageReading?): String =
    if (reading == null) "Checking server…" else reading.unavailable ?: "Connected"

/**
 * Where the agent sessions are backed up to, and how the last backup went. See [Backup] for what is
 * copied and how.
 */
@Composable
private fun BackupSection(config: AgentConfig, onChange: (AgentConfig) -> Unit) {
    var refused by remember { mutableStateOf<String?>(null) }
    var typingShare by remember { mutableStateOf(false) }
    val share = rememberTextFieldState("smb://")
    val status = Backup.status
    val dir = config.backupDir?.takeIf { it.isNotBlank() }

    // A share is checked when the backup runs, which is where mounting it happens — never on this
    // thread. A new folder is backed up to as soon as it is saved: see [Backup.start].
    fun useShare() {
        val typed = share.text.toString().trim()
        refused = if (NetworkShare.isShare(typed)) null else "Not a share: write it as smb://server/share/folder."
        if (refused == null) {
            typingShare = false
            onChange(config.copy(backupDir = typed))
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Session backup", fontWeight = FontWeight.SemiBold)
        Text(
            dir ?: "Not set up: nothing is backed up.",
            color = if (dir == null) ChangeColors.CONFLICT else AgentMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Link(if (dir == null) "Choose a folder" else "Choose another folder", onClick = {
                val picked = chooseBackupFolder(dir) ?: return@Link
                refused = Backup.refusal(picked, config)?.let { "Not that folder: $it" }
                if (refused == null) {
                    typingShare = false
                    onChange(config.copy(backupDir = picked.toString()))
                }
            })
            Link("Use a network share", onClick = {
                typingShare = !typingShare
                refused = null
            })
            if (dir != null) {
                Link("Back up now", onClick = { Backup.runNow() })
                Link("Stop backing up", onClick = { onChange(config.copy(backupDir = null)) })
            }
        }
        if (typingShare) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    state = share,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("smb://server/share/folder") },
                    onKeyboardAction = { useShare() },
                )
                Link("Use", onClick = ::useShare)
            }
            Text(
                "Uses the share's existing mount, or mounts it the way your file manager does, and asks for " +
                    "its password if it needs one.",
                color = AgentMuted,
            )
        }
        refused?.let { Text(it, color = ChangeColors.REMOVED) }
        val waiting = status.password
        if (dir != null && waiting != null && !status.running) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (waiting.refused) "${waiting.share} did not accept the password." else "Waiting for the password to ${waiting.share}.",
                    color = ChangeColors.CONFLICT,
                )
                Link("Sign in…", onClick = { Backup.askForPassword() })
            }
        } else if (dir != null) {
            val line = when {
                status.running -> "Backing up…"
                status.lastError != null -> "Last backup failed: ${status.lastError}"
                status.lastSuccessAt != null -> "Last backed up ${Ago.of(status.lastSuccessAt)}" +
                    if (status.copiedFiles > 0) " · ${status.copiedFiles} files, ${formatBytes(status.copiedBytes)}" else " · nothing new"
                else -> "Backs up every 10 minutes, and after each run ends."
            }
            Text(line, color = if (status.lastError != null && !status.running) ChangeColors.REMOVED else AgentMuted)
        }
    }
}

/**
 * The machine the chosen projects' agent tabs are kept on over ssh, so that another machine with the
 * same project can show them and take them over, and whether [root] — the repository the dialog was
 * opened from — is one of them. See [ProjectSync] for what is sent and when.
 */
@Composable
private fun SyncSection(config: AgentConfig, root: File?, onChange: (AgentConfig) -> Unit) {
    val remote = config.sync
    var editing by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()
    val target = rememberTextFieldState(remote?.target.orEmpty())
    val identity = rememberTextFieldState(remote?.identity.orEmpty())
    val base = rememberTextFieldState(remote?.base ?: SyncRemote.DEFAULT_BASE)
    val rate = rememberTextFieldState((remote?.rateLimit ?: SyncRemote.DEFAULT_RATE_LIMIT).toString())

    fun typed() = SyncRemote(
        target = target.text.toString().trim(),
        identity = identity.text.toString().trim().ifEmpty { null },
        base = base.text.toString().trim().trimEnd('/').ifEmpty { SyncRemote.DEFAULT_BASE },
        ssh = remote?.ssh,
        // Anything but a whole number is -1, which SyncLink.problem refuses with the reason.
        rateLimit = rate.text.toString().trim().ifEmpty { SyncRemote.DEFAULT_RATE_LIMIT.toString() }.toIntOrNull() ?: -1,
    )

    fun save() {
        val next = typed()
        refused = SyncLink.problem(next)
        if (refused == null) {
            editing = false
            checked = null
            onChange(config.copy(sync = next))
        }
    }

    // Reaching the remote is a network call that can take its full timeout, so it never runs on
    // this thread; rsync is looked for here too, since the remote's answer only covers its own side.
    fun check(candidate: SyncRemote) {
        refused = SyncLink.problem(candidate)
        if (refused != null) return
        checking = true
        checked = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val local = SyncLink.run(listOf("rsync", "--version"), null, 10.seconds)
                    if (local.exit != 0) throw SyncFailure("rsync does not run on this machine (exit ${local.exit})")
                    SyncLink(candidate, null).ping()
                }
            }
            checking = false
            checked = outcome.fold(
                onSuccess = { true to "Reached ${candidate.target}: it has what the sync needs." },
                onFailure = { false to (it.message ?: it.toString()).replaceFirstChar(Char::uppercaseChar) },
            )
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Agent tab sync", fontWeight = FontWeight.SemiBold)
        Text(
            remote?.let { "${it.target}, in ~/${it.base}, ${speed(it.rateLimit)}" }
                ?: "Not set up: each machine keeps its own agent tabs.",
            color = AgentMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Link(if (remote == null) "Set up" else "Change", onClick = {
                    target.replaceWith(remote?.target.orEmpty())
                    identity.replaceWith(remote?.identity.orEmpty())
                    base.replaceWith(remote?.base ?: SyncRemote.DEFAULT_BASE)
                    rate.replaceWith((remote?.rateLimit ?: SyncRemote.DEFAULT_RATE_LIMIT).toString())
                    refused = null
                    checked = null
                    editing = true
                })
                if (remote != null) {
                    Link("Check", enabled = !checking, onClick = { check(remote) })
                    Link("Stop syncing", onClick = {
                        checked = null
                        onChange(config.copy(sync = null))
                    })
                }
            }
        } else {
            Text(
                "The chosen projects' agent tabs and their conversations are kept there, so another machine with the " +
                    "same project can show them and take them over. The code, and every sign-in, stay on each machine.",
                color = AgentMuted,
            )
            SyncField("Remote", target, "user@host, or a host from the ssh config", ::save)
            SyncField("Key file", identity, "Optional: the ssh config's key when empty", ::save)
            SyncField("Folder", base, "Under the remote user's home", ::save)
            SyncField("Speed cap", rate, "KB a second each way; 0 for no cap", ::save)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Link("Save", onClick = ::save)
                Link("Check", enabled = !checking, onClick = { check(typed()) })
                Link("Cancel", onClick = {
                    editing = false
                    refused = null
                    checked = null
                })
            }
            Text(
                "nop never accepts a host key for you, whatever the ssh config says: connect once with ssh from a " +
                    "terminal first. The remote needs flock and rsync, and a key with a passphrase needs to be in an ssh agent.",
                color = AgentMuted,
            )
        }
        if (remote != null && !editing) {
            root?.let {
                CheckboxRow(
                    text = "Sync ${it.name}'s agent tabs",
                    checked = config.syncs(it.toPath()),
                    onCheckedChange = { on -> onChange(config.withSynced(it.toPath(), on)) },
                )
            }
            Text(
                if (config.syncProjects.isEmpty()) {
                    "No project syncs yet. Each machine chooses its own, from each project's settings."
                } else {
                    "Syncing from this machine: " + config.syncProjects.joinToString(", ") { File(it).name } +
                        ". Each machine chooses its own, from each project's settings."
                },
                color = AgentMuted,
            )
        }
        refused?.let { Text(it, color = ChangeColors.REMOVED) }
        when {
            checking -> Text("Checking…", color = AgentMuted)
            else -> checked?.let { (ok, said) -> Text(said, color = if (ok) AgentMuted else ChangeColors.REMOVED) }
        }
        if (remote != null || editing) {
            Text(
                "A change applies to open projects at once, and nothing more is sent to the remote it replaces. " +
                    "Where another machine already holds an open project there, the tabs running here stop and its tabs are shown.",
                color = AgentMuted,
            )
        }
    }
}

@Composable
private fun SyncField(label: String, state: TextFieldState, hint: String, onDone: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(72.dp))
        TextField(
            state = state,
            modifier = Modifier.weight(1f),
            placeholder = { Text(hint) },
            onKeyboardAction = { onDone() },
        )
    }
}

private fun TextFieldState.replaceWith(value: String) = edit { replace(0, length, value) }

/** How the summary line puts [rateLimit], a speed cap in KB a second. */
private fun speed(rateLimit: Int): String = if (rateLimit > 0) "up to $rateLimit KB/s" else "no speed cap"

/**
 * Whether [project]'s agent tabs may message each other without the user delivering each message.
 * Per project, since what makes it safe is that sender and recipient share one: see
 * [AgentMessages.setAutoDeliver].
 */
@Composable
private fun TabMessagesSection(project: File) {
    val on = AgentMessages.autoDelivers(project)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Messages between agent tabs", fontWeight = FontWeight.SemiBold)
        CheckboxRow(
            text = "Deliver messages between ${project.name}'s tabs without asking",
            checked = on,
            onCheckedChange = { AgentMessages.setAutoDeliver(project, it) },
        )
        Text(
            if (on) {
                "Typed straight into the recipient's prompt. Messages from other projects' tabs still wait for you."
            } else {
                "Each message waits in the recipient tab until you press Deliver."
            },
            color = AgentMuted,
        )
    }
}

/** A directory chooser for the backup folder, or null when cancelled. */
private fun chooseBackupFolder(current: String?): Path? {
    val chooser = JFileChooser().apply {
        dialogTitle = "nop — choose where to back up agent sessions"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        currentDirectory = current?.let(::File)?.takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile?.toPath()?.toAbsolutePath()?.normalize()
}

/** A rounded, shaded box that sets a group of rows apart from the list around it. */
@Composable
private fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
    val background = if (JewelTheme.isDark) Color(0xFF26282E) else Color(0xFFF2F4F7)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
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
internal fun ChoicePicker(
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
    val endpoint = rememberTextFieldState(LocalModel.DEFAULT_ENDPOINT)
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
            val home = Accounts.defaultHome(trimmed)
            if (provider == Provider.Local) {
                runCatching { LocalModel.endpoint(endpoint.text.toString()) }.fold(
                    onSuccess = { onAdd(Account(trimmed, provider, home.resolve("local-model").toString(), endpoint = it)) },
                    onFailure = { error = it.message },
                )
            } else {
                onAdd(Account(trimmed, provider, home.toString()))
            }
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
        if (provider == Provider.Local) {
            Text("Server URL", color = AgentMuted)
            TextField(state = endpoint, modifier = Modifier.fillMaxWidth())
        }
        error?.let { Text(it, color = ChangeColors.REMOVED) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            DefaultButton(onClick = ::submit) { Text(if (provider == Provider.Local) "Add" else "Add and sign in") }
        }
    }
}
