package iondrive.nop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * The question a restart puts to the user: an agent wants nop restarted (`nop --restart`), usually to
 * load a build it has just made, or the user pressed the bar's restart button while something would
 * be lost by it. Nothing restarts until they say so.
 *
 * [asker] is the title of the agent tab that asked, when it was one of nop's; [byUser] says the user
 * asked instead. [carriesOver] is whether this system can hand the agents and terminals over to the
 * next nop still running (see [iondrive.nop.ipc.Handover]). When it can, the only thing a restart
 * costs is [stopping], the launcher runs, which come back waiting to be run again. When it cannot,
 * every tab stops, and [working] and [asking] are the agents that would be cut off: mid-turn, or
 * holding a question that is not in the transcript until it is answered, so a restart loses it.
 *
 * Escape is "not now", and Enter does nothing: a restart is not something to agree to by accident.
 */
@Composable
fun RestartDialog(
    asker: String?,
    byUser: Boolean,
    carriesOver: Boolean,
    stopping: List<String>,
    working: List<String>,
    asking: List<String>,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
) {
    DialogFrame(title = "Restart nop?", onClose = onCancel, size = DpSize(460.dp, Dp.Unspecified)) {
        if (!byUser) {
            Text(
                (asker?.let { "The agent in “$it”" } ?: "An agent") + " asks to restart nop.",
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (carriesOver) {
            Text(
                "nop starts again from the build now on disk and puts every window back. The agents " +
                    "and terminals in them keep running through it, mid-turn or not.",
            )
        } else {
            Text(
                "Every agent tab and terminal stops, and nop starts again from the build now on disk. " +
                    "It puts every tab back, and each agent carries on from its own transcript.",
            )
            if (working.isNotEmpty()) {
                Text("Cut off mid-turn: ${quoted(working)}.", color = ChangeColors.MODIFIED)
            }
            if (asking.isNotEmpty()) {
                Text("Waiting on a question, which is lost: ${quoted(asking)}.", color = ChangeColors.REMOVED)
            }
        }
        if (stopping.isNotEmpty()) {
            Text("Stopped, and put back ready to run again: ${quoted(stopping)}.", color = ChangeColors.MODIFIED)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(onClick = onCancel) { Text("Not now") }
            DefaultButton(onClick = onRestart) { Text("Restart") }
        }
    }
}

private fun quoted(titles: List<String>): String = titles.joinToString(", ") { "“$it”" }
