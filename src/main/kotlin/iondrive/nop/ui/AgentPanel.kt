package iondrive.nop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import java.nio.file.Path
import javax.swing.JPanel

/**
 * The agent picker, or the selected conversation's live terminal or static reading view.
 * Controls sit above the terminal because its heavyweight AWT component covers Compose overlays.
 * Removing its SwingPanel from the composition leaves the widget and its process in [cards].
 */
@Composable
fun AgentPanel(
    state: AgentSessions,
    accounts: List<Account>,
    readings: Map<String, UsageReading>,
    sessions: List<PastSession>,
    /** The directory a session launched or resumed from here runs in. See [AgentPicker]. */
    projectDir: Path,
    cards: JPanel,
    modelsFor: (Account) -> List<String> = { it.provider.fallbackModels },
    onUpdateAccount: ((Account) -> Unit)? = null,
    onRemoveAccount: ((Account) -> Unit)? = null,
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
            projectDir = projectDir,
            modelsFor = modelsFor,
            onUpdateAccount = onUpdateAccount,
            onRemoveAccount = onRemoveAccount,
            onLaunch = onLaunch,
            onReopen = onReopen,
            onSettings = onSettings,
        )
        return
    }

    // Dismissed per ending, not per session: dismissing the exit choices on a reopen, a fresh
    // start or a switch must not suppress them for the next run, which after a switch is a
    // different provider entirely.
    var dismissedAt by remember(selected.sessionId) { mutableStateOf(0L) }
    val showExit = selected.ended && selected.endedAt > dismissedAt
    LaunchedEffect(selected, selected.summary.shown) {
        if (!selected.summary.shown) selected.session.requestFocus()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AgentSessionBar(
            session = selected,
            accounts = accounts,
            readings = readings,
            modelsFor = modelsFor,
            onUpdateAccount = onUpdateAccount,
            onHandOver = { draft -> selected.handOver(draft.target, draft = draft) },
        )
        // What other agents have sent this one, held until the user delivers or discards it.
        AgentInbox(selected)
        if (showExit) {
            AgentExitPanel(
                session = selected,
                accounts = accounts,
                readings = readings,
                // Read from the log as it actually stands, so the warning describes this run rather
                // than an assumption about the provider.
                fromScreenOnly = Handoff.fromScreenOnly(selected.log.events()),
                modelsFor = modelsFor,
                onUpdateAccount = onUpdateAccount,
                onReopen = {
                    dismissedAt = selected.endedAt
                    selected.reopen()
                },
                onStartFresh = {
                    dismissedAt = selected.endedAt
                    selected.startFresh()
                },
                onSwitch = { draft ->
                    dismissedAt = selected.endedAt
                    selected.handOver(draft.target, draft = draft)
                },
                onClose = { state.close(selected.sessionId) },
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            if (selected.summary.shown) {
                AgentSummaryView(selected.summary, selected.log.file)
            } else {
                TerminalView(selected, cards)
            }
        }
    }
}
