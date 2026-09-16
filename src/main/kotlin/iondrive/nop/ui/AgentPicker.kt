package iondrive.nop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import iondrive.nop.agent.Account
import iondrive.nop.agent.CliTools
import iondrive.nop.Ago
import iondrive.nop.agent.DEFAULT_CHOICE
import iondrive.nop.agent.PastSession
import iondrive.nop.agent.UsageReading
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text

/** The grey secondary text uses throughout the agent panels. */
internal val AgentMuted = ChangeColors.UNTRACKED

/**
 * What the Agent tab shows with no session open: the accounts you can launch.
 *
 * It is a picker rather than an empty panel because the choice it asks for is the one that matters
 * — which account, and so which quota and which model the next hour of work comes out of.
 */
@Composable
fun AgentPicker(
    accounts: List<Account>,
    readings: Map<String, UsageReading>,
    sessions: List<PastSession>,
    onLaunch: (Account) -> Unit,
    onReopen: (PastSession) -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Accounts", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Link("Settings", onClick = onSettings)
        }
        Spacer(Modifier.height(6.dp))

        if (accounts.isEmpty()) {
            Text(
                "No agent accounts yet. Settings adds one and signs it in through the vendor's own " +
                    "login — nop never reads the credentials, it only points the CLI at them.",
            )
        }

        accounts.forEach { account ->
            key(account.name) {
                AccountRow(
                    account = account,
                    reading = readings[account.name],
                    onLaunch = { onLaunch(account) },
                )
            }
        }

        if (sessions.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Earlier sessions here", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            // The sessions that are *not* in the strip: one still open there is reached by its own
            // tab, and listing it here as well would offer to resume a conversation that is already
            // running, in a second tab beside it. Reopening one of these resumes the vendor's own
            // session rather than replaying a summary of it, so nothing is lost by closing a tab —
            // which is what makes this list, and not the strip, the thing that has to be reachable
            // while a session is running.
            //
            // Capped, because this list stopped being short the moment it started including the
            // sessions nop did not run: two checkouts on the machine this was written on had 199
            // between them. What a picker is for is getting back into work from the last day or
            // two, and a row five hundred deep is found by searching, which this is not.
            sessions.take(EARLIER_SESSIONS_SHOWN).forEach { past ->
                key(past.sessionId) { PastSessionRow(past = past, onReopen = { onReopen(past) }) }
            }
            val hidden = sessions.size - EARLIER_SESSIONS_SHOWN
            if (hidden > 0) {
                Spacer(Modifier.height(4.dp))
                Text("and $hidden older", color = AgentMuted)
            }
        }
    }
}

/**
 * How many earlier sessions the picker lists. See the comment at the call site for why there is a
 * limit at all.
 */
private const val EARLIER_SESSIONS_SHOWN = 20

/** One earlier session in this project: what it was about, and the account that was doing it. */
@Composable
private fun PastSessionRow(past: PastSession, onReopen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Only a session whose provider left a resumable id can be reopened natively. Without one the
    // row would promise to pick the work back up and instead start an empty session.
    val resumable = past.lastNativeSessionId != null && past.lastAccount != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .clickable(enabled = resumable, onClick = onReopen)
            .padding(vertical = 5.dp),
    ) {
        Text(
            past.title,
            fontWeight = if (hovered) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Ago.of(past.startedAt), color = AgentMuted)
            past.lastAccount?.let { Text(it, color = AgentMuted) }
            if (!resumable) Text("nothing to resume", color = AgentMuted)
        }
    }
}

/**
 * One launchable account. The whole row is the button: starting a session is what you came here to
 * do, so a control hidden inside the row would be ceremony around its only action.
 */
@Composable
private fun AccountRow(account: Account, reading: UsageReading?, onLaunch: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Looked up on composition rather than cached for the session: a CLI installed while nop was
    // open should stop the row claiming it is missing, without needing a restart.
    val cli = CliTools.locate(account.provider)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .clickable(enabled = cli != null, onClick = onLaunch)
            .padding(vertical = 5.dp),
    ) {
        Text(
            account.name,
            fontWeight = if (hovered) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Two lines, not one wrapping row: the tool panel is a few hundred pixels wide, and
        // provider, model and both usage windows on one line reflowed into four ragged lines that
        // read as one paragraph rather than as two facts about an account.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(account.provider.label, color = AgentMuted)
            Text(account.model ?: DEFAULT_CHOICE, color = AgentMuted)
            account.reasoning?.takeIf { it != DEFAULT_CHOICE }?.let { Text(it, color = AgentMuted) }
        }
        // The same numbers as the corner strip, spelled out: choosing which account to spend the
        // next hour on is exactly the decision this row is asking about.
        Text(usageLine(reading), color = AgentMuted)
        if (cli == null) {
            Text(
                "${account.provider.binary} is not installed — run " +
                    "`${CliTools.installCommand(account.provider)}`",
                color = ChangeColors.REMOVED,
            )
        }
    }
}

