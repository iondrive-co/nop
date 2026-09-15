package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import iondrive.nop.agent.Account
import iondrive.nop.agent.AgentSession
import iondrive.nop.agent.EndReason
import iondrive.nop.agent.UsageReading
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * What appears when a run ends: why it ended, and what to do about it.
 *
 * It sits *over* the dead terminal rather than replacing it. The TUI's last frame is usually the
 * thing that answers the question the panel is asking — whether the work is finished, whether it
 * stopped mid-change, whether switching to another provider is worth doing — so covering it up
 * would hide the evidence at exactly the moment it is needed.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AgentExitPanel(
    session: AgentSession,
    accounts: List<Account>,
    readings: Map<String, UsageReading>,
    fromScreenOnly: Boolean,
    onReopen: () -> Unit,
    onSwitch: (Account) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
    val quota = session.run.quota
    val others = accounts.filter { it.name != session.account.name }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(JewelTheme.globalColors.panelBackground)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(headline(session), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Close") }
        }

        if (quota != null) {
            Text(quota.line, color = AgentMuted)
            // The reset, from the poller rather than from the message: the CLI's own wording about
            // when a limit lifts is inconsistent, and the usage API says it exactly.
            readings[session.account.name]?.session?.eta()?.let {
                Text("${session.account.name} resets in $it", color = AgentMuted)
            }
        }

        if (fromScreenOnly) {
            // Said plainly, because it explains a first turn on the new provider that may be worse
            // than it looks: there was no transcript to read, so the summary is built from terminal
            // text and is less complete than a normal handoff.
            Text(
                "No transcript was readable for this run, so a handoff would be built from terminal " +
                    "output alone and will be less complete than usual.",
                color = ChangeColors.CONFLICT,
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultButton(onClick = onReopen) { Text("Reopen ${session.account.name}") }
            others.forEach { account ->
                key(account.name) {
                    OutlinedButton(onClick = { onSwitch(account) }) {
                        Text("Switch to ${account.name}${usageSuffix(readings[account.name])}")
                    }
                }
            }
        }
    }
}

/** Why the run ended, in the terms the user would use about it. */
private fun headline(session: AgentSession): String = when (session.run.endReason) {
    EndReason.Quota -> "${session.account.name} ran out: ${session.run.quota?.kind ?: "usage limit"}"
    EndReason.Killed -> "${session.account.name} was stopped"
    EndReason.Switched -> "${session.account.name} was replaced"
    EndReason.Exited, null -> {
        val code = session.run.session.exitCode
        if (code == null || code == 0) "${session.account.name} exited" else "${session.account.name} exited ($code)"
    }
}

/** How much of the other account is left, so the choice can be made without leaving the panel. */
private fun usageSuffix(reading: UsageReading?): String {
    val window = reading?.session ?: reading?.weekly ?: return ""
    return " · ${window.percent.toInt()}% used"
}
