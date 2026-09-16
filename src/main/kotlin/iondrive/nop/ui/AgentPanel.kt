package iondrive.nop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import iondrive.nop.agent.Account
import iondrive.nop.agent.AgentSessions
import iondrive.nop.agent.Handoff
import iondrive.nop.agent.PastSession
import iondrive.nop.agent.UsageReading
import javax.swing.JPanel

/**
 * The Agent tool tab: the picker, or the vendor TUI of whichever session the strip has selected.
 *
 * While a session is running the panel is the terminal and only the terminal — no header, no status
 * line, no controls of nop's own. The CLI in it draws a full-screen UI with its own keybindings, and
 * anything nop puts over that is something the user has to work around. What nop knows about the
 * session comes off the CLI's transcript on disk instead, on a channel that never touches the
 * screen.
 *
 * The one exception is when the run has ended, and then only because there is nothing left to get in
 * the way of: the dead TUI's last frame stays on screen beneath the post-exit choices, because it is
 * usually what tells you which of them to take.
 *
 * The choices sit *above* the terminal rather than floating over it, which is not a preference. A
 * terminal is a heavyweight AWT component and Compose Desktop composites those above everything it
 * draws itself, so a panel overlaid on one is simply not on screen. Giving it a row of its own and
 * letting the terminal shrink is the only arrangement where both are actually visible.
 */
@Composable
fun AgentPanel(
    state: AgentSessions,
    accounts: List<Account>,
    readings: Map<String, UsageReading>,
    sessions: List<PastSession>,
    cards: JPanel,
    onLaunch: (Account) -> Unit,
    onReopen: (PastSession) -> Unit,
    onSettings: () -> Unit,
) {
    val selected = state.selected
    if (selected == null) {
        AgentPicker(
            accounts = accounts,
            readings = readings,
            sessions = sessions,
            onLaunch = onLaunch,
            onReopen = onReopen,
            onSettings = onSettings,
        )
        return
    }

    // Dismissed per ending, not per session: closing the panel after one run must not suppress it
    // for the next, which after a switch is a different provider entirely.
    var dismissedAt by remember(selected.sessionId) { mutableStateOf(0L) }
    val showExit = selected.ended && selected.endedAt > dismissedAt

    Column(modifier = Modifier.fillMaxSize()) {
        // One slim row above the TUI: which account is running, and how to hand the work to another
        // without waiting for this one to end. Everything else about the panel is still terminal and
        // only terminal — but "I am in codex and want to be in Claude" had no answer at all before,
        // short of quitting the CLI to reach the post-exit choices.
        AgentSessionBar(
            session = selected,
            accounts = accounts,
            readings = readings,
            onHandOver = { account -> selected.handOver(account) },
        )
        if (showExit) {
            AgentExitPanel(
                session = selected,
                accounts = accounts,
                readings = readings,
                // Read from the log as it actually stands, so the warning describes this run rather
                // than an assumption about the provider.
                fromScreenOnly = Handoff.fromScreenOnly(selected.log.events()),
                onReopen = {
                    dismissedAt = selected.endedAt
                    selected.reopen()
                },
                onStartFresh = {
                    dismissedAt = selected.endedAt
                    selected.startFresh()
                },
                onSwitch = { account ->
                    dismissedAt = selected.endedAt
                    selected.handOver(account)
                },
                onClose = { dismissedAt = selected.endedAt },
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            TerminalView(selected, cards)
        }
    }
}
