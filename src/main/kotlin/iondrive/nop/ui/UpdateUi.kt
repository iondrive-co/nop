package iondrive.nop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.update.SelfUpdate
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

/** What the updater is doing, in a sentence. */
internal fun updateStatusLine(status: SelfUpdate.Status): String = when (status) {
    SelfUpdate.Status.Idle -> "Checks for updates a couple of minutes after nop starts, then every 6 hours."
    is SelfUpdate.Status.Off -> "Not updated from releases: ${status.reason}."
    SelfUpdate.Status.Disabled -> "Automatic updates are off."
    SelfUpdate.Status.Checking -> "Checking for an update…"
    SelfUpdate.Status.Current -> "Up to date."
    is SelfUpdate.Status.Downloading -> {
        val percent = if (status.total > 0) (status.done * 100 / status.total).toInt() else 0
        "Downloading ${status.version}… $percent%"
    }
    is SelfUpdate.Status.Ready -> if (status.atRestart) {
        "${status.version} is ready, and starts when nop restarts. The agents and terminals keep running through it."
    } else {
        "${status.version} is ready. It cannot take over running terminals, so it starts the next time nop is started, " +
            "not at a restart."
    }
    is SelfUpdate.Status.Failed -> "The last check failed: ${status.reason}."
}

/**
 * The running build's version, small, beside the windows button: which nop this is, for anyone
 * comparing it with a release or with what an agent just built. Click for the update settings.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun VersionLabel(onClick: () -> Unit, height: Dp) {
    val tint = if (org.jetbrains.jewel.foundation.theme.JewelTheme.isDark) ProjectIconTintDark else ProjectIconTintLight
    Tooltip(tooltip = { Text("nop ${SelfUpdate.label} — ${updateStatusLine(SelfUpdate.status)}") }) {
        Row(
            modifier = Modifier.height(height).clickable(onClick = onClick).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(SelfUpdate.label, color = tint, fontSize = 10.sp)
        }
    }
}

/**
 * "Update to 1.2.6", beside the restart button, once an update is staged. When a restart starts it,
 * clicking restarts; when only a fresh start can, clicking says so.
 */
@OptIn(ExperimentalJewelApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun UpdateBadge(onRestart: () -> Unit, onDetails: () -> Unit, height: Dp) {
    val ready = SelfUpdate.status as? SelfUpdate.Status.Ready ?: return
    Tooltip(tooltip = { Text(updateStatusLine(ready)) }) {
        Row(
            modifier = Modifier
                .height(height)
                .clickable(onClick = if (ready.atRestart) onRestart else onDetails)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Update to ${ready.version}", color = ChangeColors.ADDED, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * nop's version and its updates: what the updater is doing, whether it checks by itself, a check
 * on demand, and a restart into a staged update.
 */
@Composable
internal fun UpdateDialog(onRestart: (() -> Unit)?, onClose: () -> Unit) {
    val status = SelfUpdate.status
    DialogFrame(title = "nop ${SelfUpdate.label}", onClose = onClose, size = DpSize(440.dp, Dp.Unspecified)) {
        SelfUpdate.rolledBack?.let { version ->
            Text("$version was installed and did not start, so this is the version before it. nop does not try $version again.", color = ChangeColors.MODIFIED)
        }
        Text(updateStatusLine(status))
        if (status !is SelfUpdate.Status.Off) {
            CheckboxRow(
                text = "Check for updates and install them at the next restart",
                checked = SelfUpdate.automatic,
                onCheckedChange = { SelfUpdate.changeAutomatic(it) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            if (status !is SelfUpdate.Status.Off) {
                OutlinedButton(
                    onClick = { SelfUpdate.checkNow() },
                    enabled = status !is SelfUpdate.Status.Checking && status !is SelfUpdate.Status.Downloading,
                ) { Text("Check now") }
            }
            if (status is SelfUpdate.Status.Ready && status.atRestart && onRestart != null) {
                DefaultButton(onClick = {
                    onClose()
                    onRestart()
                }) { Text("Restart to update") }
            } else {
                DefaultButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}
