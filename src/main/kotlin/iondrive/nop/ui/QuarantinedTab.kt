package iondrive.nop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.recovery.LocalWindowId
import iondrive.nop.recovery.Quarantine
import iondrive.nop.recovery.WindowRecovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.InputStream
import java.text.DateFormat
import java.util.Date

/**
 * A tab, named without its project: the same file seen from two project tabs is the same content,
 * and fails the same way. [Tab.id] names a diff by its path within the repo, so the repo is added.
 */
internal val Tab.recoveryKey: String
    get() = when (this) {
        is Tab.Diff -> "diff:${File(repoRoot, change.path).absolutePath}"
        is Tab.CommitDiff -> "commitdiff:$sha:${File(repoRoot, file.path).absolutePath}"
        else -> id
    }

/** The file on disk a tab is a view of, for reading it as plain text. */
internal val Tab.sourceFile: File
    get() = when (this) {
        is Tab.FileView -> file
        is Tab.Diff -> File(repoRoot, change.path)
        is Tab.CommitDiff -> File(repoRoot, file.path)
        is Tab.LocalHistory -> file
        is Tab.RevisionDiff -> file
        is Tab.LocalDiff -> file
    }

/**
 * Tells the window [tab] is on screen in, so that if the window fails the tab is held back — see
 * [WindowRecovery.showing]. Call before composing the tab's content.
 */
@Composable
internal fun ReportShowing(tab: Tab) {
    val window = LocalWindowId.current ?: return
    val key = tab.recoveryKey
    WindowRecovery.showing(window, key)
    DisposableEffect(window, key) { onDispose { WindowRecovery.notShowing(window, key) } }
}

/**
 * Saves [store]'s unsaved edits if the window this is in fails, before it is rebuilt. The autosave
 * is a debounce behind the typing, and rebuilding the window starts the buffers again from disk.
 */
@Composable
internal fun RescueEditsOnFailure(store: FileEditStore) {
    val window = LocalWindowId.current ?: return
    DisposableEffect(window, store) {
        val rescue = WindowRecovery.onRescue(window) {
            store.snapshot().filter { it.hasUserEdit }.forEach { it.save() }
        }
        onDispose { WindowRecovery.remove(window, rescue) }
    }
}

/**
 * What a held-back tab shows instead of itself: what went wrong, and what the user can do about it.
 * Built from nothing that reads the tab's content, so it cannot fail the way the tab did; the plain
 * text it offers is read through [plainTextLines], which bounds every line and the whole.
 */
@Composable
internal fun QuarantinedTabView(tab: Tab, entry: Quarantine.Entry) {
    var plain by remember(entry.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("nop hit an error showing ${tab.title}, so it isn't drawing this tab")
        Text(
            "Held back since ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.at))}. " +
                "The rest of nop carries on; the tab stays open so it comes back here, not where it went wrong.",
            color = JewelTheme.globalColors.text.info,
        )
        SelectionContainer {
            Text(entry.reason, color = JewelTheme.globalColors.text.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultButton(onClick = { Quarantine.release(entry.key) }) { Text("Try again") }
            OutlinedButton(onClick = { plain = !plain }) { Text(if (plain) "Hide plain text" else "Open as plain text") }
            OutlinedButton(onClick = {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection("${tab.recoveryKey}\n${entry.reason}"), null)
            }) { Text("Copy error") }
        }
        if (plain) Box(Modifier.fillMaxWidth().weight(1f)) { PlainTextView(tab.sourceFile) }
    }
}

@Composable
private fun PlainTextView(file: File) {
    val lines by produceState<List<String>?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { file.inputStream().use { plainTextLines(it) } }
                .getOrElse { listOf("could not read ${file.path}: ${it.message}") }
        }
    }
    val style = TextStyle(fontFamily = NopFonts.Mono, fontSize = 12.sp, color = JewelTheme.globalColors.text.normal)
    val shown = lines
    if (shown == null) {
        Text("Reading ${file.name}…")
        return
    }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown) { line -> BasicText(line, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip) }
        }
    }
}

/**
 * [input] as lines safe to lay out one per row, whatever it holds: at most [MAX_PLAIN_BYTES] of it,
 * decoded leniently, split on any line ending, each line cut at [MAX_PLAIN_LINE_CHARS] with a note
 * of how much was left off, and control characters shown as `�` rather than handed to the text
 * engine. The last view nop can always give of a file.
 */
internal fun plainTextLines(input: InputStream): List<String> {
    val bytes = input.readNBytes(MAX_PLAIN_BYTES + 1)
    val truncated = bytes.size > MAX_PLAIN_BYTES
    val text = String(bytes, 0, minOf(bytes.size, MAX_PLAIN_BYTES), Charsets.UTF_8)
    val out = ArrayList<String>()
    for (raw in text.split("\r\n", "\n", "\r")) {
        val clean = buildString(minOf(raw.length, MAX_PLAIN_LINE_CHARS)) {
            for (i in 0 until minOf(raw.length, MAX_PLAIN_LINE_CHARS)) {
                val c = raw[i]
                append(
                    when {
                        c == '\t' -> ' '
                        Character.isISOControl(c) || c == '‮' || c == '‭' -> '�'
                        else -> c
                    },
                )
            }
            if (raw.length > MAX_PLAIN_LINE_CHARS) append(" … (+${raw.length - MAX_PLAIN_LINE_CHARS} characters)")
        }
        out += clean
    }
    if (truncated) out += "… (the rest of the file, past ${MAX_PLAIN_BYTES / 1_000_000}MB, is not shown)"
    return out
}

internal const val MAX_PLAIN_BYTES = 4_000_000
internal const val MAX_PLAIN_LINE_CHARS = 2_000
