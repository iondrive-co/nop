package iondrive.nop.ui

import androidx.compose.runtime.Composable
import iondrive.nop.git.CommitFile
import org.jetbrains.jewel.ui.component.Text

/**
 * A commit the user has asked to revert from a git history tab, waiting on its confirmation: the
 * commit itself and the files its changes will be backed out of.
 */
data class RevertCommitRequest(
    val sha: String,
    val shortSha: String,
    val message: String,
    val files: List<CommitFile>,
)

/**
 * Confirms backing a commit's changes out of the working tree. Milder than the other two revert
 * confirmations — nothing is committed, HEAD doesn't move, and the operation refuses outright
 * rather than overwrite uncommitted edits — so the wording spends its space saying what the user
 * will be left holding, not warning them off.
 */
@Composable
fun ConfirmRevertCommitDialog(request: RevertCommitRequest, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ConfirmGitActionDialog(
        title = "Revert commit ${request.shortSha}?",
        confirmLabel = "Revert",
        onConfirm = onConfirm,
        onCancel = onCancel,
    ) {
        Text(request.message, color = ChangeColors.UNTRACKED)
        for (line in revertCommitSummary(request.files)) Text(line, color = ChangeColors.REMOVED)
        // The paths themselves, capped so a sweeping commit can't grow the dialog past the screen.
        val shown = request.files.take(MaxListedFiles)
        for (file in shown) Text(file.path, color = ChangeColors.UNTRACKED)
        if (request.files.size > shown.size) {
            Text("…and ${request.files.size - shown.size} more", color = ChangeColors.UNTRACKED)
        }
    }
}

/**
 * The sentences describing what reverting is about to do. Deliberately two short ones: what gets
 * undone, and where the result lands. A commit whose file list couldn't be read still gets the
 * second line — the reversal is defined by the commit, not by the list this panel managed to
 * load, so an empty list is a gap in what we can *show*, not a reason to claim nothing happens.
 */
internal fun revertCommitSummary(files: List<CommitFile>): List<String> = buildList {
    if (files.isNotEmpty()) {
        add("The changes this commit made to ${plural(files.size, "file")} will be undone on disk.")
    }
    add("Nothing is committed: the reversal arrives as pending changes you can commit or revert.")
}

private const val MaxListedFiles = 12
