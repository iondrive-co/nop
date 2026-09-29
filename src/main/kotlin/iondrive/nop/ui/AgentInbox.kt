package iondrive.nop.ui

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import iondrive.nop.agent.AgentMessages
import iondrive.nop.agent.AgentSession
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * The messages other agents have sent this tab, each waiting for the user to deliver or discard it.
 *
 * It is the one place such a message can get into an agent: nothing is typed until Deliver is
 * pressed here (see [AgentMessages] for why). So it shows everything the choice rests on — who sent
 * it, and every word that would be typed — and says when the prompt may hold something of the
 * user's own that Enter would send along with it.
 *
 * Above the terminal, not over it, for the reason [AgentPanel] gives: a terminal is a heavyweight
 * component that Compose cannot draw on top of.
 */
@Composable
fun AgentInbox(session: AgentSession, modifier: Modifier = Modifier) {
    val held = session.inbox.toList()
    if (held.isEmpty()) return
    val isDark = JewelTheme.isDark
    val border = if (isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
    val warn = AgentStatusColors(isDark).asking
    // Ticks so a delivered message's "waiting until" line follows the tab as it starts or answers.
    val now = rememberNow(1_000L)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(JewelTheme.globalColors.panelBackground)
            .border(1.dp, warn.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        held.forEach { message ->
            key(message) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Message from tab ${message.from}",
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(agoLabel(now - message.at), color = AgentMuted)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, border, RoundedCornerShape(4.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    ) {
                        SelectionContainer { Text(message.body) }
                    }
                    when {
                        session.ended -> Text("This tab's CLI has ended, so it can only be discarded.", color = AgentMuted)
                        message.approved -> AgentMessages.waitReason(session, now)?.let {
                            Text("Delivering once $it.", color = AgentMuted)
                        }
                        session.run.session.draftPending -> Text(
                            "The prompt may hold text you have not sent. Deliver presses Enter, which would send it " +
                                "as part of this message — send or clear it first.",
                            color = warn,
                        )
                        else -> Text(
                            "Not from you: sent from inside that tab, by its agent or by any command it ran. Deliver " +
                                "types it into this agent's prompt, headed with who sent it, and submits it.",
                            color = AgentMuted,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (message.approved) {
                            OutlinedButton(onClick = { AgentMessages.hold(message) }) { Text("Hold back") }
                        } else {
                            DefaultButton(onClick = { AgentMessages.deliver(session, message) }, enabled = !session.ended) {
                                Text("Deliver")
                            }
                        }
                        OutlinedButton(onClick = { AgentMessages.discard(session, message) }) { Text("Discard") }
                    }
                }
            }
        }
    }
}

private fun agoLabel(ms: Long): String = when {
    ms < 60_000 -> "just now"
    ms < 3_600_000 -> "${ms / 60_000} min ago"
    else -> "${ms / 3_600_000} h ago"
}
