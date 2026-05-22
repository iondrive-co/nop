package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.text.input.TextFieldState
import iondrive.nop.Settings
import iondrive.nop.git.GitRepo
import iondrive.nop.git.GitStatus
import iondrive.nop.git.StashEntry
import iondrive.nop.index.FileIndex
import iondrive.nop.index.Indexer
import iondrive.nop.index.JumpResolver
import iondrive.nop.index.SymbolIndex
import iondrive.nop.launchers.Launcher
import iondrive.nop.launchers.LauncherRun
import iondrive.nop.launchers.LauncherStore
import iondrive.nop.launchers.discoverLaunchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import java.io.File
import java.nio.file.Path

@OptIn(FlowPreview::class)
@Composable
fun App(
    projectPath: Path,
    recentProjects: List<Path> = emptyList(),
    onPickRecent: (Path) -> Unit = {},
    onPickNew: () -> Unit = {},
    onToggleTheme: () -> Unit = {},
    fileSearchTrigger: Int = 0,
    findInFilesTrigger: Int = 0,
    findInFileTrigger: Int = 0,
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
    // Whether the file-search popup is currently shown. Bumped open by the double-shift trigger
    // passed in from Main; user closes it by Esc, click-outside, or picking a file.
    var fileSearchOpen by remember(projectPath) { mutableStateOf(false) }
    // Bumped on every refresh to force the project tree to rescan from disk
    var fsRefreshKey by remember(projectPath) { mutableStateOf(0) }
    // Re-key on projectPath so switching projects drops the old project's open tabs and edit state.
    val tabsState = remember(projectPath) { TabsState() }
    val editStore = remember(projectPath) { FileEditStore() }
    val scope = rememberCoroutineScope()

    // Split ratios are persisted globally (not per-project): the layout preference is about the
    // user's preferred shape of the app, not the specific repo. Load once at composition start;
    // unsaved values fall back to the original defaults.
    val savedRatios = remember { Settings.loadSplitRatios() }
    var hRatio by remember { mutableStateOf(savedRatios.horizontal ?: 0.22f) }
    var vRatio by remember { mutableStateOf(savedRatios.vertical ?: 0.55f) }
    LaunchedEffect(Unit) {
        snapshotFlow { hRatio to vRatio }
            .debounce(500)
            .distinctUntilChanged()
            .collectLatest { (h, v) -> Settings.saveSplitRatios(h, v) }
    }

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
    // Jump-to-source index: load the previous run's cache for instant clicks while we rebuild,
    // then swap in the fresh version (and persist it) once the walk finishes. The index lives
    // under ~/.config/nop/projects/<slug>/ so the project tree stays free of derived files.
    var symbolIndex by remember(rootPath) { mutableStateOf(SymbolIndex()) }
    var fileIndex by remember(rootPath) { mutableStateOf(FileIndex()) }
    LaunchedEffect(rootPath) {
        val indexFile = Settings.projectDataDir(rootPath).resolve("index.tsv")
        val filesIndexFile = Settings.projectDataDir(rootPath).resolve("files.txt")
        val cachedSymbols = withContext(Dispatchers.IO) { SymbolIndex.load(indexFile) }
        if (cachedSymbols.size > 0) symbolIndex = cachedSymbols
        val cachedFiles = withContext(Dispatchers.IO) { FileIndex.load(filesIndexFile) }
        if (cachedFiles.files.isNotEmpty()) fileIndex = cachedFiles
        val freshSymbols = withContext(Dispatchers.IO) { Indexer.build(rootPath) }
        symbolIndex = freshSymbols
        val freshFiles = withContext(Dispatchers.IO) { FileIndex.build(rootPath) }
        fileIndex = freshFiles
        withContext(Dispatchers.IO) {
            SymbolIndex.save(indexFile, freshSymbols)
            FileIndex.save(filesIndexFile, freshFiles)
        }
    }
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

    // Open the search popup whenever Main bumps the trigger (double-Shift). Skip the initial 0
    // so the dialog doesn't pop on first composition.
    LaunchedEffect(fileSearchTrigger) {
        if (fileSearchTrigger > 0) fileSearchOpen = true
    }

    // Bottom tab selection (Commit by default). Ctrl+Shift+F bumps findInFilesTrigger; we flip
    // the bottom strip to Search and forward the trigger into SearchPanel so it requests focus
    // on its input field.
    var bottomTab by remember(projectPath) { mutableStateOf(BottomTab.Commit) }
    var searchFieldFocusTrigger by remember(projectPath) { mutableStateOf(0) }
    val searchQueryState = remember(rootPath) { TextFieldState() }
    LaunchedEffect(findInFilesTrigger) {
        if (findInFilesTrigger > 0) {
            bottomTab = BottomTab.Search
            searchFieldFocusTrigger += 1
        }
    }

    // Commit-message text-area height, persisted per-project. Long commit messages need more
    // room than the default; we save what the user dragged it to so reopening the project
    // restores the same shape.
    var commitMessageHeight by remember(rootPath) {
        val saved = Settings.loadCommitMessageHeight(rootPath)
        mutableStateOf<Dp>(saved?.dp ?: DEFAULT_MESSAGE_HEIGHT)
    }
    LaunchedEffect(rootPath) {
        snapshotFlow { commitMessageHeight }
            .drop(1)
            .debounce(500)
            .distinctUntilChanged()
            .collectLatest { h ->
                withContext(Dispatchers.IO) { Settings.saveCommitMessageHeight(rootPath, h.value) }
            }
    }

    // Tab-strip persistence: restore on project open, then debounce-save on every change. Saved
    // alongside the symbol/file indexes under the project's data dir. Restore happens before
    // we subscribe to changes, and we drop the first emission so the restore itself doesn't
    // immediately trigger a no-op save.
    val tabsFile = remember(rootPath) { Settings.projectDataDir(rootPath).resolve("tabs.tsv") }
    LaunchedEffect(rootPath, tabsState) {
        val saved = withContext(Dispatchers.IO) { TabsPersistence.load(tabsFile) }
        TabsPersistence.restore(tabsState, saved, repo?.rootDir?.toFile())
        snapshotFlow { tabsState.tabs.map { it.id } to tabsState.selectedId }
            .drop(1)
            .debounce(500)
            .distinctUntilChanged()
            .collectLatest {
                val snapshotTabs = tabsState.tabs.toList()
                val selectedId = tabsState.selectedId
                withContext(Dispatchers.IO) { TabsPersistence.save(tabsFile, snapshotTabs, selectedId) }
            }
    }

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

    // Sync the active tab's underlying file back into the tree so the sidebar always shows
    // which file the user is currently looking at.
    val revealFile: File? = when (val t = tabsState.selectedTab) {
        is Tab.FileView -> t.file
        is Tab.Diff -> File(t.repoRoot, t.change.path)
        is Tab.History -> t.file
        is Tab.LauncherOutput, null -> null
    }

    val tintColor = projectTint(rootPath, JewelTheme.isDark)

    Box(
        modifier = Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Project-identity strip: a subtle band tinted from the project path's hash, so two
            // windows on different projects look visually distinct at a glance.
            Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(tintColor))
            HorizontalSplit(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                ratio = hRatio,
                onRatioChange = { hRatio = it },
                first = {
                    ProjectTreePanel(
                        projectPath = rootPath,
                        status = status,
                        refreshKey = fsRefreshKey,
                        revealFile = revealFile,
                        recentProjects = recentProjects,
                        onFileClick = { tabsState.open(Tab.FileView(it)) },
                        onPickRecent = onPickRecent,
                        onPickNew = onPickNew,
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
                    VerticalSplit(
                        modifier = Modifier.fillMaxSize(),
                        ratio = vRatio,
                        onRatioChange = { vRatio = it },
                        first = {
                            TabbedViewerPanel(
                                tabsState = tabsState,
                                repo = repo,
                                editStore = editStore,
                                onFileSaved = ::refresh,
                                onResolveAt = { currentFile, text, offset ->
                                    JumpResolver.resolve(
                                        symbolIndex,
                                        rootPath.toFile(),
                                        currentFile,
                                        text,
                                        offset,
                                    )
                                },
                                onJump = { file, line ->
                                    tabsState.openAt(Tab.FileView(file), line)
                                },
                                findInFileTrigger = findInFileTrigger,
                            )
                        },
                        second = {
                            BottomTabs(
                                selected = bottomTab,
                                onSelect = { bottomTab = it },
                                commit = {
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
                                        messageHeight = commitMessageHeight,
                                        onMessageHeightChange = { commitMessageHeight = it },
                                        stashInFlight = stashInFlight,
                                        refreshing = refreshing,
                                        onRefresh = ::refresh,
                                    )
                                },
                                search = {
                                    SearchPanel(
                                        projectRoot = rootPath,
                                        files = fileIndex.files,
                                        state = searchQueryState,
                                        focusTrigger = searchFieldFocusTrigger,
                                        onPick = { relPath, line ->
                                            val absolute = File(rootPath.toFile(), relPath)
                                            if (absolute.isFile) tabsState.openAt(Tab.FileView(absolute), line)
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }

        ThemeToggleButton(
            onToggle = onToggleTheme,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd),
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

        if (fileSearchOpen) {
            FileSearchDialog(
                files = fileIndex.files,
                onPick = { relPath ->
                    fileSearchOpen = false
                    val absolute = File(rootPath.toFile(), relPath)
                    if (absolute.isFile) tabsState.open(Tab.FileView(absolute))
                },
                onDismiss = { fileSearchOpen = false },
            )
        }
    }
}
