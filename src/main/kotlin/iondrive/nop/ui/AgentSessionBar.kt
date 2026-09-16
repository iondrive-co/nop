package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import iondrive.nop.agent.Account
import iondrive.nop.agent.AgentSession
import iondrive.nop.agent.UsageReading
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * The one row of nop's own chrome above a running agent TUI.
 *
 * It exists for a question the design had no answer to: you are in a Codex session and want to be
 * in a Claude one, carrying the work across. Handing over used to be reachable only from the
 * post-exit panel, so the only route was to quit the CLI first — and quitting is precisely what you
 * do *not* want to do when the reason to switch is that this provider is going badly.
 *
 * Collapsed it is a single line naming the account. Expanded it lists the others with what each has
 * left, because "which one instead" is the same question the usage strip answers. The list is drawn
 * inline rather than in a popup: the terminal below is a heavyweight AWT component, and Compose
 * composites popups underneath it, so a menu opened here would simply not be on screen.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AgentSessionBar(
    session: AgentSession,
    accounts: List<Account>,
    readings: Map<String, UsageReading>,
    onHandOver: (Account) -> Unit,
) {
    val others = accounts.filter { it.name != session.account.name }
    var expanded by remember(session.sessionId) { mutableStateOf(false) }
    // Reset per run, so the confirmation belongs to the command actually on the clipboard rather
    // than to one copied before a handoff changed which account is running.
    var copied by remember(session.id) { mutableStateOf(false) }
    val tint = if (JewelTheme.isDark) ProjectIconTintDark else ProjectIconTintLight

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(JewelTheme.globalColors.panelBackground)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                session.account.name,
                color = AgentMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // The one thing nop cannot do for you, offered as the command that does it. A vendor
            // keeps a session's transcript beside the credentials of the account that wrote it, so
            // a session nop ran under one account is not in the store a bare `claude` reads — and
            // nothing nop does to its own state changes where that CLI looks. See
            // [AgentSession.resumeCommand].
            session.resumeCommand()?.let { command ->
                Text(
                    if (copied) "Copied" else "Copy resume command",
                    color = AgentMuted,
                    modifier = Modifier.clickable {
                        Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(StringSelection(command), null)
                        copied = true
                    },
                )
            }
            if (others.isNotEmpty()) {
                Row(
                    modifier = Modifier.clickable { expanded = !expanded },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Hand over", color = AgentMuted)
                    Canvas(Modifier.size(9.dp)) { drawDisclosure(tint, collapsed = !expanded) }
                }
            }
        }

        if (expanded && others.isNotEmpty()) {
            Text(
                "Ends this run, writes a summary of it, and opens the chosen account on that summary.",
                color = AgentMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                others.forEach { account ->
                    key(account.name) {
                        OutlinedButton(onClick = { expanded = false; onHandOver(account) }) {
                            Text(account.name + usageSuffix(readings[account.name]))
                        }
                    }
                }
            }
            Box(modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/** How much of the other account is left, so the choice needs nothing but this row. */
private fun usageSuffix(reading: UsageReading?): String {
    val window = reading?.session ?: reading?.weekly ?: return ""
    return " · ${window.percent.toInt()}%"
}
