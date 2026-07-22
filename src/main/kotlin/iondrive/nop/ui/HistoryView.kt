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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import iondrive.nop.git.GitRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SHA_FG = Color(0xFFA9B6C3)
private val META_FG = Color(0xFF7F8C9B)
private val DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun HistoryView(repo: GitRepo, tab: Tab.History, tabsState: TabsState) {
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
        Box(modifier = Modifier.fillMaxWidth().padding(12.dp, 10.dp, 12.dp, 4.dp)) {
            Text("History — ${tab.file.name}")
        }
        when {
            loading -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) { Text("Loading log…") }
            error != null -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                Text("Could not load history: $error")
            }
            commits.isEmpty() -> Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
                Text("No commits touch this path.")
            }
            else -> CommitList(repo, tab, commits, tabsState)
        }
    }
}

@Composable
private fun CommitList(repo: GitRepo, tab: Tab.History, commits: List<CommitInfo>, tabsState: TabsState) {
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
            modifier = Modifier.fillMaxSize().padding(end = 10.dp),
        ) {
            for (c in commits) {
                item(key = c.sha) {
                    CommitRow(
                        c = c,
                        expanded = c.sha == expandedSha,
                        onToggle = { tab.expandedSha = if (expandedSha == c.sha) null else c.sha },
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
            modifier = Modifier.align(Alignment.CenterEnd).width(10.dp).fillMaxHeight(),
        )
    }
}

@Composable
private fun CommitRow(c: CommitInfo, expanded: Boolean, onToggle: () -> Unit) {
    val date = DATE_FMT.format(Instant.ofEpochSecond(c.whenEpochSeconds))
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
