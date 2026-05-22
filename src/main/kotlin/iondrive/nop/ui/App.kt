package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import iondrive.nop.git.GitRepo
import iondrive.nop.git.GitStatus
import iondrive.nop.git.StashEntry
import iondrive.nop.launchers.Launcher
import iondrive.nop.launchers.LauncherRun
import iondrive.nop.launchers.LauncherStore
import iondrive.nop.launchers.discoverLaunchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.VerticalSplitLayout
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import java.io.File
import java.nio.file.Path

@Composable
fun App(
    projectPath: Path,
    onChangeProject: () -> Unit = {},
    onToggleTheme: () -> Unit = {},
) {
    val repo: GitRepo? = remember(projectPath) { GitRepo.discover(projectPath) }
    DisposableEffect(repo) { onDispose { repo?.close() } }

    var status by remember(projectPath) { mutableStateOf(GitStatus.EMPTY) }
    var stashes by remember(projectPath) { mutableStateOf<List<StashEntry>>(emptyList()) }
    var selectedPaths by remember(projectPath) { mutableStateOf(emptySet<String>()) }
    var commitInFlight by remember(projectPath) { mutableStateOf(false) }
    var stashInFlight by remember(projectPath) { mutableStateOf(false) }
    var refreshing by remember(projectPath) { mutableStateOf(false) }
    // Target of the pending "Delete file?" confirmation, or null when no dialog is open.
    var pendingDelete by remember(projectPath) { mutableStateOf<File?>(null) }
    // Bumped on every refresh to force the project tree to rescan from disk
    var fsRefreshKey by remember(projectPath) { mutableStateOf(0) }
    // Re-key on projectPath so switching projects drops the old project's open tabs and edit state.
    val tabsState = remember(projectPath) { TabsState() }
    val editStore = remember(projectPath) { FileEditStore() }
    val scope = rememberCoroutineScope()

    suspend fun reloadStatus() {
        if (repo != null) {
            val fresh = withContext(Dispatchers.IO) { repo.loadStatus() }
            val freshStashes = withContext(Dispatchers.IO) {
                runCatching { repo.stashList() }.getOrDefault(emptyList())
            }
            status = fresh
            stashes = freshStashes
            // Default-select every change after a reload
            selectedPaths = fresh.changes.map { it.path }.toSet()
            // A status reload usually means files appeared / disappeared too (commit, stash, pop)
            // — re-walk the project tree so the sidebar matches the filesystem.
            fsRefreshKey += 1
        }
    }

    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            try {
                reloadStatus()
            } finally {
                refreshing = false
            }
        }
    }

    val rootPath = repo?.rootDir ?: projectPath
    val launcherStore = remember(rootPath) { LauncherStore(rootPath) }
    var stored by remember(rootPath) { mutableStateOf<List<Launcher>>(emptyList()) }
    var discovered by remember(rootPath) { mutableStateOf<List<Launcher>>(emptyList()) }
    // Re-key on fsRefreshKey so edits to package.json show up after the next refresh.
    LaunchedEffect(launcherStore, fsRefreshKey) {
        stored = withContext(Dispatchers.IO) { launcherStore.load() }
        discovered = withContext(Dispatchers.IO) { discoverLaunchers(rootPath) }
    }
    val storedNames = stored.map { it.name }.toSet()
    // A stored launcher with the same name wins, so the user can override a discovered entry
    // (e.g. add a stored "npm: build" with a customized command) without it being duplicated.
    val launchers = stored + discovered.filterNot { it.name in storedNames }
    val readOnlyNames = discovered.map { it.name }.toSet() - storedNames

    fun persistLaunchers(next: List<Launcher>) {
        stored = next
        scope.launch { withContext(Dispatchers.IO) { launcherStore.save(next) } }
        // The launchers file is itself version controlled — show it as a change immediately.
        refresh()
    }

    LaunchedEffect(repo) { reloadStatus() }

    // Close any open tabs that point at the given file or anything under it (when it's a dir).
    // Saves the user from typing into a buffer whose underlying file just got removed.
    fun closeTabsUnder(target: File) {
        val targetPath = target.absolutePath
        val toClose = tabsState.tabs.filter { tab ->
            val tabFile: File? = when (tab) {
                is Tab.FileView -> tab.file
                is Tab.Diff -> File(tab.repoRoot, tab.change.path)
                is Tab.History -> tab.file
                is Tab.LauncherOutput -> null
            }
            val p = tabFile?.absolutePath ?: return@filter false
            p == targetPath || p.startsWith("$targetPath${File.separator}")
        }
        for (tab in toClose) {
            editStore.close(tab.id)
            tabsState.close(tab.id)
        }
    }

    fun performDelete(target: File) {
        scope.launch {
            withContext(Dispatchers.IO) { target.deleteRecursively() }
            closeTabsUnder(target)
            reloadStatus()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground),
    ) {
        HorizontalSplitLayout(
            first = {
                ProjectTreePanel(
                    projectPath = rootPath,
                    status = status,
                    refreshKey = fsRefreshKey,
                    onFileClick = { tabsState.open(Tab.FileView(it)) },
                    onChangeProject = onChangeProject,
                    onToggleTheme = onToggleTheme,
                    onDeleteRequest = { pendingDelete = it },
                    onHistoryRequest = { file ->
                        if (repo != null) tabsState.open(Tab.History(file, repo.rootDir.toFile()))
                    },
                    headerExtras = {
                        LauncherButton(
                            launchers = launchers,
                            readOnlyNames = readOnlyNames,
                            onRun = { launcher ->
                                val run = LauncherRun(launcher, rootPath.toFile())
                                tabsState.open(Tab.LauncherOutput(run))
                                run.start()
                            },
                            onAdd = { persistLaunchers(stored + it) },
                            onDelete = { persistLaunchers(stored - it) },
                        )
                    },
                )
            },
            second = {
                VerticalSplitLayout(
                    first = {
                        TabbedViewerPanel(
                            tabsState = tabsState,
                            repo = repo,
                            editStore = editStore,
                            onFileSaved = ::refresh,
                        )
                    },
                    second = {
                        CommitPanel(
                            status = status,
                            stashes = stashes,
                            selectedPaths = selectedPaths,
                            onToggle = { path ->
                                selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
                            },
                            onChangeClick = { change ->
                                if (repo != null) {
                                    tabsState.open(Tab.Diff(change, repo.rootDir.toFile()))
                                }
                            },
                            onCommit = { message, included ->
                                if (repo != null && !commitInFlight) {
                                    scope.launch {
                                        commitInFlight = true
                                        try {
                                            withContext(Dispatchers.IO) {
                                                repo.stageAndCommit(message, included)
                                            }
                                            reloadStatus()
                                        } finally {
                                            commitInFlight = false
                                        }
                                    }
                                }
                            },
                            onStash = { message ->
                                if (repo != null && !stashInFlight) {
                                    scope.launch {
                                        stashInFlight = true
                                        try {
                                            withContext(Dispatchers.IO) {
                                                repo.stashCreate(message.ifBlank { null })
                                            }
                                            reloadStatus()
                                        } finally {
                                            stashInFlight = false
                                        }
                                    }
                                }
                            },
                            onPopStash = { entry ->
                                if (repo != null && !stashInFlight) {
                                    scope.launch {
                                        stashInFlight = true
                                        try {
                                            withContext(Dispatchers.IO) { repo.stashPop(entry) }
                                            reloadStatus()
                                        } finally {
                                            stashInFlight = false
                                        }
                                    }
                                }
                            },
                            onDropStash = { entry ->
                                if (repo != null && !stashInFlight) {
                                    scope.launch {
                                        stashInFlight = true
                                        try {
                                            withContext(Dispatchers.IO) { repo.stashDrop(entry) }
                                            reloadStatus()
                                        } finally {
                                            stashInFlight = false
                                        }
                                    }
                                }
                            },
                            commitInFlight = commitInFlight,
                            stashInFlight = stashInFlight,
                            refreshing = refreshing,
                            onRefresh = ::refresh,
                        )
                    },
                    state = rememberSplitLayoutState(0.55f),
                )
            },
            state = rememberSplitLayoutState(0.22f),
            modifier = Modifier.fillMaxSize(),
        )

        pendingDelete?.let { target ->
            ConfirmDeleteDialog(
                target = target,
                onConfirm = {
                    performDelete(target)
                    pendingDelete = null
                },
                onCancel = { pendingDelete = null },
            )
        }
    }
}
