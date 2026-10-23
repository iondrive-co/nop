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
import iondrive.nop.update.SelfUpdate
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
    /** An update downloaded in the background, which the restart may start — see [SelfUpdate]. */
    update: SelfUpdate.Status.Ready? = null,
    /** What nop starts again from: the build on disk, or the release `nop --restart --release` named. */
    into: String = "the build now on disk",
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
                "nop starts again from $into and puts every window back. The agents " +
                    "and terminals in them keep running through it, mid-turn or not.",
            )
        } else {
            Text(
                "Every agent tab and terminal stops, and nop starts again from $into. " +
                    "It puts every tab back, and each agent carries on from its own transcript.",
            )
            if (working.isNotEmpty()) {
                Text("Cut off mid-turn: ${quoted(working)}.", color = ChangeColors.MODIFIED)
            }
            if (asking.isNotEmpty()) {
                Text("Waiting on a question, which is lost: ${quoted(asking)}.", color = ChangeColors.REMOVED)
            }
        }
        if (update != null) {
            Text(
                if (update.atRestart) {
                    "It comes back as ${update.version}, the update downloaded in the background."
                } else {
                    "${update.version} is downloaded, but cannot take over running terminals: it starts the next " +
                        "time nop is started, not at this restart."
                },
                fontWeight = if (update.atRestart) FontWeight.SemiBold else FontWeight.Normal,
            )
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
