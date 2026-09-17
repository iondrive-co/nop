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
import iondrive.nop.ShortPath
import iondrive.nop.agent.Account
import iondrive.nop.agent.CliTools
import iondrive.nop.Ago
import iondrive.nop.agent.DEFAULT_CHOICE
import iondrive.nop.agent.handoverTarget
import iondrive.nop.agent.PastSession
import iondrive.nop.agent.UsageReading
import java.nio.file.Path
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text

/** The grey secondary text uses throughout the agent panels. */
internal val AgentMuted = ChangeColors.UNTRACKED

/**
 * What the Agent tab shows with no session open: the accounts you can launch.
 *
 * It is a picker rather than an empty panel because the choice it asks for is the one that matters
 * — which account, and so which quota and which model the next hour of work comes out of.
 *
 * It names [projectDir] as well, because there is a second choice being made here that nobody is
 * asked about: a session starts in whichever project tab happens to be in front. Get that wrong and
 * the CLI comes up in the wrong checkout and works across the boundary into the right one, which
 * looks like nothing at all until you go back to the project you thought you were in.
 */
@Composable
fun AgentPicker(
    accounts: List<Account>,
    readings: Map<String, UsageReading>,
    sessions: List<PastSession>,
    /** Where launching or resuming from here runs the CLI — this project's repository root. */
    projectDir: Path,
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
        // Above the rows rather than below them: it qualifies every launch on this screen, and a
        // footnote under the list is read after the click it was meant to inform. It is also what
        // makes "Earlier sessions here" mean something — "here" is this directory.
        Text(
            "Sessions start in ${ShortPath.of(projectDir, max = PICKER_PATH_MAX)}",
            color = AgentMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
                    // Resolved against the configured accounts rather than taken off the account
                    // itself, so a nomination naming one that has since been removed says nothing
                    // here — exactly as it will do nothing when the wall is hit.
                    handsOverTo = accounts.handoverTarget(account)?.name,
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
                key(past.sessionId) {
                    PastSessionRow(
                        past = past,
                        // The accounts decide this, not the row: a session names the account it
                        // ran under, and whether nop can still run that account is a question
                        // about the settings rather than about the session.
                        account = past.accountIn(accounts),
                        onReopen = { onReopen(past) },
                    )
                }
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

/**
 * How much path the picker spells out before it starts dropping leading directories. Longer than
 * [ShortPath.DEFAULT_MAX] because this line has the panel's whole width to itself, where the session
 * bar's copy shares a row with an account name and two controls.
 */
private const val PICKER_PATH_MAX = 44

/** One earlier session in this project: what it was about, and the account that was doing it. */
@Composable
private fun PastSessionRow(past: PastSession, account: Account?, onReopen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Only a session nop can actually get back into — the vendor left a transcript that is still
    // there, and there is something to run it as. Without both, the row would promise to pick the
    // work back up and instead fail at the CLI, or do nothing whatsoever. See PastSession.accountIn.
    val resumable = account != null

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
private fun AccountRow(
    account: Account,
    reading: UsageReading?,
    /** Who takes over when this one runs out, or null when the choice is left to the user. */
    handsOverTo: String?,
    onLaunch: () -> Unit,
) {
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
        // And where the hour after that comes from, when it has been decided in advance. Only when
        // it has: a line on every row saying nobody is nominated would be the settings dialog's
        // list printed into the picker, where the answer is almost always "nobody" and says nothing.
        handsOverTo?.let { Text("hands over to $it when it runs out", color = AgentMuted) }
        if (cli == null) {
            val install = CliTools.installCommand(account.provider)
            Text(
                if (install != null) {
                    "${account.provider.binary} is not installed — run `$install`"
                } else {
                    // Nothing to paste for this one: see CliTools.installCommand.
                    "${account.provider.binary} is not installed — get it from " +
                        CliTools.installPage(account.provider)
                },
                color = ChangeColors.REMOVED,
            )
        }
    }
}

