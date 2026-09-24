package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.text.input.TextFieldState
import iondrive.nop.Log
import iondrive.nop.Settings
import iondrive.nop.agent.Account
import iondrive.nop.agent.AgentConfig
import iondrive.nop.agent.Accounts
import iondrive.nop.agent.AgentSessionStore
import iondrive.nop.agent.EventLog
import iondrive.nop.agent.Login
import iondrive.nop.agent.NativeSessions
import iondrive.nop.agent.PastSession
import iondrive.nop.agent.Provider
import iondrive.nop.agent.Usage
import iondrive.nop.agent.UsageReading
import iondrive.nop.agent.handoverTarget
import iondrive.nop.git.CommitInfo
import iondrive.nop.git.CommitProgress
import iondrive.nop.git.FileChange
import iondrive.nop.git.GitRepo
import iondrive.nop.git.GitStatus
import iondrive.nop.git.RepoWatcher
import iondrive.nop.git.StashEntry
import iondrive.nop.history.LocalHistory
import iondrive.nop.index.AccessFrequency
import iondrive.nop.index.FileIndex
import iondrive.nop.index.Indexer
import iondrive.nop.index.JumpResolver
import iondrive.nop.index.SymbolIndex
import iondrive.nop.lang.JavaParse
import iondrive.nop.lang.JavaRename
import iondrive.nop.lang.JavaUsages
import iondrive.nop.lang.RenamePlan
import iondrive.nop.lang.UsageResult
import iondrive.nop.launchers.Launcher
import iondrive.nop.launchers.LauncherStore
import iondrive.nop.launchers.discoverLaunchers
import iondrive.nop.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import java.awt.CardLayout
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JPanel

// How often the commit panel re-checks git state on its own, so changes from editing, branch
// switches, or external git commands show up without hitting Refresh.
private const val GIT_POLL_INTERVAL_MS = 3_000L

// How many recent commit messages to remember per project for the reuse dropdown.
private const val COMMIT_MESSAGE_HISTORY_CAP = 20

/**
 * How often each agent account's quota is re-read. Five minutes, because the thing it feeds is a
 * decision made a few times a day: polling harder would spend Anthropic's rate limit to watch
 * Anthropic's rate limit, and a Codex reading only changes when that account runs a session anyway.
 */
private const val USAGE_POLL_INTERVAL_MS = 5 * 60 * 1000L

/** How often to poll while some account's reading is stale. See iondrive.nop.agent.UsageGate. */
private const val USAGE_RETRY_INTERVAL_MS = 60 * 1000L

// A pending project-tree creation/copy dialog. NewX carry the parent directory the entry will be
// created in; CopyFile carries the source file being duplicated.
/**
 * A rename waiting on the user's confirmation: the usages that would be rewritten, and the source
 * they were found from.
 *
 * The declaring file's text is carried along because the dialog re-checks for collisions on every
 * keystroke, and re-reading the file for each character typed would be absurd. It is the text as of
 * the moment the usages were found — which is also the text the offsets index into, so holding the
 * two together is what keeps them in step.
 */
private data class RenameRequest(
    val usages: UsageResult,
    val declaringPath: String?,
    val declaringText: String?,
)

private sealed interface TreeEntryDialog {
    data class NewFile(val parentDir: File) : TreeEntryDialog
    data class NewDirectory(val parentDir: File) : TreeEntryDialog
    data class NewPackage(val parentDir: File) : TreeEntryDialog
    data class CopyFile(val source: File) : TreeEntryDialog
    data class Rename(val target: File) : TreeEntryDialog
}

@OptIn(FlowPreview::class)
@Composable
fun App(
    projectPath: Path,
    // Borrowed from the workspace, which watches every open project's tree. Null means "no watcher"
    // — every poll then walks the tree, which is what this panel did before there was one.
    repoWatcher: RepoWatcher? = null,
    // Reports this project's dirty state up to its project tab, which is why the workspace poller
    // leaves the project in front alone: the status below is fresher than a re-walk would find.
    onDirtyChange: (Boolean) -> Unit = {},
    // What the "Project" label over the tree offers as the way to a different project: the recently
    // used ones, minus the ones this window already has a tab for, and a browse for anything else.
    // Handed down from the window rather than read here — which projects are open is the window's
    // business, not one project's.
    recentProjects: List<Path> = emptyList(),
    openProjects: List<Path> = emptyList(),
    onOpenProject: (Path) -> Unit = {},
    onOpenOtherProject: () -> Unit = {},
    fileSearchTrigger: Int = 0,
    findInFilesTrigger: Int = 0,
    findInFileTrigger: Int = 0,
    replaceInFileTrigger: Int = 0,
    jumpToSourceTrigger: Int = 0,
    refreshTrigger: Int = 0,
    saveTrigger: Int = 0,
    snipTrigger: Int = 0,
    findUsagesTrigger: Int = 0,
    renameSymbolTrigger: Int = 0,
    navigateBackTrigger: Int = 0,
    navigateForwardTrigger: Int = 0,
) {
    val repo: GitRepo? = remember(projectPath) { GitRepo.discover(projectPath) }
    DisposableEffect(repo) { onDispose { repo?.close() } }

    var status by remember(projectPath) { mutableStateOf(GitStatus.EMPTY) }
    // The commit HEAD points at, reloaded alongside [status]. A merge, pull, commit or reset moves
    // it without necessarily changing which files are dirty, and that is exactly the case an open
    // diff can't see for itself: its left-hand side is HEAD's copy of the file, read once when it
    // loaded. Null on an unborn branch, and until the first status load.
    var headSha by remember(projectPath) { mutableStateOf<String?>(null) }
    // The watcher generation the last status walk covered, so a poll can tell a quiet tree from one
    // it simply hasn't looked at yet. UNKNOWN until the first walk, and never equal to a real count.
    var polledGeneration by remember(projectPath) { mutableStateOf(RepoWatcher.UNKNOWN) }
    // Whether [status] has been loaded yet, as opposed to still being the empty placeholder. Only
    // reported dirtiness depends on this: switching to a project starts a fresh composition, and
    // announcing the placeholder's "clean" upward would blink the tab's dot off and back on
    // once the real status landed a moment later.
    var statusLoaded by remember(projectPath) { mutableStateOf(false) }
    var stashes by remember(projectPath) { mutableStateOf<List<StashEntry>>(emptyList()) }
    var selectedPaths by remember(projectPath) { mutableStateOf(emptySet<String>()) }
    var commitInFlight by remember(projectPath) { mutableStateOf(false) }
    // How far the running commit has got, for the commit button's progress bar. A StateFlow and
    // not snapshot state because GitRepo reports it from its staging worker threads, which have no
    // business touching composition state; collecting it hands the updates back on this thread.
    val commitProgressFlow = remember(projectPath) { MutableStateFlow<CommitProgress?>(null) }
    val commitProgress by commitProgressFlow.collectAsState()
    // Bumped when a commit or stash lands, which is the commit panel's cue to empty the message
    // field. See CommitPanel: it must not clear on the click, because the click does not always
    // end in a commit.
    var messageClearTrigger by remember(projectPath) { mutableStateOf(0) }
    var stashInFlight by remember(projectPath) { mutableStateOf(false) }
    var refreshing by remember(projectPath) { mutableStateOf(false) }
    // Whether HEAD has a parent to soft-reset back onto, and whether a soft reset is in flight.
    var canSoftReset by remember(projectPath) { mutableStateOf(false) }
    var resetInFlight by remember(projectPath) { mutableStateOf(false) }
    // Whether a per-file revert is running; stands the poll down so it can't load a half-state.
    var revertInFlight by remember(projectPath) { mutableStateOf(false) }
    // The change pending a "Revert file?" confirmation, or null when no dialog is open.
    var pendingRevert by remember(projectPath) { mutableStateOf<FileChange?>(null) }
    // The whole change list pending a "Revert all?" confirmation, or null when no dialog is open.
    var pendingRevertAll by remember(projectPath) { mutableStateOf<List<FileChange>?>(null) }
    // The commit pending a "Revert commit?" confirmation, or null when no dialog is open. Raised
    // from a git history tab; see [askRevertCommit].
    var pendingRevertCommit by remember(projectPath) { mutableStateOf<RevertCommitRequest?>(null) }
    // A failed git mutation (commit/stash) to show in an error dialog, or null when none. Without
    // this the exception would escape the launched coroutine and crash the window (see runGitOp).
    var gitOpError by remember(projectPath) { mutableStateOf<GitOpError?>(null) }
    // Rows awaiting a delete confirmation (the whole Ctrl/Shift selection), or null when no dialog
    // is open.
    var pendingDelete by remember(projectPath) { mutableStateOf<List<File>?>(null) }
    // The pending new-file/directory/package/copy dialog, or null when none is open.
    var pendingEntry by remember(projectPath) { mutableStateOf<TreeEntryDialog?>(null) }
    // What the Usages tab is showing. Set by Alt+F7 and never cleared on its own: a usage list is
    // something the user works through row by row, so it outlives the caret that produced it.
    var usagesView by remember(projectPath) { mutableStateOf(UsagesView()) }
    // The rename in progress: the usages it will rewrite, plus where they came from. Null when no
    // rename dialog is open.
    var pendingRename by remember(projectPath) { mutableStateOf<RenameRequest?>(null) }
    var renameFailure by remember(projectPath) { mutableStateOf<String?>(null) }
    var renameInFlight by remember(projectPath) { mutableStateOf(false) }
    // The file whose revisions the picker is currently offering ("Compare with Revision…"), or null
    // when it's closed. Held here rather than in the tree or the editor because either can raise it.
    var pendingCompare by remember(projectPath) { mutableStateOf<File?>(null) }
    // A just-created directory/package to expand and scroll to in the tree (new files reveal
    // themselves by opening as a tab instead).
    var treeReveal by remember(projectPath) { mutableStateOf<File?>(null) }
    // Whether the file-search popup is currently shown. Bumped open by the double-shift trigger
    // passed in from Main; user closes it by Esc, click-outside, or picking a file.
    var fileSearchOpen by remember(projectPath) { mutableStateOf(false) }
    // Whether the editor shows the git-blame annotate column. A global toggle (applies to whichever
    // file tab is active), driven by the gutter button in the project-tree header.
    var blameEnabled by remember(projectPath) { mutableStateOf(false) }
    // Whether long lines wrap in file tabs and diffs, driven by the toggle beside the tab strip's
    // "+". Deliberately *not* keyed on projectPath — it's a preference about reading code, so it
    // stays put as the user moves between projects — and persisted so it survives a restart.
    var wrapLines by remember { mutableStateOf(Settings.loadWrapLines()) }
    // Bumped on every refresh to force the project tree to rescan from disk
    var fsRefreshKey by remember(projectPath) { mutableStateOf(0) }
    // Re-key on projectPath so switching projects drops the old project's open tabs and edit state.
    val tabsState = remember(projectPath) { TabsState() }
    // The working-file line currently at the top of the open diff's viewport, so F4 ("jump to
    // source") can land the file on the change the user is looking at. The diff view keeps this
    // updated as it scrolls; reset on tab switch so a different diff doesn't inherit a stale line.
    var diffTopLine by remember(projectPath) { mutableStateOf(1) }
    LaunchedEffect(tabsState.selectedId) { diffTopLine = 1 }

    // F4 from a diff opens the real file behind it, at the line in view — from a working-tree diff
    // and from a historic commit's diff alike. For a commit diff the line comes from that revision,
    // so it only points at the same code while the file hasn't moved on much since; it's still a far
    // better landing spot than the top of the file. Ignored for any other tab, and for diffs whose
    // working file is gone (removed/missing, or deleted by the commit being read). The trigger is a
    // window-level counter that outlives this per-project composition, so compare against its
    // value at composition — not 0 — or switching projects after any F4 would re-fire the jump.
    val jumpToSourceBaseline = remember(projectPath) { jumpToSourceTrigger }
    LaunchedEffect(jumpToSourceTrigger) {
        if (jumpToSourceTrigger <= jumpToSourceBaseline) return@LaunchedEffect
        val file = jumpToSourceTarget(tabsState.selectedTab) ?: return@LaunchedEffect
        tabsState.openAt(Tab.FileView(file), diffTopLine)
    }

    // Ctrl+Alt+Left / Ctrl+Alt+Right navigation history between files
    val navigateBackBaseline = remember(projectPath) { navigateBackTrigger }
    LaunchedEffect(navigateBackTrigger) {
        if (navigateBackTrigger <= navigateBackBaseline) return@LaunchedEffect
        tabsState.navigateBack()
    }
    val navigateForwardBaseline = remember(projectPath) { navigateForwardTrigger }
    LaunchedEffect(navigateForwardTrigger) {
        if (navigateForwardTrigger <= navigateForwardBaseline) return@LaunchedEffect
        tabsState.navigateForward()
    }
    // nop's own record of what this project's files have held, kept beside the other per-project
    // derived data. Every buffer the store hands out reports its saves into it — see [LocalHistory].
    val localHistory = remember(projectPath) {
        LocalHistory(Settings.projectDataDir(projectPath).resolve("localhistory"))
    }
    val editStore = remember(projectPath) { FileEditStore(localHistory) }
    val scope = rememberCoroutineScope()

    // Split ratios are persisted globally (not per-project): the layout preference is about the
    // user's preferred shape of the app, not the specific repo. Load once at composition start;
    // unsaved values fall back to the original defaults.
    val savedRatios = remember { Settings.loadSplitRatios() }
    var hRatio by remember { mutableStateOf(savedRatios.horizontal ?: 0.22f) }
    // Share of the area right of the project tree given to the viewer; the rest is the tool
    // panel (Commit/Search/Usages/Stash/Preview/Run) on the window's right edge.
    var toolsRatio by remember { mutableStateOf(savedRatios.tools ?: 0.68f) }
    // The divider between a diff's before/after halves — even by default, draggable to favour
    // whichever side is being read.
    var diffRatio by remember { mutableStateOf(savedRatios.diff ?: 0.5f) }
    // Inside the tool region: how much of it the session pane (agents, terminals, runs) takes, with
    // the tool panel beside it. Slightly past half by default — the pane holding a full-screen TUI
    // is the one that suffers first when it is short of columns.
    var sessionRatio by remember { mutableStateOf(savedRatios.session ?: 0.58f) }
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(hRatio, toolsRatio, diffRatio, sessionRatio) }
            .debounce(500)
            .distinctUntilChanged()
            .collectLatest { (h, t, d, s) -> Settings.saveSplitRatios(h, t, d, s) }
    }
    // Whether the tool panel is folded away, leaving the whole region to the session.
    var toolsCollapsed by remember { mutableStateOf(Settings.loadToolsCollapsed()) }
    LaunchedEffect(toolsCollapsed) { Settings.saveToolsCollapsed(toolsCollapsed) }

    // Pull external edits into cached editor buffers. The buffer behind a file/diff tab is read from
    // disk once and then cached for the whole session, so a file changed outside nop (another editor,
    // a branch switch, a pull, an agent) would otherwise render frozen until restart — refresh and
    // reopening the tab both reuse the same buffer. Only buffers with no unsaved in-app edits are
    // reloaded; a dirty buffer's pending work always wins until it's saved. Disk reads run off the UI
    // thread; the buffer writes run on it.
    suspend fun reconcileEdits() {
        val openEdits = editStore.snapshot()
        if (openEdits.isEmpty()) return
        val updates = withContext(Dispatchers.IO) {
            openEdits.mapNotNull { edit -> edit.diskTextIfDivergedAndClean()?.let { edit to it } }
        }
        for ((edit, diskText) in updates) {
            // Re-check on the UI thread: the user may have started typing in the gap between the disk
            // read and here, in which case their unsaved edits take precedence over the disk copy.
            // Gate on hasUserEdit (not isModified) so a buffer that merely drifted programmatically
            // still adopts disk — see FileEdit.diskTextIfDivergedAndClean.
            if (!edit.hasUserEdit) edit.adoptDiskText(diskText)
        }
    }

    suspend fun reloadStatus() {
        if (repo != null) {
            reconcileEdits()
            val fresh = withContext(Dispatchers.IO) { repo.loadStatus() }
            val freshStashes = withContext(Dispatchers.IO) {
                runCatching { repo.stashList() }.getOrDefault(emptyList())
            }
            headSha = withContext(Dispatchers.IO) { runCatching { repo.headSha() }.getOrNull() }
            status = fresh
            statusLoaded = true
            stashes = freshStashes
            canSoftReset = withContext(Dispatchers.IO) {
                runCatching { repo.canSoftResetHead() }.getOrDefault(false)
            }
            // Default-select every change after a reload
            selectedPaths = fresh.changes.map { it.path }.toSet()
            // A status reload usually means files appeared / disappeared too (commit, stash, pop)
            // — re-walk the project tree so the sidebar matches the filesystem.
            fsRefreshKey += 1
        }
    }

    // Hand this project's dirty state to the rail. Cheap and always current — it is the status the
    // panel has already loaded — which is what lets the workspace poller skip the active project
    // instead of walking the same tree a second time on its own timer.
    LaunchedEffect(status.isClean, statusLoaded, repo) {
        if (repo != null && statusLoaded) onDirtyChange(!status.isClean)
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

    // F5 reloads what the user is looking at: the active tab re-reads its content from disk (which
    // is what makes an open diff pick up a commit, pull or agent edit without being closed and
    // reopened), and git status reloads alongside it. Baselined against the composition-time value
    // for the same reason as the other window-level triggers — the counter outlives this
    // per-project composition, so a project switch must not replay the session's last F5.
    val refreshBaseline = remember(projectPath) { refreshTrigger }
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger <= refreshBaseline) return@LaunchedEffect
        tabsState.selectedId?.let { tabsState.requestReload(it) }
        refresh()
    }

    // Gentle background poll. Unlike reloadStatus() it preserves the user's commit selection —
    // only dropping paths that vanished and auto-selecting changes that newly appeared — and it
    // touches no state (so no recomposition, no tree re-walk) when git state is unchanged. It
    // also stands down while a commit/stash/manual-refresh is in flight so it can't clobber them.
    suspend fun pollStatus() {
        if (repo == null || commitInFlight || stashInFlight || refreshing || revertInFlight) return
        // Reconcile before the git-state check below: an external edit to an already-modified file
        // leaves its git status unchanged, so the early return there would otherwise skip the reload.
        // This stays outside the watcher gate too — it only stats the open tabs, and one of them can
        // be a file the watcher never reports on, sitting in an ignored directory.
        reconcileEdits()
        // Ask the watcher whether this tree has moved before walking it, and read the counter first
        // so a change landing mid-walk is picked up on the next tick rather than being written off as
        // covered. UNKNOWN means the watcher cannot vouch for the tree, so we walk.
        val generation = repoWatcher?.generation(repo.rootDir) ?: RepoWatcher.UNKNOWN
        if (generation != RepoWatcher.UNKNOWN && generation == polledGeneration) return
        val fresh = withContext(Dispatchers.IO) { runCatching { repo.loadStatus() }.getOrNull() } ?: return
        val freshStashes = withContext(Dispatchers.IO) { runCatching { repo.stashList() }.getOrDefault(stashes) }
        // Read HEAD too: a merge or pull that leaves the same files dirty shows up nowhere in the
        // status, and the open diffs are comparing against the commit it used to be.
        val freshHead = withContext(Dispatchers.IO) { runCatching { repo.headSha() }.getOrNull() }
        polledGeneration = generation
        if (fresh == status && freshStashes == stashes && freshHead == headSha) return
        headSha = freshHead
        val previousPaths = status.changes.map { it.path }.toSet()
        val freshPaths = fresh.changes.map { it.path }.toSet()
        val appeared = freshPaths - previousPaths
        // Keep selections the user still cares about, drop vanished ones, default-select new ones.
        selectedPaths = (selectedPaths intersect freshPaths) + appeared
        status = fresh
        statusLoaded = true
        stashes = freshStashes
        fsRefreshKey += 1
    }

    val rootPath = repo?.rootDir ?: projectPath
    // Jump-to-source index: load the previous run's cache for instant clicks while we rebuild,
    // then swap in the fresh version (and persist it) once the walk finishes. The index lives
    // under ~/.config/nop/projects/<slug>/ so the project tree stays free of derived files.
    var symbolIndex by remember(rootPath) { mutableStateOf(SymbolIndex()) }
    var fileIndex by remember(rootPath) { mutableStateOf(FileIndex()) }
    // How often each file has been opened, so the double-shift box can surface the most-used files
    // in its top slots. Counts every genuine open (tree click, search jump, double-shift pick) via
    // TabsState.onFileOpened; session restore opts out. Loaded per project, persisted on each open.
    val accessFreq = remember(rootPath) { mutableStateOf(AccessFrequency()) }
    LaunchedEffect(rootPath) {
        val freqFile = Settings.projectDataDir(rootPath).resolve("access-counts.tsv")
        accessFreq.value = withContext(Dispatchers.IO) { AccessFrequency.load(freqFile) }
    }
    LaunchedEffect(tabsState, rootPath) {
        tabsState.onFileOpened = onOpened@{ file ->
            val root = rootPath.toFile().toPath().toAbsolutePath().normalize()
            val rel = runCatching {
                root.relativize(file.toPath().toAbsolutePath().normalize())
                    .toString().replace(File.separatorChar, '/')
            }.getOrNull() ?: return@onOpened
            // Only count files inside the project (matches FileIndex's relative paths).
            if (rel.isEmpty() || rel.startsWith("..")) return@onOpened
            val updated = accessFreq.value.record(rel)
            accessFreq.value = updated
            val freqFile = Settings.projectDataDir(rootPath).resolve("access-counts.tsv")
            scope.launch { withContext(Dispatchers.IO) { AccessFrequency.save(freqFile, updated) } }
        }
    }
    // Seed the on-disk cache into memory only once per project; later re-runs (driven by
    // fsRefreshKey) already hold the index and shouldn't re-read a cache that can be large.
    var indexCacheSeeded by remember(rootPath) { mutableStateOf(false) }
    // Whether the symbol cache on disk is one this nop can read. A cache written by an older
    // version means something different line by line, so [SymbolIndex.load] rejects it outright —
    // and that has to force a rebuild, because the freshness probe below asks only whether the
    // *project* has changed. Without this an upgrade would leave every existing project with an
    // empty jump index and no event that would ever refill it.
    var symbolCacheUsable by remember(rootPath) { mutableStateOf(false) }
    // Re-key on fsRefreshKey so files added/removed while nop stays open show up in the
    // double-shift search and jump-to-source. fsRefreshKey bumps on manual Refresh and whenever
    // the git poll sees the working tree change (e.g. a new untracked file) — exactly the moments
    // the index can go out of date. The staleness probe below gates the actual rebuild, so an
    // unchanged tree pays only a stat-only walk per refresh, not a full re-read of every file.
    LaunchedEffect(rootPath, fsRefreshKey) {
        val indexFile = Settings.projectDataDir(rootPath).resolve("index.tsv")
        val filesIndexFile = Settings.projectDataDir(rootPath).resolve("files.txt")
        if (!indexCacheSeeded) {
            val cachedSymbols = withContext(Dispatchers.IO) { SymbolIndex.load(indexFile) }
            if (cachedSymbols != null) {
                symbolCacheUsable = true
                if (cachedSymbols.size > 0) symbolIndex = cachedSymbols
            }
            val cachedFiles = withContext(Dispatchers.IO) { FileIndex.load(filesIndexFile) }
            if (cachedFiles.files.isNotEmpty()) fileIndex = cachedFiles
            // Set only after the load completes: if a refresh cancels this effect mid-seed, the
            // relaunch should re-seed rather than skip with a half-loaded index.
            indexCacheSeeded = true
        }

        // Skip the full rebuild (walk + read + regex every file) when the on-disk cache is still
        // fresh — i.e. nothing under the project changed since the cache was written. The
        // freshness probe is a stat-only walk, far cheaper than a rebuild, so a project that
        // hasn't changed since the last index pays almost nothing per refresh. Gated on the file
        // index alone (not symbols): files.txt and index.tsv are written together, so a populated
        // file cache implies the symbol cache is as current as the project allows.
        val cacheReady = fileIndex.files.isNotEmpty() && symbolCacheUsable
        val cacheStamp = withContext(Dispatchers.IO) {
            runCatching { Files.getLastModifiedTime(filesIndexFile).toMillis() }.getOrDefault(0L)
        }
        val cacheFresh = cacheReady && cacheStamp > 0L && withContext(Dispatchers.IO) {
            !Indexer.isStale(rootPath, cacheStamp, fileIndex.files.size)
        }
        if (cacheFresh) return@LaunchedEffect

        val freshSymbols = withContext(Dispatchers.IO) { Indexer.build(rootPath) }
        symbolIndex = freshSymbols
        val freshFiles = withContext(Dispatchers.IO) { FileIndex.build(rootPath) }
        fileIndex = freshFiles
        withContext(Dispatchers.IO) {
            SymbolIndex.save(indexFile, freshSymbols)
            FileIndex.save(filesIndexFile, freshFiles)
        }
        // What we just wrote is by definition current, so later refreshes can trust the probe again.
        symbolCacheUsable = true
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

    // The background poll, and the one place that must never stop running: it is what keeps the
    // commit list honest *and* what pulls external edits into open buffers (pollStatus reconciles
    // before it looks at git). An exception escaping the loop body would end the coroutine, and with
    // it every future poll for the life of this project's composition — git state and every open
    // editor frozen together, silently, until the project is switched or nop is restarted. One
    // failed tick is not worth that, so each is isolated and logged; cancellation still gets out,
    // or switching projects would leak this loop.
    LaunchedEffect(repo) {
        suspend fun tick(what: String, body: suspend () -> Unit) {
            try {
                body()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.error("git poll: $what failed", t)
            }
        }
        tick("initial status load") { reloadStatus() }
        while (true) {
            delay(GIT_POLL_INTERVAL_MS)
            tick("poll") { pollStatus() }
        }
    }

    // Open the search popup whenever Main bumps the trigger (double-Shift). The counter is
    // window-level and outlives this per-project composition, so only bumps past the value seen
    // at composition count — else the popup would re-open on every project switch after the
    // session's first double-Shift.
    val fileSearchBaseline = remember(projectPath) { fileSearchTrigger }
    LaunchedEffect(fileSearchTrigger) {
        if (fileSearchTrigger > fileSearchBaseline) fileSearchOpen = true
    }

    // This project's terminals: the launcher runs behind the Run tab, and the shells behind the
    // terminal tabs at the head of the tool strip. Not owned here. They come from a store that lives
    // as long as nop does, so a project switch leaves them running with their scrollback, and coming
    // back finds them where they were. See TerminalStore for what ends them.
    val projectTerminals = remember(projectPath, rootPath) {
        TerminalStore.of(root = rootPath, project = projectPath) {
            TerminalStore.Terminals(
                // The first shell is opened up front so the tab is there from the start. It costs
                // nothing until it is looked at, because a session spawns no PTY until the panel
                // asks it for a widget.
                shells = RunSessions().apply { openShell(rootPath.toFile()) },
                // The run tabs the project had last time come back with it, not running — see
                // RunSessions.restore. Done here rather than in an effect so the strip is right on
                // its first frame instead of growing tabs a moment after the window opens.
                runs = RunSessions().apply { restore(Settings.loadOpenRuns(rootPath), rootPath.toFile()) },
            )
        }
    }
    val runSessions = projectTerminals.runs
    // The tool region holds two selections, one per side — see [ToolTabs]. This is the right-hand
    // one (Commit by default). Ctrl+Shift+F bumps findInFilesTrigger; we flip the tool panel to
    // Search and forward the trigger into SearchPanel so it requests focus on its input field.
    var toolTab by remember(projectPath) { mutableStateOf(ToolTab.Commit) }

    /**
     * Which collection the session pane on the left is drawing, or null for the agent picker.
     *
     * Null rather than Agent at the start, and they are not the same thing: the picker is what an
     * empty pane holds, and a session restored into the strip is deliberately not selected by the
     * restore (see [AgentSessions.restore]).
     */
    var sessionTab by remember(projectPath) { mutableStateOf(projectTerminals.sessionTab) }

    /**
     * Shows [tab] on the right, unfolding the tool panel if it was collapsed.
     *
     * Every external trigger goes through here rather than assigning the selection directly. A
     * Ctrl+Shift+F that flipped the hidden panel to Search would look, from the user's side, like
     * the shortcut doing nothing at all.
     */
    fun showTool(tab: ToolTab) {
        toolTab = tab
        toolsCollapsed = false
    }

    /**
     * What a click on a tool tab does: shows it, or folds the panel away when it is the one already
     * showing.
     *
     * A tab is a claim on half the region, and the region's other half is a full-screen TUI — so the
     * gesture that asks for the diff has to be the gesture that gives those columns back, without
     * hunting for the chevron at the far end of the strip. Collapsed, the selection is kept but
     * drawn as unselected (see [ToolTabs]), so the next click on that tab brings the same panel back
     * rather than a different one.
     */
    fun toggleTool(tab: ToolTab) {
        if (toolTab == tab && !toolsCollapsed) toolsCollapsed = true else showTool(tab)
    }

    /**
     * Shows [tab]'s collection in the session pane on the left. Written into the store as well, so
     * that coming back to this project shows the same collection. Its terminals are still running.
     */
    fun showSession(tab: ToolTab) {
        sessionTab = tab
        projectTerminals.sessionTab = tab
    }

    /** What the Diff tab dates its "session" base from — see [DiffPanel]. */
    var diffBase by remember(projectPath) { mutableStateOf(DiffBase.Session) }
    var searchFieldFocusTrigger by remember(projectPath) { mutableStateOf(0) }
    val searchQueryState = remember(rootPath) { TextFieldState() }
    // Held out here for the same reason, and a sharper one: the panel is composed only while its
    // tab is selected, so a message remembered inside it was lost the moment anything flipped the
    // panel away — which now includes opening a markdown file or starting a script. Persisted via
    // Settings so an unsaved draft survives switching between tabs, switching projects, or app restarts.
    val commitMessageState = remember(rootPath) {
        TextFieldState(Settings.loadCommitMessageDraft(rootPath))
    }
    LaunchedEffect(rootPath, commitMessageState) {
        snapshotFlow { commitMessageState.text.toString() }
            .distinctUntilChanged()
            .collect { draft ->
                Settings.setCommitMessageDraftMemory(rootPath, draft)
            }
    }
    LaunchedEffect(rootPath, commitMessageState) {
        snapshotFlow { commitMessageState.text.toString() }
            .drop(1)
            .debounce(300)
            .distinctUntilChanged()
            .collectLatest { draft ->
                withContext(Dispatchers.IO) {
                    Settings.saveCommitMessageDraft(rootPath, draft)
                }
            }
    }
    DisposableEffect(rootPath, commitMessageState) {
        onDispose {
            Settings.saveCommitMessageDraft(rootPath, commitMessageState.text.toString())
        }
    }
    // Window-level counter; only bumps past the composition-time value count, else switching
    // back to a project after the session's first Ctrl+Shift+F would land on Search, not Commit.
    val findInFilesBaseline = remember(projectPath) { findInFilesTrigger }
    LaunchedEffect(findInFilesTrigger) {
        if (findInFilesTrigger > findInFilesBaseline) {
            showTool(ToolTab.Search)
            searchFieldFocusTrigger += 1
        }
    }

    // Written back whenever the strip changes — a run started, closed or renamed. Keyed on the
    // rows themselves rather than on a count, so a rename is saved too; recomputing the list is
    // what reads the snapshot state that makes this effect re-run at all.
    val openRunRows = runSessions.sessions.mapNotNull { it.asOpenRun() }
    LaunchedEffect(rootPath, openRunRows) {
        withContext(Dispatchers.IO) { Settings.saveOpenRuns(rootPath, openRunRows) }
    }
    // The git logs behind the History tabs. Owned here for the same reason as the runs: the tool
    // panel composes one tab at a time, so a log remembered inside the panel would lose its scroll
    // and its expanded commit every time the user opened one of the diffs it sent them to.
    val historySessions = remember(projectPath) {
        // The logs the project had last time come back with it, nothing selected — see
        // HistorySessions.restore. Done here rather than in an effect so the strip is right on its
        // first frame instead of growing tabs a moment after the window opens. A project with no
        // repo restores none: there is no log to read without one.
        HistorySessions().apply {
            val root = repo?.rootDir?.toFile()
            if (root != null) restore(Settings.loadOpenHistories(rootPath).map(::File), root)
        }
    }
    // Written back whenever the strip changes — a log opened or closed. Keyed on the paths, which
    // is also what reads the snapshot state that makes this effect re-run.
    val openHistoryPaths = historySessions.sessions.map { it.file.absolutePath }
    LaunchedEffect(rootPath, openHistoryPaths) {
        withContext(Dispatchers.IO) { Settings.saveOpenHistories(rootPath, openHistoryPaths) }
    }
    // The shells behind the terminal tabs at the head of the tool strip. A separate list from the
    // launcher runs above — those belong to the Run tab and come and go with the scripts that
    // started them, while these are the project's own terminals.
    val terminals = projectTerminals.shells
    // The vendor agent sessions behind the Agent tab. The one collection on this screen that is not
    // owned here: it comes from a store that lives as long as nop does, because a composition is the
    // wrong lifetime for a model half-way through a refactor — looking at another project, or moving
    // this tab to another window, tears this composition down and used to take every running agent
    // with it. See AgentSessionStore for what ends a session now (its tab, its project, or nop).
    val agentSessions = remember(projectPath, rootPath) {
        AgentSessionStore.of(root = rootPath, project = projectPath)
    }
    val snipBaseline = remember(projectPath) { snipTrigger }
    LaunchedEffect(snipTrigger) {
        if (snipTrigger > snipBaseline) {
            val session = agentSessions.selected?.session
                ?: terminals.selected?.session
                ?: agentSessions.sessions.firstOrNull()?.session
            session?.snipArea()
        }
    }
    // This project's earlier agent sessions, for the picker.
    var pastAgentSessions by remember(projectPath) { mutableStateOf(emptyList<PastSession>()) }
    // The configured accounts. Window-level state rather than per-project: the accounts are global,
    // and reloading here is what makes a change in the settings dialog show up in the picker.
    var agentConfig by remember { mutableStateOf<AgentConfig?>(null) }
    LaunchedEffect(Unit) {
        if (agentConfig == null) agentConfig = withContext(Dispatchers.IO) { Accounts.load() }
    }
    DisposableEffect(Unit) {
        val unsubscribe = Accounts.addChangeListener { next -> agentConfig = next }
        onDispose { unsubscribe() }
    }
    val agentAccounts: List<Account> = agentConfig?.accounts.orEmpty()
    // Who a session hands its work to when the account running it runs out. Pushed into the
    // collection rather than passed to each session, because the sessions outlive this composition
    // and the accounts are edited in a dialog that is open while they run — re-pushed whenever the
    // settings change, so a nomination made mid-session is the one that session uses.
    LaunchedEffect(agentSessions, agentAccounts) {
        agentSessions.handoverTarget = { from -> agentAccounts.handoverTarget(from) }
    }
    // Two sources, because "the sessions on this project" is not the same set as "the sessions nop
    // ran on this project". nop's own event logs know the account and carry the name the user gave
    // a tab; the vendor's store is where a `claude` run from a shell in this checkout ends up, and
    // nothing about that session exists on nop's side at all. Read together, the picker lists the
    // work rather than the subset of it that happened to go through nop.
    //
    // nop's row wins a tie, which is every session it ran: it is the same conversation either way,
    // and only that side knows which configured account is behind it.
    //
    // Re-read whenever the conversations running in the strip change, and whenever the accounts do,
    // since they are what says which stores to read. The conversations, not the count of tabs: a
    // handover, a reopen or a `/clear` keeps the tab and moves it to another conversation, and the
    // one it left is exactly what the picker is for. Keyed on the count alone, the list went on
    // hiding the conversation a handover had just left, as though it were still running in its tab.
    // And re-read each time the picker comes up, which also finds what a shell has filed since.
    val runningConversations = agentSessions.sessions.map { it.sessionId to it.run.nativeSessionId }
    val pickerShowing = agentSessions.selected == null
    LaunchedEffect(projectPath, runningConversations, pickerShowing, agentAccounts) {
        pastAgentSessions = withContext(Dispatchers.IO) {
            val own = EventLog.sessions(rootPath)
            // Only a row that can actually be reopened is allowed to stand in for the vendor's own
            // copy of the same conversation. nop's side knows more about a session — which
            // configured account, and the name the user gave the tab — but a row it cannot place
            // knows less than the transcript does, and hiding the transcript behind it is how a
            // conversation that is sitting right there on disk becomes unreachable. Which is what a
            // log written before the store was recorded is: see PastSession.accountIn.
            val seen = own.filter { it.accountIn(agentAccounts) != null }
                .mapNotNull { it.lastNativeSessionId }
                .toSet()
            val native = NativeSessions.claude(rootPath, NativeSessions.stores(agentAccounts))
                .filterNot { it.sessionId in seen }
            (own + native).sortedByDescending { it.lastActiveAt }
        }
    }
    // The agent tabs this project had when nop last exited, put back and then kept up to date.
    //
    // Restore first and watch afterwards, in one effect, exactly as the tab strip below does: two
    // effects would race, and the one that lost would write the empty strip over the rows the other
    // was about to read. Waits for the accounts for the same reason — a row names the account whose
    // CLI to run, an empty list is "not read yet" as much as "none configured", and saving over the
    // file before they arrive would cost the user every tab in it.
    LaunchedEffect(rootPath, agentSessions, agentAccounts) {
        if (agentAccounts.isEmpty()) return@LaunchedEffect
        val saved = withContext(Dispatchers.IO) { Settings.loadOpenAgents(rootPath) }
        // Guarded inside the store's collection, which outlives this composition: coming back to the
        // project must not restore a second copy of every tab. See AgentSessions.restore.
        agentSessions.restore(saved, rootPath.toFile(), agentAccounts)
        // Not debounced, unlike the tab strip's: a session opens, closes, is renamed or learns its
        // own name a handful of times an hour, and each of those is the only chance to write it down
        // before nop is killed rather than closed.
        snapshotFlow { agentSessions.sessions.mapNotNull { it.asOpenAgent() } }
            .distinctUntilChanged()
            .collectLatest { rows ->
                withContext(Dispatchers.IO) { Settings.saveOpenAgents(rootPath, rows) }
            }
    }
    // How much room the usage strip at the bottom of the window is taking. Terminals are
    // heavyweight AWT components drawn over everything Compose paints, so each one has to stop
    // short of the strip by exactly this much or the strip is simply not on screen.
    var usageStripHeight by remember { mutableStateOf(0.dp) }
    // And how wide the tool region is, which is how wide the strip draws itself: the accounts want
    // one line, and one line's worth of room is the agent pane plus the panel beside it. Measured
    // rather than derived from the two split ratios, which would have to be re-derived here every
    // time either of them moved.
    var toolRegionWidth by remember { mutableStateOf(0.dp) }
    // Whether the accounts dialog is up.
    // Once per nop run, not once per opening: re-asking every time turns a lock into a nuisance and
    // trains the user to keep the dialog open, which is the opposite of what it is for.
    var showAccounts by remember { mutableStateOf(false) }
    // Model lists, per account, fetched once each from the provider. Claude's comes from the
    // Anthropic Models API with the account's own token, so it reflects what that account may
    // actually use rather than a list baked in here to go stale.
    val agentModels = remember { mutableStateMapOf<String, List<String>>() }
    // Each account's quota, refreshed on a background coroutine. Deliberately only a number to look
    // at: nothing here interrupts a running TUI. Being told at 80% that the session is over is
    // worse than seeing the number and deciding at the next natural stopping point — a quota wall
    // the CLI itself hits is different, and Quota handles that, because there the session is
    // already finished.
    val agentUsage = remember { mutableStateMapOf<String, UsageReading>() }
    LaunchedEffect(agentAccounts) {
        if (agentAccounts.isEmpty()) return@LaunchedEffect
        while (true) {
            for (account in agentAccounts) {
                // Contained per account. A poller is background convenience; nothing it can hit —
                // a dead endpoint, a credentials file someone is mid-way through rewriting — is
                // worth taking the window down for, and an uncaught throw here would.
                runCatching {
                    agentUsage[account.name] = withContext(Dispatchers.IO) { Usage.read(account) }
                    // Only until it answers: a live model list is a fact about the account, not
                    // about the moment, and the providers that can be asked for one are asked
                    // through a CLI start or an HTTPS GET that the poll should not repeat forever.
                    if (account.name !in agentModels) {
                        val models = withContext(Dispatchers.IO) { Usage.discoverModels(account) }
                        if (models.isNotEmpty()) agentModels[account.name] = models
                    }
                }.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    Log.warn("could not read usage for ${account.name}: $failure")
                }
            }
            // Sooner while a reading is stale or spent, so a rate-limited or reset account recovers
            // within a minute rather than five. Usage's own gate decides whether a poll actually asks.
            val stale = agentAccounts.any {
                agentUsage[it.name]?.note != null || agentUsage[it.name]?.looksSpent() == true
            }
            delay(if (stale) USAGE_RETRY_INTERVAL_MS else USAGE_POLL_INTERVAL_MS)
        }
    }
    LaunchedEffect(showAccounts) {
        if (!showAccounts) return@LaunchedEffect
        for (account in agentAccounts) {
            Usage.invalidate(account)
            val reading = withContext(Dispatchers.IO) { runCatching { Usage.read(account) }.getOrNull() }
            if (reading != null) agentUsage[account.name] = reading
        }
    }
    // The other half of believing a quota wall: the provider's own number for what is left. Read
    // out of the poller's map at the moment a session asks, so it is the freshest reading there is
    // rather than whatever had arrived when the tab was opened. See [UsageReading.looksSpent].
    SideEffect {
        agentSessions.hasRunOut = { account ->
            val reading = listOfNotNull(agentUsage[account.name], Usage.liveCodexReading(account.homePath))
                .maxByOrNull { it.asOf ?: java.time.Instant.EPOCH }
            // Written down, because a handover on "its usage reading is spent" used to leave no trace
            // of what the reading said — the 14:11 handover in hermes acted on one that still read
            // spent after the reset, and nothing on disk could say which window, or how old.
            reading?.looksSpent()?.also { spent ->
                if (spent) Log.info("usage for ${account.name} reads spent: $reading")
            }
        }
        agentSessions.spentUntil = { account ->
            val reading = listOfNotNull(agentUsage[account.name], Usage.liveCodexReading(account.homePath))
                .maxByOrNull { it.asOf ?: java.time.Instant.EPOCH }
            reading?.spentUntil()
        }
        agentSessions.onSessionClosed = { account ->
            scope.launch {
                val reading = withContext(Dispatchers.IO) {
                    Usage.invalidate(account)
                    runCatching { Usage.read(account) }.getOrNull()
                }
                if (reading != null) agentUsage[account.name] = reading
            }
        }
    }
    // One shared Swing CardLayout panel hosts every terminal widget (see TerminalView for why a
    // SwingPanel-per-run can't work). Remembered beside the sessions so it — and the live PTYs in
    // it — outlive visits to the other tool tabs.
    val terminalCards = remember(projectPath) { JPanel(CardLayout()) }

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

    // Recently used commit (and stash) messages, offered in a reuse dropdown above the message
    // field. Loaded from disk, then merged with the repo's git log on open so the dropdown is
    // useful immediately and picks up commits made outside nop. Persisting our own copy (rather
    // than reading git log live) means a message survives a soft reset of the very commit you
    // might want to reuse, and outlives the stash it described once that stash is popped.
    var recentMessages by remember(rootPath) { mutableStateOf(Settings.loadRecentCommitMessages(rootPath)) }
    LaunchedEffect(rootPath, repo) {
        if (repo != null) {
            val log = withContext(Dispatchers.IO) { repo.recentCommitMessages() }
            val merged = (recentMessages + log).distinct().take(COMMIT_MESSAGE_HISTORY_CAP)
            if (merged != recentMessages) {
                recentMessages = merged
                withContext(Dispatchers.IO) { Settings.saveRecentCommitMessages(rootPath, merged) }
            }
        }
    }
    // Record a just-used message (from a commit or a stash) at the top of the reuse list, deduped.
    suspend fun rememberMessage(message: String) {
        if (message.isBlank()) return
        val next = (listOf(message) + recentMessages).distinct().take(COMMIT_MESSAGE_HISTORY_CAP)
        recentMessages = next
        withContext(Dispatchers.IO) { Settings.saveRecentCommitMessages(rootPath, next) }
    }

    // Tab-strip persistence: restore on project open, then debounce-save on every change. Saved
    // alongside the symbol/file indexes under the project's data dir. Restore happens before
    // we subscribe to changes, and we drop the first emission so the restore itself doesn't
    // immediately trigger a no-op save.
    val tabsFile = remember(rootPath) { Settings.projectDataDir(rootPath).resolve("tabs.tsv") }
    // Guards the restore against the flush below: switching projects tears this composition down and
    // builds the new project's, and the outgoing flush must not race the incoming restore's own read.
    var tabsRestored by remember(rootPath) { mutableStateOf(false) }
    LaunchedEffect(rootPath, tabsState) {
        val saved = withContext(Dispatchers.IO) { TabsPersistence.load(tabsFile) }
        TabsPersistence.restore(tabsState, saved, repo?.rootDir?.toFile())
        tabsRestored = true
        // Groups are part of the strip's shape, so renaming, collapsing, reordering or re-homing one
        // has to trip the save the same way opening a tab does.
        snapshotFlow { tabsState.snapshot() }
            .drop(1)
            .debounce(500)
            .distinctUntilChanged()
            .collectLatest { snapshot ->
                withContext(Dispatchers.IO) { TabsPersistence.save(tabsFile, snapshot) }
            }
    }
    // The debounced save above dies with this composition, so anything done in the half-second before
    // switching projects (or closing the window) would never reach disk — which is how a group made
    // and then switched away from came back missing. Write the strip out once more on the way out.
    // Synchronous on purpose: a coroutine launched here would be cancelled by the same teardown, and
    // the file is a handful of lines.
    DisposableEffect(rootPath, tabsFile) {
        onDispose { if (tabsRestored) TabsPersistence.save(tabsFile, tabsState.snapshot()) }
    }

    // The HEAD the open diffs were last read against, so the sync below can tell "the same files
    // are dirty" from "the same files are dirty against the same commit".
    var diffsReadAtHead by remember(projectPath) { mutableStateOf<String?>(null) }
    // Keep the open working-tree diffs pointed at the repository as it is now. A commit, merge,
    // pull, stash or revert moves both of a diff's sides: HEAD gains commits, a change switches
    // kind, or the change goes away entirely — and a tab whose change has gone is showing a diff
    // the repository no longer has, with no fresher version of it to fall back to, so it closes.
    // The rest re-read. Runs after the restore too (hence tabsRestored), so a diff reopened from
    // last session's strip is checked against today's status rather than the one it was saved with.
    LaunchedEffect(status, headSha, statusLoaded, tabsRestored) {
        if (repo == null || !statusLoaded) return@LaunchedEffect
        val headMoved = headSha != diffsReadAtHead
        diffsReadAtHead = headSha
        val sync = syncDiffTabs(tabsState.tabs, status, headMoved) { tab ->
            // Unsaved work in the shared buffer outranks git's opinion that the file is clean:
            // disk matching HEAD says nothing about what the user has typed since.
            editStore.peek(Tab.FileView(File(tab.repoRoot, tab.change.path)).id)?.hasUserEdit == true
        }
        for (tab in sync.reload) {
            tabsState.replace(tab)
            tabsState.requestReload(tab.id)
        }
        for (tab in sync.close) {
            editStore.close(tab.id)
            tabsState.close(tab.id)
        }
    }

    // Close any open tabs that point at the given file or anything under it (when it's a dir).
    // Saves the user from typing into a buffer whose underlying file just got removed.
    // [includeHistoryTabs] false spares the log panels — the local-history tab here, and the git
    // logs in the tool strip — whose subject is a *path* rather than its content: a log reads fine
    // for a file that has just stopped existing, and closing the one the user is acting from (see
    // performRestore) would yank the panel out from under them.
    fun closeTabsUnder(target: File, includeHistoryTabs: Boolean = true) {
        val targetPath = target.absolutePath
        val toClose = tabsState.tabs.filter { tab ->
            val tabFile: File? = when (tab) {
                is Tab.FileView -> tab.file
                is Tab.Diff -> File(tab.repoRoot, tab.change.path)
                is Tab.CommitDiff -> File(tab.repoRoot, tab.file.path)
                is Tab.RevisionDiff -> tab.file
                is Tab.LocalHistory -> tab.file.takeIf { includeHistoryTabs }
                is Tab.LocalDiff -> tab.file
            }
            val p = tabFile?.absolutePath ?: return@filter false
            p == targetPath || p.startsWith("$targetPath${File.separator}")
        }
        for (tab in toClose) {
            editStore.close(tab.id)
            tabsState.close(tab.id)
        }
        if (includeHistoryTabs) historySessions.closeUnder(target)
    }

    fun performDelete(targets: List<File>) {
        if (targets.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) { targets.forEach { it.deleteRecursively() } }
            targets.forEach { closeTabsUnder(it) }
            // Drop the removed rows from the tree even in a non-git project (reloadStatus is a no-op
            // without a repo), then reload git status for the survivors' colours.
            fsRefreshKey += 1
            reloadStatus()
        }
    }

    // Discard a single file's local changes, restoring it to its last committed state (or deleting
    // it when it's a brand-new file). The on-disk content just changed, so any open editor buffer
    // for it must drop its in-app edits — otherwise a later save would re-introduce the very changes
    // the user reverted. A file that no longer exists has its tabs closed outright.
    fun performRevert(change: FileChange) {
        if (repo == null || revertInFlight) return
        scope.launch {
            revertInFlight = true
            try {
                withContext(Dispatchers.IO) { runCatching { repo.revertFile(change) } }
                val reverted = File(repo.rootDir.toFile(), change.path)
                if (reverted.isFile) {
                    // Push the reverted on-disk content into any editor tab open on this file, so it
                    // stops showing the now-discarded edits. Find those buffers first and read the file
                    // only when one exists: reverting a large binary (never open in the text editor)
                    // must not pull its whole content into heap — an unconditional read OOM-crashed the app.
                    val editors = editStore.editorsFor(reverted)
                    if (editors.isNotEmpty()) {
                        val disk = withContext(Dispatchers.IO) { runCatching { reverted.readText() }.getOrNull() }
                        if (disk != null) editors.forEach { it.adoptDiskText(disk) }
                    }
                } else {
                    closeTabsUnder(reverted)
                }
                reloadStatus()
            } finally {
                revertInFlight = false
            }
        }
    }

    // Discard every uncommitted change at once. Same reconciliation as performRevert, applied to
    // the whole set: buffers for files that survive adopt their restored disk content, and tabs on
    // files that no longer exist (the new ones, now deleted) are closed.
    fun performRevertAll(changes: List<FileChange>) {
        if (repo == null || revertInFlight || changes.isEmpty()) return
        scope.launch {
            revertInFlight = true
            try {
                gitOpError = runGitOp("Revert all failed") {
                    withContext(Dispatchers.IO) { repo.revertFiles(changes) }
                    for (change in changes) {
                        val reverted = File(repo.rootDir.toFile(), change.path)
                        if (reverted.isFile) {
                            // Read the file only when a buffer is actually open on it — see
                            // performRevert: an unconditional read of a large binary OOM-crashed the app.
                            val editors = editStore.editorsFor(reverted)
                            if (editors.isNotEmpty()) {
                                val disk = withContext(Dispatchers.IO) { runCatching { reverted.readText() }.getOrNull() }
                                if (disk != null) editors.forEach { it.adoptDiskText(disk) }
                            }
                        } else {
                            closeTabsUnder(reverted)
                        }
                    }
                    reloadStatus()
                }
            } finally {
                revertInFlight = false
            }
        }
    }

    // Raised by a git history tab: load what the commit touched so the confirmation can show it,
    // then put the dialog up. The file list is only for the user to read — the reversal itself is
    // defined by the commit — so a list we fail to load still gets a dialog rather than nothing.
    fun askRevertCommit(commit: CommitInfo) {
        if (repo == null || revertInFlight) return
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                runCatching { repo.commitFiles(commit.sha) }.getOrDefault(emptyList())
            }
            pendingRevertCommit = RevertCommitRequest(
                sha = commit.sha,
                shortSha = commit.shortSha,
                message = commit.shortMessage,
                files = files,
            )
        }
    }

    // Back a commit's changes out of the working tree, leaving them uncommitted — the log's
    // "Revert commit". Same reconciliation as performRevert, split the way the reversal itself
    // splits: paths it rewrote are pushed into any open editor buffer, so a later autosave can't
    // undo the reversal; paths it removed have their tabs closed. History tabs are kept — the log
    // the user right-clicked in must not vanish under them.
    fun performRevertCommit(request: RevertCommitRequest) {
        if (repo == null || revertInFlight) return
        scope.launch {
            revertInFlight = true
            try {
                // Held across the block so the "nothing happened" case can be reported *after*
                // runGitOp has returned — assigning gitOpError inside it would be overwritten by
                // the null it returns on success.
                var revertedNothing = false
                gitOpError = runGitOp("Revert of ${request.shortSha} failed") {
                    val outcome = withContext(Dispatchers.IO) { repo.revertCommit(request.sha) }
                    revertedNothing = outcome.isEmpty
                    for (path in outcome.updated) {
                        val file = File(repo.rootDir.toFile(), path)
                        // Read the file only when a buffer is actually open on it — see
                        // performRevert: an unconditional read of a large binary OOM-crashed the app.
                        val editors = editStore.editorsFor(file)
                        if (editors.isNotEmpty()) {
                            val disk = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrNull() }
                            if (disk != null) editors.forEach { it.adoptDiskText(disk) }
                        }
                    }
                    for (path in outcome.removed) {
                        closeTabsUnder(File(repo.rootDir.toFile(), path), includeHistoryTabs = false)
                    }
                    reloadStatus()
                }
                // A commit already backed out leaves the tree untouched. Saying so beats a silent
                // no-op the user reads as "the revert didn't work".
                if (gitOpError == null && revertedNothing) {
                    gitOpError = GitOpError(
                        "Nothing to revert",
                        "${request.shortSha} has already been backed out — the working tree holds " +
                            "none of its changes.",
                    )
                }
            } finally {
                revertInFlight = false
            }
        }
    }

    // After a tree mutation (new file/dir/package, copy): rescan the tree from disk immediately
    // and reload git status so the new entry shows up with the right colour. fsRefreshKey is
    // bumped directly (not only via reloadStatus) so the tree still refreshes in a non-git dir.
    fun afterTreeMutation() {
        fsRefreshKey += 1
        refresh()
    }

    // Drag-and-drop move from the project tree. Any tab open on the moved path no longer points
    // at a real file (same situation as a delete), so it's closed the same way — the file reopens
    // with one click from its new spot in the tree, which afterTreeMutation reveals.
    fun performMove(source: File, targetDir: File) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { FileOperations.moveFile(source, targetDir) } }
            result.fold(
                onSuccess = { dest ->
                    if (dest.absolutePath != source.absolutePath) {
                        closeTabsUnder(source)
                        afterTreeMutation()
                        treeReveal = dest
                    }
                },
                onFailure = { e -> gitOpError = GitOpError("Could not move file", e.userMessage()) },
            )
        }
    }

    // Ctrl+V in the project tree: copy everything on the clipboard into [targetDir]. Each source
    // is attempted independently so one failure (a vanished file, a directory dropped into itself)
    // doesn't lose the rest; the first error is what the user is told about.
    fun performPaste(targetDir: File) {
        val sources = FileClipboard.files()
        if (sources.isEmpty()) return
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                sources.map { source -> runCatching { FileOperations.copyInto(source, targetDir) } }
            }
            val created = results.mapNotNull { it.getOrNull() }
            if (created.isNotEmpty()) {
                afterTreeMutation()
                treeReveal = created.last()
            }
            results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { e ->
                gitOpError = GitOpError("Could not paste", e.userMessage())
            }
        }
    }

    // F2 / "Rename…" from the project tree. Runs on the calling (UI) thread like the other dialog
    // actions so a bad name can be reported back into the still-open dialog; the work is a single
    // rename() syscall either way.
    //
    // Tabs are carried across rather than closed: a rename is usually done *to* the file you have
    // open, so its editor follows to the new path, keeping its place in the strip (a renamed
    // directory takes every editor beneath it along). Buffers are flushed first — the buffer itself
    // can't come across, and the autosave debounce means "unsaved" is the normal state for a second
    // or two after typing. Tabs that aren't plain editors (diffs, history) are pinned to a path the
    // rename invalidates, so those still close.
    fun performRename(target: File, rawName: String): File {
        for (edit in editStore.snapshot()) {
            if (FileOperations.isSelfOrDescendant(edit.file, target)) runCatching { edit.save() }
        }
        val dest = FileOperations.rename(target, rawName)
        for (tab in tabsState.tabs.toList()) {
            if (tab !is Tab.FileView) continue
            val moved = FileOperations.remapPath(tab.file, target, dest) ?: continue
            // The buffer is keyed on the old tab id and points at a path that no longer exists;
            // left behind it would be reconciled — and eventually written — back into place.
            editStore.close(tab.id)
            tabsState.rekey(tab.id, Tab.FileView(moved))
        }
        // Whatever is left on the old path is pinned to it (diffs, history), so it closes.
        closeTabsUnder(target)
        afterTreeMutation()
        treeReveal = dest
        return dest
    }

    // ---- Java: find usages and rename ---------------------------------------------------------

    /** The active tab's Java editor, or null when the front tab isn't one. */
    fun activeJavaEditor(): Triple<File, String, FileEdit>? {
        val tab = tabsState.selectedTab as? Tab.FileView ?: return null
        if (!tab.file.extension.equals("java", ignoreCase = true)) return null
        val edit = editStore.peek(tab.id) ?: return null
        val rel = runCatching {
            rootPath.toAbsolutePath().normalize()
                .relativize(tab.file.toPath().toAbsolutePath().normalize())
                .toString().replace(File.separatorChar, '/')
        }.getOrNull()?.takeIf { it.isNotEmpty() && !it.startsWith("..") } ?: return null
        return Triple(tab.file, rel, edit)
    }

    /**
     * Writes out every buffer with unsaved work before a project-wide read.
     *
     * Both features below find their occurrences by reading files from disk, and a rename then
     * rewrites the characters at the offsets it found. If a buffer held unsaved edits, disk and
     * screen would disagree about where those characters are, and the rewrite would land in the
     * wrong place — so disk is made the single version of the truth first. A buffer that can't be
     * saved (something else wrote the file) stops the whole operation rather than proceeding on
     * text nop knows is stale.
     */
    suspend fun flushBuffersForAnalysis(): String? {
        for (edit in editStore.snapshot()) {
            if (!edit.hasUserEdit) continue
            when (val result = withContext(Dispatchers.IO) { edit.save() }) {
                is SaveResult.ExternalChange ->
                    return "${edit.file.name} changed on disk while you were editing it — resolve that first."
                is SaveResult.Failed -> return "Could not save ${edit.file.name}: ${result.message}"
                else -> Unit
            }
        }
        return null
    }

    /**
     * Resolves what the caret is on and finds every usage of it.
     *
     * Returns null having already set [usagesView] to something explaining why, so both callers —
     * the panel and the rename dialog — get the same diagnosis for the same non-answer.
     */
    suspend fun resolveUsagesAtCaret(): Pair<UsageResult, Triple<File, String, FileEdit>>? {
        val active = activeJavaEditor()
        if (active == null) {
            usagesView = UsagesView(message = "Open a Java file and put the caret on a name first")
            return null
        }
        if (!JavaParse.available) {
            usagesView = UsagesView(message = "This build has no Java compiler in its runtime, so Java analysis is off")
            return null
        }
        val (file, rel, edit) = active
        val blocked = flushBuffersForAnalysis()
        if (blocked != null) {
            usagesView = UsagesView(message = blocked)
            return null
        }
        val text = edit.state.text.toString()
        val caret = edit.state.selection.start
        val target = withContext(Dispatchers.Default) {
            JavaParse.parse(text, file.name)?.let { JavaUsages.targetAt(it, caret) }
        }
        if (target == null) {
            usagesView = UsagesView(
                message = "No Java declaration under the caret — nop can only search for something it " +
                    "can see declared or imported here",
            )
            return null
        }
        usagesView = UsagesView(title = target.description, searching = true)
        val result = JavaUsages.find(rootPath.toFile(), fileIndex.files, target, rel)
        usagesView = UsagesView(title = target.description, result = result)
        return result to active
    }

    /** Rewrites every planned occurrence, then brings tabs and the tree back into line. */
    suspend fun applyRename(plan: RenamePlan): String? {
        val root = rootPath.toFile()
        val rewritten = withContext(Dispatchers.IO) {
            val done = mutableListOf<Pair<File, String>>()
            for ((rel, ranges) in plan.edits) {
                val file = File(root, rel)
                val before = runCatching { file.readText() }.getOrNull()
                    ?: return@withContext Result.failure(IOException("Could not read $rel"))
                val after = JavaRename.applyEdits(before, ranges, plan.newName)
                if (after == before) continue
                // The pre-edit version goes into local history first, so a rename that turns out to
                // be wrong is recoverable from inside nop — the same safety net an edit gets.
                localHistory.record(file, before)
                val failure = runCatching { file.writeText(after) }.exceptionOrNull()
                if (failure != null) {
                    return@withContext Result.failure(IOException("Could not write $rel: ${failure.message}"))
                }
                localHistory.record(file, after)
                done += file to after
            }
            Result.success(done)
        }
        val files = rewritten.getOrElse { return it.message ?: "Rename failed" }
        // Back on the UI thread: every open buffer on a rewritten file takes the new text, exactly
        // as it does after a revert. adoptDiskText carries the caret over and leaves the buffer
        // clean, because what it now holds is what is on disk.
        for ((file, text) in files) {
            editStore.editorsFor(file).forEach { it.adoptDiskText(text) }
        }
        // A public type has to live in a file of its own name, so the file follows the class.
        plan.fileRename?.let { rename ->
            val target = File(root, rename.path)
            if (target.isFile) {
                val failure = runCatching { performRename(target, rename.newFileName) }.exceptionOrNull()
                if (failure != null) return failure.message ?: "Renamed the code but not the file"
            }
        }
        refresh()
        return null
    }

    // Alt+F7. Baselined like the other window-level triggers so switching back to a project after
    // the session's first press doesn't re-run the search.
    val findUsagesBaseline = remember(projectPath) { findUsagesTrigger }
    LaunchedEffect(findUsagesTrigger) {
        if (findUsagesTrigger <= findUsagesBaseline) return@LaunchedEffect
        showTool(ToolTab.Usages)
        resolveUsagesAtCaret()
    }

    // Shift+F6. Shares every step with Alt+F7 up to the point the answer arrives, then puts it in
    // front of the user as something to change rather than something to read.
    val renameBaseline = remember(projectPath) { renameSymbolTrigger }
    LaunchedEffect(renameSymbolTrigger) {
        if (renameSymbolTrigger <= renameBaseline) return@LaunchedEffect
        renameFailure = null
        val resolved = resolveUsagesAtCaret()
        if (resolved == null) {
            // The panel already carries the explanation; show it rather than a silent no-op.
            showTool(ToolTab.Usages)
            return@LaunchedEffect
        }
        val (result, active) = resolved
        pendingRename = RenameRequest(
            usages = result,
            declaringPath = active.second,
            declaringText = active.third.state.text.toString(),
        )
    }

    // Where a tree action targeting [target] should create its entry, shown to the user in the
    // dialog as a path relative to the project root ("" → the root itself).
    fun relativeLabel(dir: File): String = runCatching {
        rootPath.toAbsolutePath().normalize()
            .relativize(dir.toPath().toAbsolutePath().normalize())
            .toString().replace(File.separatorChar, '/')
    }.getOrNull()?.takeIf { it.isNotEmpty() && !it.startsWith("..") } ?: "."

    // The markdown tab the Preview panel renders, or null when the editor is showing something
    // else. Only a [Tab.FileView] qualifies: a diff of a .md file is already two panes of text and
    // has no single buffer to render.
    val previewTab: Tab.FileView? = (tabsState.selectedTab as? Tab.FileView)
        ?.takeIf { it.file.extension.equals("md", ignoreCase = true) }

    // Nothing here flips the tool panel to Preview. Opening a .md file used to, which meant the
    // panel you had chosen — a terminal, the commit list — was taken off you by the act of reading a
    // README. The tab is there to be picked when the rendered version is what you want.

    // Sync the active tab's underlying file back into the tree so the sidebar always shows
    // which file the user is currently looking at.
    val revealFile: File? = when (val t = tabsState.selectedTab) {
        is Tab.FileView -> t.file
        is Tab.Diff -> File(t.repoRoot, t.change.path)
        is Tab.CommitDiff -> File(t.repoRoot, t.file.path)
        is Tab.RevisionDiff -> t.file
        is Tab.LocalHistory -> t.file
        is Tab.LocalDiff -> t.file
        null -> null
    }

    val openFiles: List<File> = tabsState.tabs.mapNotNull { tab ->
        when (tab) {
            is Tab.FileView -> tab.file
            is Tab.Diff -> File(tab.repoRoot, tab.change.path)
            is Tab.CommitDiff -> File(tab.repoRoot, tab.file.path)
            is Tab.RevisionDiff -> tab.file
            is Tab.LocalHistory -> tab.file
            is Tab.LocalDiff -> tab.file
        }
    }

    val dirtyFiles: Set<File> = tabsState.tabs.filterIsInstance<Tab.FileView>().filter {
        editStore.peek(it.id)?.hasUserEdit == true
    }.map { it.file }.toSet()

    fun closeTab(tab: Tab) {
        editStore.close(tab.id)
        tabsState.close(tab.id)
    }

    fun closeFile(file: File) {
        val tab = tabsState.tabs.firstOrNull { it is Tab.FileView && it.file.absolutePath == file.absolutePath }
            ?: tabsState.tabs.firstOrNull { jumpToSourceTarget(it)?.absolutePath == file.absolutePath }
            ?: return
        closeTab(tab)
    }

    fun closeOtherFiles(file: File) {
        val tab = tabsState.tabs.firstOrNull { it is Tab.FileView && it.file.absolutePath == file.absolutePath }
            ?: return
        tabsState.closeOthers(tab.id).forEach { editStore.close(it.id) }
    }

    fun closeAllFiles() {
        val removed = tabsState.tabs.toList()
        for (tab in removed) {
            closeTab(tab)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalSplit(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                ratio = hRatio,
                onRatioChange = { hRatio = it },
                first = {
                    ProjectTreePanel(
                        projectPath = rootPath,
                        status = status,
                        refreshKey = fsRefreshKey,
                        openFiles = openFiles,
                        activeFile = revealFile,
                        dirtyFiles = dirtyFiles,
                        revealFile = revealFile,
                        revealRequest = treeReveal,
                        onFileClick = { tabsState.open(Tab.FileView(it)) },
                        onCloseFile = ::closeFile,
                        onCloseOtherFiles = ::closeOtherFiles,
                        onCloseAllFiles = ::closeAllFiles,
                        onDeleteRequest = { pendingDelete = it },
                        onNewFile = { pendingEntry = TreeEntryDialog.NewFile(FileOperations.parentDirFor(it)) },
                        onNewDirectory = { pendingEntry = TreeEntryDialog.NewDirectory(FileOperations.parentDirFor(it)) },
                        onNewPackage = { pendingEntry = TreeEntryDialog.NewPackage(FileOperations.parentDirFor(it)) },
                        onCopyFile = { pendingEntry = TreeEntryDialog.CopyFile(it) },
                        onRenameRequest = { pendingEntry = TreeEntryDialog.Rename(it) },
                        onClipboardCopy = FileClipboard::copy,
                        onPasteRequest = ::performPaste,
                        canPaste = FileClipboard::hasFiles,
                        onMoveRequest = ::performMove,
                        onHistoryRequest = { file ->
                            if (repo != null) {
                                historySessions.open(file, repo.rootDir.toFile())
                                showTool(ToolTab.History)
                            }
                        },
                        onCompareWithRevision = { pendingCompare = it },
                        gitEnabled = repo != null,
                        blameEnabled = blameEnabled,
                        onToggleBlame = { blameEnabled = !blameEnabled },
                        wrapLines = wrapLines,
                        onToggleWrap = {
                            wrapLines = !wrapLines
                            Settings.saveWrapLines(wrapLines)
                        },
                        projectLabel = {
                            ProjectMenuLabel(
                                recentProjects = recentProjects,
                                openProjects = openProjects,
                                onOpenProject = onOpenProject,
                                onOpenOther = onOpenOtherProject,
                            )
                        },
                        headerExtras = {
                            LauncherButton(
                                launchers = launchers,
                                readOnlyNames = readOnlyNames,
                                onRun = { launcher ->
                                    runSessions.open(TerminalSession.forLauncher(launcher, rootPath.toFile()))
                                    showSession(ToolTab.Run)
                                },
                                onNewTerminal = {
                                    // Same thing the strip's "+" does: shells live in the terminal
                                    // tabs, so the two ways of asking for one land in one place.
                                    terminals.openShell(rootPath.toFile())
                                    showSession(ToolTab.Terminal)
                                },
                                onAgentAccounts = { showAccounts = true },
                                onAdd = { persistLaunchers(stored + it) },
                                onDelete = { persistLaunchers(stored - it) },
                            )
                        },
                    )
                },
                second = {
                    // Viewer in the middle, tool panel (Commit/Search/Preview/Run/…) on the right edge,
                    // running the full height of the window to mirror the project tree on the left.
                    HorizontalSplit(
                        modifier = Modifier.fillMaxSize(),
                        ratio = toolsRatio,
                        onRatioChange = { toolsRatio = it },
                        minFirstDp = 240.dp,
                        minSecondDp = 240.dp,
                        first = {
                            TabbedViewerPanel(
                                tabsState = tabsState,
                                repo = repo,
                                editStore = editStore,
                                localHistory = localHistory,
                                onFileSaved = ::refresh,
                                onResolveAt = { currentFile, text, offset ->
                                    JumpResolver.resolve(
                                        symbolIndex,
                                        rootPath.toFile(),
                                        currentFile,
                                        text,
                                        offset,
                                        fileIndex,
                                    )
                                },
                                onJump = { file, line ->
                                    tabsState.openAt(Tab.FileView(file), line)
                                },
                                onDiffTopLine = { diffTopLine = it },
                                findInFileTrigger = findInFileTrigger,
                                replaceInFileTrigger = replaceInFileTrigger,
                                saveTrigger = saveTrigger,
                                blameEnabled = blameEnabled,
                                wrapLines = wrapLines,
                                onToggleWrap = {
                                    wrapLines = !wrapLines
                                    Settings.saveWrapLines(wrapLines)
                                },
                                diffSplitRatio = diffRatio,
                                onDiffSplitRatioChange = { diffRatio = it },
                                onCompareWithRevision = { pendingCompare = it },
                                onShowHistory = { file ->
                                    if (repo != null) {
                                        historySessions.open(file, repo.rootDir.toFile())
                                        showTool(ToolTab.History)
                                    }
                                },
                            )
                        },
                        second = {
                            // The strip is drawn over the whole window rather than inside this
                            // region, so it cannot measure the region it spans — see
                            // UsageIndicator's spanWidth. This is where that width exists.
                            val density = LocalDensity.current
                            Box(
                                modifier = Modifier.fillMaxSize().onSizeChanged {
                                    toolRegionWidth = with(density) { it.width.toDp() }
                                },
                            ) {
                            // Every terminal in the window is inside this panel, and each has to
                            // stop short of the usage strip floating over the window's bottom-right
                            // corner — see LocalTerminalBottomInset.
                            CompositionLocalProvider(
                                LocalTerminalBottomInset provides usageStripHeight,
                            ) {
                            ToolTabs(
                                selected = toolTab,
                                onSelect = { toggleTool(it) },
                                sessionTab = sessionTab,
                                collapsed = toolsCollapsed,
                                onToggleCollapsed = { toolsCollapsed = !toolsCollapsed },
                                paneRatio = sessionRatio,
                                onPaneRatioChange = { sessionRatio = it },
                                terminals = terminals,
                                onNewTerminal = {
                                    terminals.openShell(rootPath.toFile())
                                    showSession(ToolTab.Terminal)
                                },
                                onSelectTerminal = { id ->
                                    terminals.select(id)
                                    showSession(ToolTab.Terminal)
                                },
                                onCloseTerminal = { terminals.close(it) },
                                onReorderTerminal = { from, to -> terminals.move(from, to) },
                                agents = agentSessions,
                                // Both the "+" and the picker's own tab. The "+" opens an empty
                                // agent tab rather than a session on the account used last: which
                                // quota the next hour comes out of is a choice, and the same tab is
                                // where an earlier session is resumed from. Choosing in it is what
                                // turns it into the session's tab — see AgentSessions.open.
                                onShowPicker = {
                                    agentSessions.showPicker()
                                    showSession(ToolTab.Agent)
                                },
                                onSelectAgent = { id ->
                                    agentSessions.select(id)
                                    showSession(ToolTab.Agent)
                                },
                                onCloseAgent = { id -> agentSessions.close(id) },
                                onRenameAgent = { id, name -> agentSessions.rename(id, name) },
                                onReorderAgent = { from, to -> agentSessions.move(from, to) },
                                runs = runSessions,
                                onSelectRun = { id ->
                                    runSessions.select(id)
                                    showSession(ToolTab.Run)
                                },
                                onCloseRun = { id ->
                                    runSessions.close(id)
                                    // Nothing left to show and no "+" here to make another — the ▶
                                    // menu is. Fall back to the agents, which is what an empty
                                    // session pane holds: with no session selected that is the
                                    // picker, which is the one thing in the pane that can start
                                    // something.
                                    if (runSessions.sessions.isEmpty() && sessionTab == ToolTab.Run) {
                                        showSession(ToolTab.Agent)
                                    }
                                },
                                histories = historySessions,
                                onSelectHistory = { id ->
                                    // Clicking the log already on screen folds the panel away, the
                                    // way clicking the selected Commit or Diff tab does — a log is
                                    // a tab in the same strip and the gesture cannot mean two
                                    // things depending on which of them the user pressed.
                                    if (toolTab == ToolTab.History && historySessions.selectedId == id) {
                                        toggleTool(ToolTab.History)
                                    } else {
                                        historySessions.select(id)
                                        showTool(ToolTab.History)
                                    }
                                },
                                onCloseHistory = { id ->
                                    historySessions.close(id)
                                    // Nothing left to show, and like the runs there is no "+" here
                                    // to make another — the file's right-click menu is. Fall back
                                    // to the tab the panel opens on rather than sit on a tab that
                                    // is no longer in the strip.
                                    if (historySessions.sessions.isEmpty() && toolTab == ToolTab.History) {
                                        showTool(ToolTab.Commit)
                                    }
                                },
                                terminal = { TerminalTabPanel(terminals, terminalCards) },
                                agent = {
                                    AgentPanel(
                                        state = agentSessions,
                                        accounts = agentAccounts,
                                        readings = agentUsage,
                                        // Minus the ones the strip is still running — since agent
                                        // tabs come back at the next start, a restored session is
                                        // both a tab and a past session, and clicking it here would
                                        // resume the same conversation a second time beside the
                                        // first.
                                        //
                                        // Only while it is running, though. A tab whose run has
                                        // ended holds nothing but the post-exit choices, and it is
                                        // the one case where hiding the row hid the work: a session
                                        // that handed over and then stopped is listed by nop
                                        // under the conversation it ended in, so hiding that row
                                        // left the picker showing only the conversation the
                                        // handover walked away from — on the account that had
                                        // just run out.
                                        sessions = pastAgentSessions.filterNot { past ->
                                            agentSessions.sessions.any {
                                                it.sessionId == past.sessionId && !it.ended
                                            }
                                        },
                                        // The same path every launch below is given, so the picker
                                        // cannot name one directory and start the CLI in another.
                                        projectDir = rootPath,
                                        cards = terminalCards,
                                        modelsFor = { account ->
                                            agentModels[account.name] ?: account.provider.fallbackModels
                                        },
                                        onUpdateAccount = { changed ->
                                            agentConfig?.let { current ->
                                                val next = current.copy(
                                                    accounts = current.accounts.map { if (it.name == changed.name) changed else it },
                                                )
                                                agentConfig = next
                                                scope.launch { withContext(Dispatchers.IO) { Accounts.save(next) } }
                                            }
                                        },
                                        onLaunch = { account ->
                                            // HEAD now is what the Diff tab's "session" base means
                                            // for this tab from here on — see AgentSession.
                                            agentSessions.open(
                                                rootPath.toFile(),
                                                account,
                                                baselineSha = repo?.headSha(),
                                            )
                                            showSession(ToolTab.Agent)
                                        },
                                        onReopen = { past ->
                                            // Native resume, not a replay of a summary: the vendor
                                            // still has the real session, and landing back in it is
                                            // strictly better than landing in a description of it.
                                            //
                                            // A row that named a store rather than an account is
                                            // resumed against that store — see PastSession.home
                                            // and accountIn, which is also what decides whether
                                            // the row was clickable in the first place.
                                            val account = past.accountIn(agentAccounts)
                                            if (account != null) {
                                                agentSessions.open(
                                                    dir = rootPath.toFile(),
                                                    account = account,
                                                    resumeId = past.lastNativeSessionId,
                                                    // Where the resumed work starts from nop's side.
                                                    // The conversation is older than this tab; the
                                                    // changes the user is about to watch are not.
                                                    baselineSha = repo?.headSha(),
                                                )
                                                showSession(ToolTab.Agent)
                                            } else {
                                                // Unreachable through the picker, which draws such a
                                                // row as unclickable. Logged rather than dropped
                                                // because a press that does nothing at all is the
                                                // one failure the user cannot report anything about.
                                                Log.warn(
                                                    "nothing to reopen for ${past.title}: " +
                                                        "account=${past.lastAccount} home=${past.home}",
                                                )
                                            }
                                        },
                                        onSettings = { showAccounts = true },
                                    )
                                },
                                diff = {
                                    DiffPanel(
                                        repo = repo,
                                        status = status,
                                        // The same counter the editor's diff tabs re-read on, so
                                        // the panel and a diff open beside it never disagree about
                                        // what is on disk.
                                        refreshKey = fsRefreshKey,
                                        base = diffBase,
                                        onBaseChange = { diffBase = it },
                                        // Whichever agent is on the left, so "session" means the
                                        // one the user is watching rather than the first one opened.
                                        sessionBaselineSha = agentSessions.selected?.baselineSha,
                                        splitRatio = diffRatio,
                                        onSplitRatioChange = { diffRatio = it },
                                    )
                                },
                                commit = {
                                    CommitPanel(
                                        status = status,
                                        selectedPaths = selectedPaths,
                                        onToggle = { path ->
                                            selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
                                        },
                                        onChangeClick = { change ->
                                            if (repo != null) {
                                                tabsState.open(Tab.Diff(change, repo.rootDir.toFile()))
                                            }
                                        },
                                        onRevert = { change -> pendingRevert = change },
                                        onRevertAll = { pendingRevertAll = status.changes },
                                        onCommit = { message, included ->
                                            if (repo != null && !commitInFlight) {
                                                scope.launch {
                                                    commitInFlight = true
                                                    // Timed from the click, not from the staging call:
                                                    // the status check below is part of the wait the
                                                    // user is sitting through.
                                                    val startedAt = System.currentTimeMillis()
                                                    commitProgressFlow.value =
                                                        CommitProgress(CommitProgress.Phase.CHECKING, startedAtMillis = startedAt)
                                                    try {
                                                        // Held across the block so the "nothing was
                                                        // committed" case can be reported after
                                                        // runGitOp returns — assigning gitOpError
                                                        // inside it would be overwritten by the null
                                                        // it returns on success (cf.
                                                        // performRevertCommit).
                                                        var movedPaths = emptySet<String>()
                                                        gitOpError = runGitOp("Commit failed") {
                                                            // Refresh before committing: if new unreviewed
                                                            // changes appeared since the last load, show them
                                                            // and let the user decide rather than silently
                                                            // committing a partial snapshot.
                                                            val fresh = withContext(Dispatchers.IO) { repo.loadStatus() }
                                                            val knownPaths = status.changes.map { it.path }.toSet()
                                                            val newPaths = fresh.changes.map { it.path }.toSet() - knownPaths
                                                            if (newPaths.isNotEmpty()) {
                                                                status = fresh
                                                                selectedPaths = fresh.changes.map { it.path }.toSet()
                                                                fsRefreshKey += 1
                                                                movedPaths = newPaths
                                                            } else {
                                                                withContext(Dispatchers.IO) {
                                                                    repo.stageAndCommit(
                                                                        message,
                                                                        included,
                                                                        // Unticked paths are held out
                                                                        // of the commit, not merely
                                                                        // left unstaged.
                                                                        partial = included.size != status.changes.size,
                                                                        startedAtMillis = startedAt,
                                                                        onProgress = { commitProgressFlow.value = it },
                                                                    )
                                                                }
                                                                rememberMessage(message)
                                                                messageClearTrigger += 1
                                                                // The button stays disabled through the
                                                                // reload, so it keeps reporting: on a big
                                                                // repo this walk is seconds of its own.
                                                                commitProgressFlow.value = CommitProgress(
                                                                    CommitProgress.Phase.REFRESHING,
                                                                    startedAtMillis = startedAt,
                                                                )
                                                                reloadStatus()
                                                            }
                                                        }
                                                        // A click that stood the commit down looks
                                                        // no different from one that started a ten
                                                        // minute commit, so say what happened. The
                                                        // message field keeps its text, ready for
                                                        // the second click.
                                                        if (gitOpError == null && movedPaths.isNotEmpty()) {
                                                            gitOpError = changeListMovedNotice(movedPaths)
                                                        }
                                                    } finally {
                                                        commitInFlight = false
                                                        commitProgressFlow.value = null
                                                    }
                                                }
                                            }
                                        },
                                        onStash = { message, included ->
                                            if (repo != null && !stashInFlight) {
                                                scope.launch {
                                                    stashInFlight = true
                                                    try {
                                                        gitOpError = runGitOp("Stash failed") {
                                                            withContext(Dispatchers.IO) {
                                                                repo.stashCreate(message.ifBlank { null }, included)
                                                            }
                                                            rememberMessage(message)
                                                            messageClearTrigger += 1
                                                            reloadStatus()
                                                        }
                                                    } finally {
                                                        stashInFlight = false
                                                    }
                                                }
                                            }
                                        },
                                        commitInFlight = commitInFlight,
                                        commitProgress = commitProgress,
                                        messageClearTrigger = messageClearTrigger,
                                        messageState = commitMessageState,
                                        messageHeight = commitMessageHeight,
                                        onMessageHeightChange = { commitMessageHeight = it },
                                        stashInFlight = stashInFlight,
                                        refreshing = refreshing,
                                        onRefresh = ::refresh,
                                        // Reuse list = remembered commit/stash messages plus the
                                        // descriptions of stashes currently on the shelf (so externally
                                        // created or pre-existing stashes show up too). "(no message)"
                                        // is GitRepo.stashList's placeholder for an empty stash desc.
                                        messageHistory = (recentMessages + stashes.map { it.message })
                                            .filter { it.isNotBlank() && it != "(no message)" }
                                            .distinct()
                                            .take(COMMIT_MESSAGE_HISTORY_CAP),
                                        canSoftReset = canSoftReset,
                                        resetInFlight = resetInFlight,
                                        revertInFlight = revertInFlight,
                                        onSoftReset = {
                                            if (repo != null && !resetInFlight && !commitInFlight) {
                                                scope.launch {
                                                    resetInFlight = true
                                                    try {
                                                        val ok = withContext(Dispatchers.IO) {
                                                            runCatching { repo.softResetHead() }.getOrDefault(false)
                                                        }
                                                        if (ok) reloadStatus()
                                                    } finally {
                                                        resetInFlight = false
                                                    }
                                                }
                                            }
                                        },
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
                                            // Seed the opened tab's in-file find with the active query so its
                                            // matches highlight in the editor the same way a manual find does.
                                            if (absolute.isFile) tabsState.openAt(
                                                Tab.FileView(absolute),
                                                line,
                                                searchQuery = searchQueryState.text.toString(),
                                            )
                                        },
                                    )
                                },
                                usages = {
                                    UsagesPanel(
                                        view = usagesView,
                                        onPick = { relPath, line ->
                                            val absolute = File(rootPath.toFile(), relPath)
                                            if (absolute.isFile) tabsState.openAt(Tab.FileView(absolute), line)
                                        },
                                    )
                                },
                                preview = {
                                    if (previewTab == null) {
                                        Box(
                                            modifier = Modifier.fillMaxSize().padding(16.dp),
                                            contentAlignment = androidx.compose.ui.Alignment.Center,
                                        ) {
                                            Text("Open a markdown file to see it rendered here")
                                        }
                                    } else {
                                        // Reads the live buffer, not the file: the preview has always
                                        // tracked what the user is typing rather than what was last
                                        // saved, and the editor beside it is writing into this very
                                        // TextFieldState.
                                        val edit = editStore.edit(previewTab)
                                        // Clipped: squeezed past the width its longest word needs,
                                        // the rendered text lays out wider than the panel it was
                                        // given and would otherwise spill over the viewer beside it.
                                        Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                                            MarkdownPreview(
                                                text = edit.state.text.toString(),
                                                modifier = Modifier.fillMaxSize(),
                                                scroll = edit.previewScroll,
                                            )
                                        }
                                    }
                                },
                                run = { RunPanel(runSessions, terminalCards) },
                                history = {
                                    HistoryPanel(
                                        state = historySessions,
                                        repo = repo,
                                        tabsState = tabsState,
                                        onRevertCommit = ::askRevertCommit,
                                    )
                                },
                                stash = {
                                    StashPanel(
                                        stashes = stashes,
                                        busy = stashInFlight,
                                        onPop = { entry ->
                                            if (repo != null && !stashInFlight) {
                                                scope.launch {
                                                    stashInFlight = true
                                                    try {
                                                        gitOpError = runGitOp("Unstash failed") {
                                                            withContext(Dispatchers.IO) { repo.stashPop(entry) }
                                                            reloadStatus()
                                                        }
                                                    } finally {
                                                        stashInFlight = false
                                                    }
                                                }
                                            }
                                        },
                                        onDrop = { entry ->
                                            if (repo != null && !stashInFlight) {
                                                scope.launch {
                                                    stashInFlight = true
                                                    try {
                                                        gitOpError = runGitOp("Drop stash failed") {
                                                            withContext(Dispatchers.IO) { repo.stashDrop(entry) }
                                                            reloadStatus()
                                                        }
                                                    } finally {
                                                        stashInFlight = false
                                                    }
                                                }
                                            }
                                        },
                                    )
                                },
                            )
                            }
                            }
                        },
                    )
                },
            )
        }

        // The corner the theme toggle used to float in. Usage earns it: which account has quota
        // left is the thing you look at to decide what to do next, and it is global state, so
        // burying it behind a tab would cost a click you only make once you already suspect the
        // answer. The toggle moved to the project bar, which is drawn once per window.
        UsageIndicator(
            accounts = agentAccounts,
            readings = agentUsage,
            onClick = { showAccounts = true },
            onHeight = { usageStripHeight = it },
            spanWidth = toolRegionWidth,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd),
        )

        if (showAccounts) {
            val config = agentConfig ?: AgentConfig()
            AccountsDialog(
                config = config,
                readings = agentUsage,
                modelsFor = { account ->
                    agentModels[account.name] ?: account.provider.fallbackModels
                },
                onSave = { next ->
                    agentConfig = next
                    scope.launch { withContext(Dispatchers.IO) { Accounts.save(next) } }
                },
                onLogIn = { account ->
                    // In a terminal tab, not in the dialog: the vendor flows need a real TTY, and a
                    // terminal is a heavyweight AWT component that Compose composites *above* every
                    // popup — one drawn inside this dialog would simply not be on screen. The output
                    // staying in the tab afterwards is also the only thing that explains a login
                    // that didn't take. On success, dismiss the tab, refresh usage and return to settings.
                    showAccounts = false
                    val session = Login.session(account)
                    val run = terminals.open(session)
                    session.onExit = { code ->
                        scope.launch {
                            val hasCreds = Login.hasCredentials(account)
                            if (code == 0 && hasCreds) {
                                Usage.clearAuthCache()
                                val reading = withContext(Dispatchers.IO) { runCatching { Usage.read(account) }.getOrNull() }
                                if (reading != null) agentUsage[account.name] = reading
                                val models = withContext(Dispatchers.IO) { runCatching { Usage.discoverModels(account) }.getOrDefault(emptyList()) }
                                if (models.isNotEmpty()) agentModels[account.name] = models
                                delay(800)
                                terminals.close(run.id)
                                showAccounts = true
                            } else {
                                val reading = withContext(Dispatchers.IO) { runCatching { Usage.read(account) }.getOrNull() }
                                if (reading != null) agentUsage[account.name] = reading
                            }
                        }
                    }
                    showSession(ToolTab.Terminal)
                },
                onLogOut = { account ->
                    Login.logOut(account)
                    agentUsage[account.name] = UsageReading.unavailable("not signed in")
                },
                onClose = { showAccounts = false },
            )
        }

        pendingDelete?.let { targets ->
            ConfirmDeleteDialog(
                targets = targets,
                onConfirm = {
                    performDelete(targets)
                    pendingDelete = null
                },
                onCancel = { pendingDelete = null },
            )
        }

        pendingRevert?.let { change ->
            ConfirmRevertDialog(
                change = change,
                onConfirm = {
                    performRevert(change)
                    pendingRevert = null
                },
                onCancel = { pendingRevert = null },
            )
        }

        pendingRevertAll?.let { changes ->
            ConfirmRevertAllDialog(
                changes = changes,
                onConfirm = {
                    performRevertAll(changes)
                    pendingRevertAll = null
                },
                onCancel = { pendingRevertAll = null },
            )
        }

        pendingRevertCommit?.let { request ->
            ConfirmRevertCommitDialog(
                request = request,
                onConfirm = {
                    performRevertCommit(request)
                    pendingRevertCommit = null
                },
                onCancel = { pendingRevertCommit = null },
            )
        }

        gitOpError?.let { err ->
            GitErrorDialog(title = err.title, detail = err.detail, onDismiss = { gitOpError = null })
        }

        when (val entry = pendingEntry) {
            is TreeEntryDialog.NewFile -> NewEntryDialog(
                title = "New File",
                description = "Create a file in ${relativeLabel(entry.parentDir)} — use / to nest in subfolders",
                onSubmit = { name ->
                    runCatching { FileOperations.createFile(entry.parentDir, name) }.fold(
                        onSuccess = { created ->
                            pendingEntry = null
                            afterTreeMutation()
                            tabsState.open(Tab.FileView(created))
                            null
                        },
                        onFailure = { it.message ?: "Could not create file" },
                    )
                },
                onCancel = { pendingEntry = null },
            )
            is TreeEntryDialog.NewDirectory -> NewEntryDialog(
                title = "New Directory",
                description = "Create a directory in ${relativeLabel(entry.parentDir)} — use / to nest",
                onSubmit = { name ->
                    runCatching { FileOperations.createDirectory(entry.parentDir, name) }.fold(
                        onSuccess = { created ->
                            pendingEntry = null
                            afterTreeMutation()
                            treeReveal = created
                            null
                        },
                        onFailure = { it.message ?: "Could not create directory" },
                    )
                },
                onCancel = { pendingEntry = null },
            )
            is TreeEntryDialog.NewPackage -> NewEntryDialog(
                title = "New Package",
                description = "Create a package in ${relativeLabel(entry.parentDir)} — dots become folders (com.example.app)",
                onSubmit = { name ->
                    runCatching { FileOperations.createPackage(entry.parentDir, name) }.fold(
                        onSuccess = { created ->
                            pendingEntry = null
                            afterTreeMutation()
                            treeReveal = created
                            null
                        },
                        onFailure = { it.message ?: "Could not create package" },
                    )
                },
                onCancel = { pendingEntry = null },
            )
            is TreeEntryDialog.CopyFile -> NewEntryDialog(
                title = "Copy File",
                description = "Copy \"${entry.source.name}\" to a new name in ${relativeLabel(entry.source.parentFile)}",
                initialText = entry.source.name,
                confirmLabel = "Copy",
                onSubmit = { name ->
                    runCatching { FileOperations.copyFile(entry.source, name) }.fold(
                        onSuccess = { created ->
                            pendingEntry = null
                            afterTreeMutation()
                            tabsState.open(Tab.FileView(created))
                            null
                        },
                        onFailure = { it.message ?: "Could not copy file" },
                    )
                },
                onCancel = { pendingEntry = null },
            )
            is TreeEntryDialog.Rename -> NewEntryDialog(
                title = if (entry.target.isDirectory) "Rename Directory" else "Rename File",
                description = "Rename \"${entry.target.name}\" — it stays in " +
                    relativeLabel(entry.target.absoluteFile.parentFile ?: rootPath.toFile()) + "/",
                initialText = entry.target.name,
                confirmLabel = "Rename",
                onSubmit = { name ->
                    runCatching { performRename(entry.target, name) }.fold(
                        onSuccess = {
                            pendingEntry = null
                            null
                        },
                        onFailure = { it.message ?: "Could not rename" },
                    )
                },
                onCancel = { pendingEntry = null },
            )
            null -> {}
        }

        // The rename prompt, raised by Shift+F6 once its usage search has come back. Applying runs
        // off the dialog and reports back into it, so a write that fails leaves the user where they
        // were — with the name they typed still in the field — rather than closing on an error.
        pendingRename?.let { request ->
            RenameSymbolDialog(
                usages = request.usages,
                declaringPath = request.declaringPath,
                declaringText = request.declaringText,
                pathExists = { rel -> File(rootPath.toFile(), rel).exists() },
                busy = renameInFlight,
                failure = renameFailure,
                onRename = { plan ->
                    if (!renameInFlight) {
                        scope.launch {
                            renameInFlight = true
                            try {
                                val failure = applyRename(plan)
                                renameFailure = failure
                                if (failure == null) {
                                    pendingRename = null
                                    // The usage list was measured against the old name and the old
                                    // offsets; leaving it up would hand the user rows that no longer
                                    // point at anything.
                                    usagesView = UsagesView(
                                        message = "Renamed ${plan.oldName} to ${plan.newName} — " +
                                            "${plan.occurrenceCount} occurrences in ${plan.fileCount} files",
                                    )
                                }
                            } finally {
                                renameInFlight = false
                            }
                        }
                    }
                },
                onCancel = {
                    if (!renameInFlight) {
                        pendingRename = null
                        renameFailure = null
                    }
                },
            )
        }

        // Drawn only with a repo to read the revisions out of. The actions that raise it are hidden
        // without one, so this is belt-and-braces for a project that has no git at all.
        val comparing = pendingCompare
        if (comparing != null && repo != null) {
            RevisionPickerDialog(
                repo = repo,
                file = comparing,
                onPick = { commit ->
                    pendingCompare = null
                    tabsState.open(
                        Tab.RevisionDiff(comparing, commit.sha, commit.shortSha, repo.rootDir.toFile()),
                    )
                },
                onDismiss = { pendingCompare = null },
            )
        }

        if (fileSearchOpen) {
            FileSearchDialog(
                files = fileIndex.files,
                accessCounts = accessFreq.value.counts,
                onPick = { relPath ->
                    fileSearchOpen = false
                    // The open itself is recorded via TabsState.onFileOpened (see above).
                    val absolute = File(rootPath.toFile(), relPath)
                    if (absolute.isFile) tabsState.open(Tab.FileView(absolute))
                },
                onDismiss = { fileSearchOpen = false },
            )
        }
    }
}
