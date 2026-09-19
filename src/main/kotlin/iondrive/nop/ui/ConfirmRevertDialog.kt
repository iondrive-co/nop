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
import iondrive.nop.git.ChangeKind
import iondrive.nop.git.FileChange
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text

/**
 * Confirms a per-file revert from the change list. Revert is destructive and — unlike a soft reset
 * or a stash — leaves nothing to recover, so we spell out exactly what will happen to this file
 * before doing it. The wording is keyed to the change kind so an untracked-file delete reads
 * differently from a modified-file rollback.
 */
@Composable
fun ConfirmRevertDialog(change: FileChange, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val detail = when (change.kind) {
        ChangeKind.UNTRACKED, ChangeKind.ADDED ->
            "This new file will be deleted from disk. It isn't in any commit, so this can't be undone."
        ChangeKind.REMOVED, ChangeKind.MISSING ->
            "The file will be restored from the last commit."
        ChangeKind.MODIFIED, ChangeKind.CONFLICT ->
            "Local changes will be discarded and the file restored to the last commit."
    }
    ConfirmGitActionDialog(title = "Revert file?", onConfirm = onConfirm, onCancel = onCancel) {
        Text(change.path, color = ChangeColors.UNTRACKED)
        Text(detail, color = ChangeColors.REMOVED)
    }
}

/**
 * Confirms reverting the whole change list ("Revert all"). This throws away every uncommitted
 * change in the working tree at once, so the summary counts the two outcomes separately — files
 * rolled back to their committed state, and brand-new files that get deleted outright with nothing
 * to restore them from — and then lists the paths so the scope is unambiguous.
 */
@Composable
fun ConfirmRevertAllDialog(changes: List<FileChange>, onConfirm: () -> Unit, onCancel: () -> Unit) {
    if (changes.isEmpty()) return
    ConfirmGitActionDialog(
        title = revertAllTitle(changes.size),
        confirmLabel = "Revert all",
        onConfirm = onConfirm,
        onCancel = onCancel,
    ) {
        for (line in revertAllSummary(changes)) Text(line, color = ChangeColors.REMOVED)
        // List the paths, capped so a big change set can't grow the dialog past the screen; the
        // count in the title still conveys the full scope.
        val shown = changes.take(MaxListedChanges)
        for (change in shown) Text(change.path, color = ChangeColors.UNTRACKED)
        if (changes.size > shown.size) {
            Text("…and ${changes.size - shown.size} more", color = ChangeColors.UNTRACKED)
        }
    }
}

internal fun revertAllTitle(count: Int) = "Revert all ${plural(count, "change")}?"

/**
 * The one or two sentences describing what "Revert all" is about to do: the tracked files rolled
 * back to their committed state, and the new files deleted outright. Kept separate from the
 * composable — and each outcome dropped when its count is zero — so the dialog never claims a
 * consequence that doesn't apply to the change set in hand.
 */
internal fun revertAllSummary(changes: List<FileChange>): List<String> {
    val fresh = changes.count { it.kind == ChangeKind.UNTRACKED || it.kind == ChangeKind.ADDED }
    val tracked = changes.size - fresh
    return buildList {
        if (tracked > 0) {
            add("${plural(tracked, "file")} will be restored to the last commit — local changes discarded.")
        }
        if (fresh > 0) {
            add(
                "${plural(fresh, "new file")} will be deleted from disk. They aren't in any commit, " +
                    "so that can't be undone."
            )
        }
    }
}

/** "1 file" / "3 files" — shared by every confirmation that counts what it is about to change. */
internal fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"

private const val MaxListedChanges = 12

/**
 * The shared frame the destructive-git confirmations sit in — title, body, Cancel/confirm row. Used
 * by both revert dialogs here and by the history log's revert ([ConfirmRevertCommitDialog]), so a
 * warning about losing uncommitted work looks the same wherever it is raised from.
 *
 * A window of its own, not a popup centred in nop's: revert is asked for from the commit panel,
 * right beside the agent pane, and a terminal there is drawn over any popup — the confirmation came
 * up with its buttons hidden behind it. See [DialogFrame]. Enter is deliberately not a shortcut for
 * confirming: nothing here can be undone.
 */
@Composable
internal fun ConfirmGitActionDialog(
    title: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    confirmLabel: String = "Revert",
    body: @Composable () -> Unit,
) {
    DialogFrame(title = title, onClose = onCancel, size = DpSize(420.dp, Dp.Unspecified)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        body()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            DefaultButton(onClick = onConfirm) { Text(confirmLabel) }
        }
    }
}
