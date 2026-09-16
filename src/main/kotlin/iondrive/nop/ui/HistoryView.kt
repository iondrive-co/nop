package iondrive.nop.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import iondrive.nop.git.CommitFile
import iondrive.nop.git.CommitFileChange
import iondrive.nop.git.CommitInfo
import iondrive.nop.Log
import iondrive.nop.git.GitRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val SHA_FG = Color(0xFFA9B6C3)
internal val META_FG = Color(0xFF7F8C9B)

/** How a commit's time is written wherever one is listed — the log, and the revision picker. */
internal val COMMIT_DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/**
 * One open git log: the path it covers, and which commit in it the user has expanded.
 *
 * The id is the path, so asking a second time for a file whose log is already up lands back on that
 * tab rather than stacking a duplicate of it beside the first.
 */
class HistorySession(val file: File, val repoRoot: File) {
    val id: String = "history:${file.absolutePath}"

    /** What the tab is called. The file's own name — a log is *of* a path and of nothing else. */
    val title: String get() = file.name

    /**
     * The commit whose file list is open, if any. Held here rather than inside the panel because
     * the tool panel composes one tab at a time: remembered in the view, the expansion would be
     * lost every time the user looked at a diff and came back.
     */
    var expandedSha: String? by mutableStateOf(null)
}

/**
 * The git logs open in the tool strip, and which of them is showing.
 *
 * Owned by [App] for the same reason [RunSessions] is — the panel under the strip exists only while
 * its tab is selected, so anything that has to outlive a glance at the commit list cannot live in
 * it. Unlike the terminals and the runs there is no process here, so closing a tab costs nothing
 * but the scroll position, and reopening re-reads the log.
 */
class HistorySessions {
    private val _sessions = mutableStateListOf<HistorySession>()
    val sessions: List<HistorySession> get() = _sessions

    var selectedId: String? by mutableStateOf(null)
        private set

    val selected: HistorySession? get() = _sessions.firstOrNull { it.id == selectedId }

    /**
     * Shows the log for [file], opening a tab for it if one isn't already up. A second ask for a
     * path that is already open selects that tab instead of adding another: two logs of one path
     * are the same log.
     */
    fun open(file: File, repoRoot: File): HistorySession {
        // Breadcrumb, for the same reason TabsState.open logs one: the last thing opened is the
        // most useful piece of context when nop dies with it on screen.
        Log.info("open history ${file.absolutePath}")
        val existing = _sessions.firstOrNull { it.file.absolutePath == file.absolutePath }
        val session = existing ?: HistorySession(file, repoRoot).also { _sessions.add(it) }
        selectedId = session.id
        return session
    }

    fun select(id: String) {
        if (_sessions.any { it.id == id }) selectedId = id
    }

    /** Drops the log behind [id] from the strip, selecting its neighbour if it was the one showing. */
    fun close(id: String) {
        val idx = _sessions.indexOfFirst { it.id == id }
        if (idx < 0) return
        _sessions.removeAt(idx)
        if (selectedId == id) {
            selectedId = (_sessions.getOrNull(idx) ?: _sessions.getOrNull(idx - 1))?.id
        }
    }

    /** Closes every log of [file], or of anything under it when [file] is a directory. */
    fun closeUnder(file: File) {
        val target = file.absolutePath
        _sessions.filter {
            val p = it.file.absolutePath
            p == target || p.startsWith("$target${File.separator}")
        }.forEach { close(it.id) }
    }

    /**
     * Puts back the logs [files] records, in order, with nothing selected.
     *
     * Nothing selected for the same reason a restored run isn't: bringing the strip back is one
     * claim, and deciding that a log is what the user wants to look at first thing after a start —
     * over the commit list the panel opens on — is a different and worse one.
     *
     * A path that has since gone is skipped. Its log would still read, but a tab for a file that is
     * no longer there is a puzzle offered to someone who didn't ask for it.
     */
    fun restore(files: List<File>, repoRoot: File) {
        files.forEach { file ->
            if (!file.exists()) return@forEach
            if (_sessions.none { it.file.absolutePath == file.absolutePath }) {
                _sessions.add(HistorySession(file, repoRoot))
            }
        }
    }
}

/**
 * The History tool tab: the git log of whichever path the tool strip has selected.
 *
 * Nothing selected means every log has been closed. There is no "+" here to open another — a log is
 * *of* a path, so the thing that opens one is the file, in the tree's right-click menu or the
 * editor's — so the panel says where that is rather than offering a button that would only have to
 * ask which path it meant.
 */
@Composable
fun HistoryPanel(
    state: HistorySessions,
    repo: GitRepo?,
    tabsState: TabsState,
    onRevertCommit: (CommitInfo) -> Unit = {},
) {
    val selected = state.selected
    if (selected == null || repo == null) {
        ToolPanelMessage("Right-click a file in the tree, or in the editor, and pick \"Show history\" to read its git log here")
        return
    }
    HistoryView(repo, selected, tabsState, onRevertCommit)
}

/**
 * Git log for one path, newest first: click a commit to see what it touched, click one of those
 * files to read that commit's diff of it, and right-click a commit to back its changes out of the
 * working tree ([onRevertCommit]).
 *
 * The revert is left to the caller to carry out: it raises a confirmation, has to reconcile open
 * buffers against the files it rewrites, and needs the commit's file list, which this panel only
 * holds for a commit the user has expanded.
 */
@Composable
fun HistoryView(
    repo: GitRepo,
    tab: HistorySession,
    tabsState: TabsState,
    onRevertCommit: (CommitInfo) -> Unit = {},
) {
    var loading by remember(tab.id) { mutableStateOf(true) }
    var error by remember(tab.id) { mutableStateOf<String?>(null) }
    var commits by remember(tab.id) { mutableStateOf<List<CommitInfo>>(emptyList()) }

    LaunchedEffect(tab.id) {
        try {
            val relPath = repo.rootDir.toAbsolutePath().normalize()
                .relativize(tab.file.toPath().toAbsolutePath().normalize())
                .toString()
                .replace(File.separatorChar, '/')
                .takeIf { it.isNotEmpty() && !it.startsWith("..") }
            commits = withContext(Dispatchers.IO) { repo.history(relPath) }
            loading = false
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp, 10.dp, 12.dp, 4.dp)) {
            Text("History — ${tab.file.name}")
            // A right-click menu is the only way to reach the revert, so the header says so —
            // there's no button on the rows to notice, and an action nobody finds is no feature.
            if (commits.isNotEmpty()) {
                Text("Right click a commit to undo its changes on disk", color = META_FG, fontSize = 11.sp)
            }
        }
        when {
            loading -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) { Text("Loading log…") }
            error != null -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                Text("Could not load history: $error")
            }
            commits.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                Text("No commits touch this path.")
            }
            else -> CommitList(repo, tab, commits, tabsState, onRevertCommit)
        }
    }
}

@Composable
private fun CommitList(
    repo: GitRepo,
    tab: HistorySession,
    commits: List<CommitInfo>,
    tabsState: TabsState,
    onRevertCommit: (CommitInfo) -> Unit,
) {
    val listState = rememberLazyListState()
    val expandedSha = tab.expandedSha
    var expandedFiles by remember(expandedSha) { mutableStateOf<List<CommitFile>>(emptyList()) }
    var filesLoading by remember(expandedSha) { mutableStateOf(expandedSha != null) }

    LaunchedEffect(expandedSha) {
        val sha = expandedSha
        if (sha != null) {
            filesLoading = true
            expandedFiles = withContext(Dispatchers.IO) { repo.commitFiles(sha) }
            filesLoading = false
        } else {
            expandedFiles = emptyList()
            filesLoading = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = 8.dp),
        ) {
            for (c in commits) {
                item(key = c.sha) {
                    CommitRow(
                        c = c,
                        expanded = c.sha == expandedSha,
                        onToggle = { tab.expandedSha = if (expandedSha == c.sha) null else c.sha },
                        onRevert = { onRevertCommit(c) },
                    )
                }
                if (c.sha == expandedSha) {
                    if (filesLoading) {
                        item(key = "${c.sha}-loading") {
                            Text("Loading…", color = META_FG, fontSize = 11.sp, modifier = Modifier.padding(start = 36.dp, top = 2.dp))
                        }
                    } else {
                        items(expandedFiles, key = { "${c.sha}:${it.path}" }) { f ->
                            CommitFileRow(f) {
                                tabsState.open(Tab.CommitDiff(c.sha, c.shortSha, f, tab.repoRoot))
                            }
                        }
                    }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommitRow(c: CommitInfo, expanded: Boolean, onToggle: () -> Unit, onRevert: () -> Unit) {
    val date = COMMIT_DATE_FMT.format(Instant.ofEpochSecond(c.whenEpochSeconds))
    // Right-click to undo this commit — the same idiom the change list and the project tree use for
    // their git actions, kept off the row itself so a long log stays a log. The ellipsis signals the
    // confirmation that follows.
    ContextMenuArea(items = { listOf(ContextMenuItem("Revert commit…") { onRevert() }) }) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(c.shortSha, color = SHA_FG, fontFamily = NopFonts.Mono, fontSize = 12.sp)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(c.shortMessage, fontSize = 13.sp)
                Text("$date · ${c.author}", color = META_FG, fontSize = 11.sp)
            }
        }
    }
}

private val FILE_ADDED_COLOR = Color(0xFF629755)
private val FILE_DELETED_COLOR = Color(0xFFB35E5E)
private val FILE_MODIFIED_COLOR = Color(0xFF6897BB)
private val FILE_RENAMED_COLOR = Color(0xFFCC7832)

@Composable
private fun CommitFileRow(f: CommitFile, onClick: () -> Unit) {
    val (prefix, color) = when (f.changeType) {
        CommitFileChange.ADDED -> "A" to FILE_ADDED_COLOR
        CommitFileChange.DELETED -> "D" to FILE_DELETED_COLOR
        CommitFileChange.MODIFIED -> "M" to FILE_MODIFIED_COLOR
        CommitFileChange.RENAMED -> "R" to FILE_RENAMED_COLOR
        CommitFileChange.COPIED -> "C" to FILE_RENAMED_COLOR
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(start = 36.dp, end = 12.dp, top = 1.dp, bottom = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(prefix, color = color, fontFamily = NopFonts.Mono, fontSize = 12.sp)
        Text(f.path, fontSize = 12.sp, color = META_FG)
    }
}
