package io.iondrive.nop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.iondrive.nop.git.CommitInfo
import io.iondrive.nop.git.GitRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SHA_FG = androidx.compose.ui.graphics.Color(0xFFA9B6C3)
private val META_FG = androidx.compose.ui.graphics.Color(0xFF7F8C9B)
private val DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun HistoryView(repo: GitRepo, tab: Tab.History) {
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
            else -> CommitList(commits)
        }
    }
}

@Composable
private fun CommitList(commits: List<CommitInfo>) {
    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = 10.dp),
        ) {
            items(commits) { c -> CommitRow(c) }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            style = NopScrollbarStyle,
            modifier = Modifier.align(Alignment.CenterEnd).width(10.dp).fillMaxHeight(),
        )
    }
}

@Composable
private fun CommitRow(c: CommitInfo) {
    val date = DATE_FMT.format(Instant.ofEpochSecond(c.whenEpochSeconds))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(c.shortSha, color = SHA_FG, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(c.shortMessage, fontSize = 13.sp)
            Text("$date · ${c.author}", color = META_FG, fontSize = 11.sp)
        }
    }
}
