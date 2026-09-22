package iondrive.nop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.diff.DiffComputer
import iondrive.nop.diff.DiffResult
import iondrive.nop.history.LocalHistory
import iondrive.nop.history.LocalRevision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Local history for one file: every version nop has seen it hold, newest first, in the same shape as
 * the git log beside it ([HistoryView]) — a monospace stamp on the left, what happened and when on
 * the right, and a click that opens the change itself.
 *
 * The two panels answer the same question at different granularities. Git history reaches back over
 * committed work; this reaches back over the last few days of *saves*, including the ones that never
 * became a commit and the ones something outside nop overwrote.
 */
@Composable
fun LocalHistoryView(history: LocalHistory, tab: Tab.LocalHistory, tabsState: TabsState, reloadKey: Int = 0) {
    var loading by remember(tab.id) { mutableStateOf(true) }
    var revisions by remember(tab.id) { mutableStateOf<List<LocalRevision>>(emptyList()) }

    LaunchedEffect(tab.id, reloadKey) {
        loading = true
        revisions = withContext(Dispatchers.IO) { history.revisions(tab.file) }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        Box(modifier = Modifier.fillMaxWidth().padding(12.dp, 10.dp, 12.dp, 4.dp)) {
            Text("Local history — ${tab.file.name}")
        }
        when {
            loading -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) { Text("Loading revisions…") }
            revisions.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                Text("No file updates yet")
            }
            else -> RevisionList(revisions, tab, tabsState)
        }
    }
}

@Composable
private fun RevisionList(revisions: List<LocalRevision>, tab: Tab.LocalHistory, tabsState: TabsState) {
    val listState = rememberLazyListState()
    // Ages are relative to when the list was built rather than to a ticking clock: a panel that
    // silently rewrote "2 minutes ago" under the pointer would be movement with nothing behind it.
    val builtAt = remember(revisions) { System.currentTimeMillis() }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {
            items(revisions, key = { it.timestampMillis }) { revision ->
                RevisionRow(revision, builtAt) {
                    tabsState.open(Tab.LocalDiff(tab.file, revision.timestampMillis))
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            style = NopScrollbarStyle,
            modifier = Modifier.align(Alignment.CenterEnd).width(8.dp).fillMaxHeight(),
        )
    }
}

@Composable
private fun RevisionRow(revision: LocalRevision, now: Long, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(LocalHistoryFormat.time(revision.timestampMillis), color = SHA_FG, fontFamily = NopFonts.Mono, fontSize = 12.sp)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(LocalHistoryFormat.age(revision.timestampMillis, now), fontSize = 13.sp)
            Text(
                "${LocalHistoryFormat.dateTime(revision.timestampMillis)} · ${LocalHistoryFormat.size(revision.sizeBytes)}",
                color = META_FG,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * A local-history revision against what the file holds right now — the same read-only side-by-side
 * view a commit diff opens in, so "what did this look like before?" reads identically whichever
 * history it came from. The revision is on the left, the working file on the right.
 */
@Composable
fun LocalDiffView(
    history: LocalHistory,
    tab: Tab.LocalDiff,
    splitRatio: Float = 0.5f,
    onSplitRatioChange: (Float) -> Unit = {},
    onTopLine: (Int) -> Unit = {},
    reloadKey: Int = 0,
    findTrigger: Int = 0,
) {
    var loading by remember(tab.id) { mutableStateOf(true) }
    var error by remember(tab.id) { mutableStateOf<String?>(null) }
    var result by remember(tab.id) { mutableStateOf<DiffResult?>(null) }

    // reloadKey re-reads both sides: the right-hand one is the live file, so re-opening this tab (or
    // F5) is how the user re-diffs the revision against work done since.
    LaunchedEffect(tab.id, reloadKey) {
        val (old, new) = withContext(Dispatchers.IO) {
            val revision = history.revisionAt(tab.file, tab.timestampMillis)
            val snapshot = revision?.let { history.read(it) }
            snapshot to runCatching { tab.file.readText() }.getOrDefault("")
        }
        if (old == null) {
            // Pruned away, or a tab restored against a history that has since been cleared. Saying so
            // beats an empty diff that reads as "this revision was identical to the file".
            error = "That revision is no longer in local history."
            loading = false
            return@LaunchedEffect
        }
        result = withContext(Dispatchers.Default) { DiffComputer.compute(old, new) }
        error = null
        loading = false
    }

    val tokenize = remember(tab.id) { tokenizerForExtension(tab.file.extension) }
    CompositionLocalProvider(LocalDiffTokenizer provides tokenize) {
        when {
            loading -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) { Text("Loading diff…") }
            error != null -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) { Text(error!!) }
            result != null -> ReadOnlyDiffList(
                result = result!!,
                splitRatio = splitRatio,
                onSplitRatioChange = onSplitRatioChange,
                onTopLine = onTopLine,
                searchKey = tab.id,
                findTrigger = findTrigger,
                oldHeader = "Local history: ${tab.file.name}",
                newHeader = "Working copy: ${tab.file.name}",
            )
        }
    }
}

/** How a revision's timestamp and size are worded, in the tab strip and in the panel alike. */
object LocalHistoryFormat {
    private val TIME_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private val DATE_TIME_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    fun time(millis: Long): String = TIME_FMT.format(Instant.ofEpochMilli(millis))

    fun dateTime(millis: Long): String = DATE_TIME_FMT.format(Instant.ofEpochMilli(millis))

    /**
     * How long ago [millis] was, worded the way someone hunting for a lost edit thinks about it —
     * "20 minutes ago", not a timestamp they have to subtract from the clock. The exact stamp is
     * beside it in the row for when that's the thing they need.
     */
    fun age(millis: Long, now: Long = System.currentTimeMillis()): String {
        val seconds = (now - millis) / 1000
        return when {
            seconds < 0 -> "just now" // clock moved backwards (NTP step, DST); not worth its own wording
            seconds < 45 -> "just now"
            seconds < 90 -> "a minute ago"
            seconds < 60 * 60 -> "${seconds / 60} minutes ago"
            seconds < 2 * 60 * 60 -> "an hour ago"
            seconds < 24 * 60 * 60 -> "${seconds / 3600} hours ago"
            seconds < 48 * 60 * 60 -> "yesterday"
            else -> "${seconds / (24 * 3600)} days ago"
        }
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} kB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
