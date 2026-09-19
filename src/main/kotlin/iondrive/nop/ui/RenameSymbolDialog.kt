package iondrive.nop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import iondrive.nop.lang.JavaRename
import iondrive.nop.lang.RenamePlan
import iondrive.nop.lang.UsageResult
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * The rename prompt: a new name, and a running account of what pressing Rename would do.
 *
 * The plan is recomputed on every keystroke, which is what makes this worth being a dialog rather
 * than an inline edit. Before committing, the user can see how many places change, in how many
 * files, whether the file itself gets renamed alongside its class, what name Java won't accept, and
 * — the one that matters most — whether nop could prove it found every occurrence. A rename that
 * might miss a call site is still offered, because the alternative is doing it by hand with a text
 * search and no warning at all; but it is never offered *quietly*.
 *
 * A window of its own rather than a popup centred in nop's, because the middle of nop's window is
 * often the tool region, and a terminal there is drawn over any popup. See [DialogFrame].
 */
@Composable
fun RenameSymbolDialog(
    usages: UsageResult,
    declaringPath: String?,
    declaringText: String?,
    onRename: (RenamePlan) -> Unit,
    onCancel: () -> Unit,
    /** Whether a project-relative path is taken — see [JavaRename.plan]. */
    pathExists: (String) -> Boolean = { false },
    /** Set when applying the rename failed, to be shown in place rather than as a separate dialog. */
    failure: String? = null,
    busy: Boolean = false,
) {
    val oldName = usages.target.name
    val state = rememberTextFieldState(oldName, TextRange(0, oldName.length))
    val focusRequester = remember { FocusRequester() }

    // Planning is cheap — the expensive half (finding the usages) already happened — so the dialog
    // can afford to re-derive the whole answer on each keystroke rather than validating on submit.
    val plan by remember(usages, declaringText) {
        derivedStateOf {
            JavaRename.plan(usages, state.text.toString().trim(), declaringPath, declaringText, pathExists)
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Read through state, not captured: the Enter shortcut can hold on to the submit from the
    // dialog's first composition, and a rename already running must not be started again from it.
    val currentBusy by rememberUpdatedState(busy)
    val currentOnRename by rememberUpdatedState(onRename)
    fun submit() {
        if (plan.canApply && !currentBusy) currentOnRename(plan)
    }

    val title = "Rename ${usages.target.description}"
    DialogFrame(title = title, onClose = onCancel, size = DpSize(520.dp, Dp.Unspecified), onSubmit = ::submit) {
        val warning = if (JewelTheme.isDark) Color(0xFFD9A441) else Color(0xFF8A6D1A)
        Text(title, fontWeight = FontWeight.Bold)
        TextField(
            state = state,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        )

        val summary = plan.summaryLine()
        Text(summary, color = ChangeColors.UNTRACKED)
        // Only once the name has actually changed — before that the line reads "Widget.java will
        // be renamed to Widget.java", which is noise pretending to be information.
        plan.fileRename?.takeIf { it.newFileName != plan.oldName + ".java" }?.let {
            Text(
                "${it.path.substringAfterLast('/')} will be renamed to ${it.newFileName}",
                color = ChangeColors.UNTRACKED,
            )
        }
        if (!plan.exact && plan.note != null) {
            Text(plan.note!!, color = warning)
        }
        // Problems only once the user has typed something worth judging: a dialog that opens
        // with "That is already its name" in red is scolding them for not having started.
        val started = state.text.toString().trim() != oldName
        if (started) plan.problems.forEach { Text(it, color = ChangeColors.REMOVED) }
        failure?.let { Text(it, color = ChangeColors.REMOVED) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
            DefaultButton(onClick = ::submit, enabled = plan.canApply && !busy) {
                Text(if (busy) "Renaming…" else "Rename")
            }
        }
    }
}

/** "12 occurrences in 3 files" — what the button is about to do, before it does it. */
internal fun RenamePlan.summaryLine(): String {
    val places = if (occurrenceCount == 1) "1 occurrence" else "$occurrenceCount occurrences"
    val files = if (fileCount == 1) "1 file" else "$fileCount files"
    return "$places in $files"
}
