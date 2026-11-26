package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import iondrive.nop.Settings
import iondrive.nop.agent.ConversationSummaryState
import iondrive.nop.agent.ProjectSync
import iondrive.nop.agent.Provider
import iondrive.nop.agent.SyncFiles
import iondrive.nop.agent.SyncView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The agent pane of a project another machine holds: that machine's tabs as it last sent them, each
 * one's conversation readable and none of them running here, and the one way to have them run here
 * instead, which always asks first.
 */
@Composable
internal fun SyncViewerPanel(sync: ProjectSync) {
    val view = sync.view
    var chosen by remember(sync) { mutableStateOf<String?>(null) }
    var confirming by remember(sync) { mutableStateOf(false) }
    val rows = view.rows
    val shown = rows.firstOrNull { it.sessionId == chosen } ?: rows.firstOrNull()
    Column(
        Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)
            .padding(bottom = LocalTerminalBottomInset.current),
    ) {
        SyncBanner(sync, view, onTakeOver = { confirming = true })
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
            ) {
                if (rows.isEmpty()) {
                    Text(
                        if (view.pulled == 0) "Nothing fetched from ${sync.target} yet." else "No agent tabs are open there.",
                        color = AgentMuted,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                rows.forEach { row -> ViewedRow(row, row.sessionId == shown?.sessionId) { chosen = row.sessionId } }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (shown != null) key(shown.sessionId) { ViewedConversation(sync, shown.sessionId, view.pulled) }
            }
        }
    }
    if (confirming) {
        TakeOverDialog(
            sync = sync,
            view = view,
            onConfirm = { onto ->
                confirming = false
                sync.takeOver(onto)
            },
            onCancel = { confirming = false },
        )
    }
}

/** What stands in for the agent pane while the remote is first asked who holds the project. */
@Composable
internal fun SyncStartingPanel(sync: ProjectSync) {
    Box(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground).padding(16.dp)) {
        Text(
            sync.view.error?.let { "Could not reach ${sync.target}: $it" }
                ?: "Checking with ${sync.target} whether another machine holds this project's agents…",
            color = AgentMuted,
        )
    }
}

/**
 * A line over the agent tabs of a synced project this machine runs, shown only when something is
 * worth saying: the remote has not answered yet, or has stopped answering, or a takeover left
 * something behind.
 */
@Composable
internal fun SyncStatusLine(sync: ProjectSync, starting: Boolean) {
    val view = sync.view
    val text = when {
        starting -> "Checking with ${sync.target} whether another machine holds this project's agents…"
        view.pending -> "Running this project's agents here, but ${sync.target} has not been told yet" +
            (view.error?.let { " ($it)" } ?: "") + ", so another machine may be running them too."
        view.error != null -> "Could not reach ${sync.target}: ${view.error}. The tabs here are sent when it answers."
        else -> null
    }
    if (text != null) {
        Text(
            text,
            color = if (view.error != null) ChangeColors.CONFLICT else AgentMuted,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
    view.notice?.let { notice ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Took over. $notice.", color = ChangeColors.CONFLICT, modifier = Modifier.weight(1f))
            Link("Dismiss", onClick = { sync.dismissNotice() })
        }
    }
}

@Composable
private fun SyncBanner(sync: ProjectSync, view: SyncView, onTakeOver: () -> Unit) {
    val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
    val since = sinceCheckIn(view)
    Column(
        Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                view.lease?.let { "${it.host} is running this project's agents" } ?: "Another machine has this project's agents",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            DefaultButton(onClick = onTakeOver, enabled = !view.busy) {
                Text(if (view.busy) "Taking over…" else "Take over")
            }
        }
        view.takenOverBy?.let {
            Text("$it took this project over, so its agents here were stopped.", color = ChangeColors.CONFLICT)
        }
        val lease = view.lease
        if (lease != null && since != null) {
            Text(
                "It last checked in with ${sync.target} ${ago(since)} ago" +
                    (if (since > STALE_SECONDS) ", so it may be off or asleep." else "."),
                color = AgentMuted,
            )
        }
        Text("Its tabs are shown here read-only. Taking over stops them there and starts them here.", color = AgentMuted)
        view.error?.let { Text("Could not reach ${sync.target}: $it", color = ChangeColors.CONFLICT) }
    }
}

@Composable
private fun ViewedRow(row: Settings.OpenAgent, selected: Boolean, onClick: () -> Unit) {
    val highlight = if (JewelTheme.isDark) Color(0xFF2E436E) else Color(0xFFD5E1FF)
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (selected) highlight else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(row.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val provider = Provider.entries.firstOrNull { it.id == row.provider }?.label ?: row.provider
        Text("$provider · ${row.account}", color = AgentMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One tab's conversation as last fetched, read again when a fetch has brought a newer copy of it.
 */
@Composable
private fun ViewedConversation(sync: ProjectSync, sessionId: String, pulled: Int) {
    val fetched by produceState<Fetched?>(null, pulled) {
        value = withContext(Dispatchers.IO) {
            val log = sync.files.viewedLog(sessionId)
            Fetched(log, log?.let { runCatching { Files.size(it) to Files.getLastModifiedTime(it).toMillis() }.getOrNull() })
        }
    }
    val now = fetched ?: return
    val log = now.log
    if (log == null) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            Text("No conversation has been fetched for this tab yet.", color = AgentMuted)
        }
        return
    }
    val state = remember { ConversationSummaryState() }
    val seen = remember { mutableStateOf(now.stamp) }
    LaunchedEffect(now.stamp) {
        if (now.stamp != seen.value) {
            seen.value = now.stamp
            state.refresh()
        }
    }
    AgentSummaryView(state, log, hint = "Take over to type in it")
}

private class Fetched(val log: Path?, val stamp: Pair<Long, Long>?)

/**
 * Asks once, however many tabs are open, before taking the project from the machine that holds it,
 * saying what that machine was working on next to what this checkout is.
 *
 * Tabs that ran on an account this machine has none by the name of are named here, with the choice
 * of a local account to run them on: which account's quota a conversation spends is never picked
 * for the user, so left alone they stay behind.
 */
@Composable
private fun TakeOverDialog(sync: ProjectSync, view: SyncView, onConfirm: (Map<String, String>) -> Unit, onCancel: () -> Unit) {
    val lease = view.lease
    val unmatched by produceState(emptyList<UnmatchedAccount>(), sync, view.pulled) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                sync.files.unmatchedAccounts(view.rows).map { (key, row) ->
                    UnmatchedAccount(key, row, view.rows.count { SyncFiles.accountKey(it) == key }, sync.files.accountsFor(row))
                }
            }.getOrDefault(emptyList())
        }
    }
    val onto = remember(sync) { mutableStateMapOf<String, String>() }
    val here by produceState<Pair<String, Boolean>?>(null, sync) {
        value = withContext(Dispatchers.IO) { runCatching { sync.localHead() to sync.localDirty() }.getOrNull() }
    }
    val holder = lease?.host ?: "the other machine"
    val tabs = view.rows.size
    DialogFrame(title = "Take over this project's agents?", onClose = onCancel, size = DpSize(480.dp, Dp.Unspecified)) {
        Text("Take over this project's agents?", fontWeight = FontWeight.SemiBold)
        val fetched = when (tabs) {
            0 -> "$holder has no agent tabs open, so this machine starts with none, in place of any it had."
            1 -> "The tab $holder has open is fetched and started here, in place of any tabs this machine had."
            else -> "The $tabs tabs $holder has open are fetched and started here, in place of any tabs this machine had."
        }
        Text(
            "$fetched $holder stops its agents and becomes a read-only view the next time it checks in, " +
                "within about a minute if it is running; if it is off, when it next starts.",
        )
        if (lease != null) {
            val theirs = lease.head.take(10).ifEmpty { "unknown" } + if (lease.dirty) ", with uncommitted changes" else ""
            Text("$holder's checkout: $theirs", color = AgentMuted)
        }
        val mine = here
        Text(
            "This checkout: " + when (mine) {
                null -> "reading…"
                else -> mine.first.take(10).ifEmpty { "unknown" } + if (mine.second) ", with uncommitted changes" else ""
            },
            color = AgentMuted,
        )
        if (lease != null && mine != null && lease.head.isNotEmpty() && mine.first.isNotEmpty() && lease.head != mine.first) {
            Text(
                "The two checkouts are at different commits: the agents will carry on here from conversations " +
                    "about the code as it is there.",
                color = ChangeColors.CONFLICT,
            )
        }
        if (lease?.dirty == true) {
            Text("Uncommitted changes there stay there: only the agents' conversations come across.", color = ChangeColors.CONFLICT)
        }
        unmatched.forEach { missing ->
            val what = if (missing.tabs == 1) "1 tab ran" else "${missing.tabs} tabs ran"
            val kind = Provider.entries.firstOrNull { it.id == missing.row.provider }?.label ?: missing.row.provider
            if (missing.options.isEmpty()) {
                Text("$what on ${missing.row.account}, and this machine has no $kind account, so they stay behind.", color = ChangeColors.CONFLICT)
            } else {
                Text("$what on ${missing.row.account}, an account this machine does not have.", color = ChangeColors.CONFLICT)
                ChoicePicker(
                    label = "Run them here on",
                    options = listOf(LEAVE_BEHIND) + missing.options,
                    selected = onto[missing.key] ?: LEAVE_BEHIND,
                    onSelect = { if (it == LEAVE_BEHIND) onto.remove(missing.key) else onto[missing.key] = it },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            DefaultButton(onClick = { onConfirm(onto.toMap()) }) { Text("Take over") }
        }
    }
}

/** An account the holder's tabs ran on that this machine has none by the name of, and what could stand in for it. */
private class UnmatchedAccount(val key: String, val row: Settings.OpenAgent, val tabs: Int, val options: List<String>)

private const val LEAVE_BEHIND = "no account: leave them behind"

/** Seconds since the holder last checked in, counted on from when the remote said, or null when it has not. */
@Composable
private fun sinceCheckIn(view: SyncView): Long? {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            tick++
        }
    }
    @Suppress("UNUSED_VARIABLE") val ticking = tick
    val age = view.age ?: return null
    val checked = view.checked ?: return age
    return age + (Instant.now().epochSecond - checked.epochSecond).coerceAtLeast(0)
}

private fun ago(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 86_400 -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
    else -> "${seconds / 86_400} days"
}

/** Three check-ins missed. */
private const val STALE_SECONDS = 90L
