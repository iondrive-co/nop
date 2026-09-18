package iondrive.nop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import iondrive.nop.agent.AgentSessionStore
import iondrive.nop.git.ProjectGitPoller
import iondrive.nop.git.RepoWatcher
import iondrive.nop.ipc.SingleInstance
import iondrive.nop.spell.Dictionary
import iondrive.nop.ui.App
import iondrive.nop.ui.DoubleShiftDetector
import iondrive.nop.ui.NopTextContextMenu
import iondrive.nop.ui.ProjectBar
import iondrive.nop.ui.WindowPickerPanel
import iondrive.nop.ui.nopMenuStyle
import iondrive.nop.ui.projectTint
import iondrive.nop.ui.projectWindowIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.component.styling.LocalMenuStyle
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

// The size a window opens at when neither it nor an older build's saved geometry says otherwise.
private const val DEFAULT_WINDOW_WIDTH = 1000
private const val DEFAULT_WINDOW_HEIGHT = 700

// How often the workspace looks at whether each open project's dirty dot needs updating. A tick
// where nothing has changed on disk costs a counter comparison per project, so this stays short;
// what a tick may actually *walk* is bounded by ProjectGitPoller instead.
private const val PROJECT_GIT_POLL_MS = 3000L

@OptIn(FlowPreview::class)
fun main(args: Array<String>) {
    // First thing, before any UI: route uncaught throwables to ~/.config/nop/nop.log. Without this
    // a crash on the AWT thread takes the window down and leaves no trace anywhere findable.
    Log.install(args)

    val argPaths = args.map { Paths.get(it).toAbsolutePath().normalize() }
        .filter { Files.isDirectory(it) }

    // If another nop is already running, hand the requested paths off to it (or just ask it
    // to come to the foreground when no paths were supplied) and exit. The primary owns every
    // window, so it can raise the one that already holds the project rather than opening a second.
    if (SingleInstance.tryForward(argPaths, Settings.configRoot)) {
        exitProcess(0)
    }

    val startup = resolveStartup(argPaths.firstOrNull())
    if (startup.workspaces.isEmpty()) exitProcess(0)

    // Read the spellchecker's word lists while the window is still being built. They're wanted the
    // moment a file or diff is on screen, and a diff checks its lines during composition — so
    // without this the ~90k-word load would be the first thing the UI thread does after opening one.
    Thread { Dictionary.warmUp() }.apply { isDaemon = true; name = "dictionary-warmup" }.start()

    application {
        // Every window the user has: a name, its own bar of project tabs, and whether it is showing.
        // Restored from disk (upgrading an older single-window layout on the way in — see
        // [Settings.loadWorkspaces]) and saved back on every change. A window the user closes stays
        // on this list with `open = false`, so its tabs are still there to come back to.
        val workspaces = remember { mutableStateListOf<Workspace>().apply { addAll(startup.workspaces) } }
        // Most-recently-used projects, newest first, backing the "Project" menu over every window's
        // file tree. Seeded from disk unioned with whatever's open now, so even a first run (before
        // this list was tracked) offers the current projects once they're closed.
        val recentProjects = remember {
            mutableStateListOf<Path>().apply {
                val open = Workspaces.allProjects(startup.workspaces)
                addAll((open + Settings.loadRecentProjects()).map { it.toAbsolutePath().normalize() }.distinct())
            }
        }
        var darkMode by remember { mutableStateOf(Settings.loadDarkMode()) }
        // The live windows, captured so a "show this window" click — and the IPC focus signal — can
        // raise one that is already on screen rather than doing nothing.
        val windowRefs = remember { mutableStateMapOf<Long, androidx.compose.ui.awt.ComposeWindow>() }
        // The window that last had the pointer or the keyboard in it, which is where a project
        // arriving from the command line lands when no window already holds it.
        var focusedId by remember { mutableStateOf(startup.focused) }

        fun bumpRecent(path: Path) {
            val norm = path.toAbsolutePath().normalize()
            recentProjects.remove(norm)
            recentProjects.add(0, norm)
        }

        /** Replaces the workspace with [id] in place, leaving the rest of the window list alone. */
        fun mutate(id: Long, transform: (Workspace) -> Workspace) {
            val idx = workspaces.indexOfFirst { it.id == id }
            if (idx >= 0) workspaces[idx] = transform(workspaces[idx])
        }

        fun raiseWindow(id: Long) {
            val w = windowRefs[id] ?: return
            if ((w.extendedState and Frame.ICONIFIED) != 0) {
                w.extendedState = w.extendedState and Frame.ICONIFIED.inv()
            }
            w.toFront()
            w.requestFocus()
        }

        /** Shows a window that was closed, and raises whichever window it is either way. */
        fun showWindow(id: Long) {
            mutate(id) { it.copy(open = true, closedAt = null) }
            focusedId = id
            // A window that has just been added to the composition has no AWT peer yet, so the raise
            // waits for the frame that creates it.
            SwingUtilities.invokeLater { raiseWindow(id) }
        }

        /**
         * Opens [path] as a new tab of the window with id [into], and shows it. A project that
         * already has a tab — in this window or another — gets a second one rather than the first
         * being reused: two tabs on one project are two places to work in it, and every gesture that
         * comes through here (the project menu, the browse dialog, the bar's "+") is the user asking
         * for a tab outright.
         */
        fun openProject(into: Long, path: Path) {
            val norm = path.toAbsolutePath().normalize()
            Log.info("open project $norm in window $into")
            val tabId = Workspaces.nextTabId(workspaces)
            mutate(into) { ws ->
                // At the end of the bar, where the "+" that opened it is: a new tab appearing in the
                // middle of the row shunts the rest along, and the tab you just made is then not
                // where you were looking. Dragging puts it wherever the user wants it.
                ws.copy(tabs = ws.tabs + ProjectTab(tabId, norm), active = tabId, open = true, closedAt = null)
            }
            focusedId = into
            bumpRecent(norm)
        }

        /** Renames a tab, or — on a blank name — hands it back to its project's directory name. */
        fun renameTab(id: Long, tabId: Long, name: String) {
            mutate(id) { ws ->
                ws.copy(tabs = ws.tabs.map { if (it.id == tabId) it.copy(name = name.trim()) else it })
            }
        }

        /** Another tab on the project the window with [id] is showing — the bar's "+". */
        fun newTab(id: Long) {
            val path = Workspaces.byId(workspaces, id)?.activeTab?.path ?: return
            openProject(id, path)
        }

        /**
         * Brings [path] into view in whichever window already has a tab on it, opening one in [into]
         * when none does, and raises that window. How a project arriving from outside lands —
         * `nop /some/dir`, or another instance forwarding its arguments: that asks to *see* a
         * project, where opening one by hand asks for a tab.
         */
        fun revealProject(into: Long, path: Path) {
            val norm = path.toAbsolutePath().normalize()
            val holder = Workspaces.containing(workspaces, norm)
            if (holder == null) {
                openProject(into, norm)
                SwingUtilities.invokeLater { raiseWindow(into) }
                return
            }
            val tab = holder.tabs.first { it.path == norm }
            mutate(holder.id) { it.copy(active = tab.id, open = true, closedAt = null) }
            focusedId = holder.id
            bumpRecent(norm)
            SwingUtilities.invokeLater { raiseWindow(holder.id) }
        }

        fun closeTab(id: Long, tabId: Long) {
            var closed: Path? = null
            mutate(id) { ws ->
                val tab = ws.tabs.firstOrNull { it.id == tabId } ?: return@mutate ws
                closed = tab.path
                ws.copy(
                    // The next active tab is read off the order as it stood before the removal.
                    active = ProjectTabs.activeAfterClose(ws.tabs, tabId, ws.active),
                    tabs = ws.tabs.filter { it.id != tabId },
                )
            }
            // Keep the just-closed project at the top of recents so it's one click to reopen.
            closed?.let { bumpRecent(it) }
        }

        /** A new, empty window, placed one cascade step off the window it was created from. */
        fun newWindow(name: String, from: Long): Long {
            val id = Workspaces.nextId(workspaces)
            val taken = workspaces.map { it.name }
            workspaces.add(
                Workspace(
                    id = id,
                    name = Workspaces.uniqueName(name, taken),
                    tabs = emptyList(),
                    geometry = Workspaces.cascade(Workspaces.byId(workspaces, from)?.geometry, 1),
                ),
            )
            focusedId = id
            return id
        }

        fun renameWindow(id: Long, name: String) {
            val taken = workspaces.filter { it.id != id }.map { it.name }
            mutate(id) { it.copy(name = Workspaces.uniqueName(name, taken)) }
        }

        /** Throws a parked window away, tabs and all — the picker's discard, once confirmed. */
        fun discardWindow(id: Long) {
            val idx = workspaces.indexOfFirst { it.id == id }
            if (idx >= 0 && !workspaces[idx].open) workspaces.removeAt(idx)
        }

        /**
         * Hands a project tab to another window, and brings that window out to receive it. The
         * windows are written back row by row rather than cleared and refilled: the list is what
         * gets persisted, and an emptied-then-rebuilt one can be seen — and saved — half done.
         */
        fun moveTab(tabId: Long, toId: Long) {
            val moved = Workspaces.moveTab(workspaces.toList(), tabId, toId)
            if (moved.size != workspaces.size) return
            moved.forEachIndexed { i, ws -> if (workspaces[i] != ws) workspaces[i] = ws }
            showWindow(toId)
        }

        /**
         * Closing a window puts it away rather than throwing it out: the workspace keeps its tabs and
         * comes back from any other window's picker. The last window on screen is different — there
         * is nowhere left to reopen it from, so closing that one quits nop, and it stays marked open
         * so the next launch starts where this one left off. So do the windows closed in quick
         * succession just before it: that was nop being put away a window at a time, not each of
         * them being parked (see [Workspaces.quitting]).
         */
        fun closeWindow(id: Long) {
            if (workspaces.count { it.open } <= 1) {
                val quitting = Workspaces.quitting(workspaces.toList(), System.currentTimeMillis())
                quitting.forEachIndexed { i, ws -> if (workspaces[i] != ws) workspaces[i] = ws }
                // Written here rather than left to the save that follows every change: that runs on
                // the composition, which quitting takes down before it gets the chance. Marking the
                // windows open doesn't flash them up either — the exit lands in the same frame.
                Settings.saveWorkspaces(quitting)
                exitApplication()
            } else {
                windowRefs.remove(id)
                val parked = Workspaces.park(workspaces.toList(), id, System.currentTimeMillis())
                if (parked.size == workspaces.size) {
                    // Parked: same list, one row changed.
                    parked.forEachIndexed { i, ws -> if (workspaces[i] != ws) workspaces[i] = ws }
                } else {
                    // A window with no tabs left in it isn't kept — there would be nothing to
                    // reopen, and the picker would offer an empty row for ever.
                    workspaces.removeAll { it.id == id }
                }
                if (focusedId == id) focusedId = workspaces.firstOrNull { it.open }?.id ?: id
            }
        }

        // Start the IPC server so subsequent `nop /some/path` invocations can forward to us.
        // Closed via DisposableEffect's onDispose when the app shuts down.
        DisposableEffect(Unit) {
            val handle = SingleInstance.bind(
                configRoot = Settings.configRoot,
                onOpen = { path ->
                    SwingUtilities.invokeLater {
                        val into = focusedId.takeIf { id -> workspaces.any { it.id == id && it.open } }
                            ?: workspaces.firstOrNull { it.open }?.id
                            ?: focusedId
                        revealProject(into, path)
                    }
                },
                onFocus = { SwingUtilities.invokeLater { raiseWindow(focusedId) } },
                onQuit = {
                    // A newer build is taking over the single-instance slot — step aside so the
                    // fresh code runs instead of this stale process lingering in the background.
                    SwingUtilities.invokeLater { exitApplication() }
                },
            )
            onDispose { handle?.close() }
        }

        // Persist the window list on every change — names, tabs, active tab, geometry, what's open.
        // An empty window list is saved too: the only way it empties is the user closing everything,
        // and that choice should survive a restart.
        LaunchedEffect(Unit) {
            snapshotFlow { workspaces.toList() }
                .distinctUntilChanged()
                .collectLatest { Settings.saveWorkspaces(it) }
        }
        // The active project of the focused window, kept as the single "what was in front last"
        // marker: it is what decides which window to open when a restored layout has none.
        LaunchedEffect(Unit) {
            snapshotFlow { workspaces.firstOrNull { it.id == focusedId }?.activeTab?.path }
                .distinctUntilChanged()
                .collectLatest { Settings.saveActiveProject(it) }
        }
        LaunchedEffect(Unit) {
            snapshotFlow { recentProjects.toList() }
                .distinctUntilChanged()
                .collectLatest { Settings.saveRecentProjects(it) }
        }
        LaunchedEffect(darkMode) { Settings.saveDarkMode(darkMode) }

        // Per-project "has uncommitted changes" flags backing the dirty dot on each project tab,
        // shared by every window since a project's working tree is the same tree wherever its tab
        // sits. The project in front of each window is reported by its own App below, which already
        // holds a fresh status; every other open project is kept current by [ProjectGitPoller], which
        // watches working trees rather than re-walking them (see its docs for what that replaced).
        val projectDirty = remember { mutableStateMapOf<Path, Boolean>() }
        val repoWatcher = remember { RepoWatcher() }
        val projectPoller = remember { ProjectGitPoller(repoWatcher) }
        // The watcher outlives the poller: the active project's panel reads it too, so it is closed
        // second, after the poller has let go of its repositories.
        DisposableEffect(Unit) {
            onDispose {
                projectPoller.close()
                repoWatcher.close()
            }
        }
        // The agent sessions are the one thing that outlives the window showing it (see
        // AgentSessionStore), so they are also the one thing nop has to put down on the way out: a
        // vendor CLI is a real process under a PTY, and quitting with one per tab still running would
        // leave them going with nothing able to reach them.
        DisposableEffect(Unit) { onDispose { AgentSessionStore.disposeAll() } }
        // And the sessions of a project no window has a tab on any more. Parking a window is not
        // that: [Workspaces.allProjects] counts a parked window's tabs, so an agent carries on
        // working while the window it was started in is put away, and is still there when it comes
        // back. Closing the last tab on a project is — that is the user saying they are done with it.
        LaunchedEffect(Unit) {
            snapshotFlow { Workspaces.allProjects(workspaces) }
                .distinctUntilChanged()
                .collect { projects -> AgentSessionStore.retain(projects) }
        }
        // collectLatest restarts the loop when tabs open or close and when a window changes what it
        // is showing — the poller needs to know which projects to leave alone — dropping stale flags
        // first. A project whose window is closed still polls: its dot should be right the moment
        // that window comes back.
        LaunchedEffect(Unit) {
            snapshotFlow {
                Workspaces.allProjects(workspaces) to Workspaces.activeProjects(workspaces)
            }
                .distinctUntilChanged()
                .collectLatest { (paths, active) ->
                    projectDirty.keys.retainAll(paths.toSet())
                    projectPoller.retain(paths)
                    while (true) {
                        val swept = withContext(Dispatchers.IO) { projectPoller.sweep(active) }
                        // Only write flags that actually moved: a no-op write to snapshot state still
                        // counts as a write, and would recompose every bar on every tick.
                        for ((path, dirty) in swept) {
                            if (projectDirty[path] != dirty) projectDirty[path] = dirty
                        }
                        delay(PROJECT_GIT_POLL_MS)
                    }
                }
        }

        val allWindows = workspaces.toList()
        for (workspace in allWindows) {
            if (!workspace.open) continue
            // Keyed on the window's identity so its state — geometry, key triggers, the App under it
            // — belongs to that window and isn't re-seated onto another when the list changes.
            key(workspace.id) {
                WorkspaceWindow(
                    workspace = workspace,
                    windows = allWindows,
                    dirtyProjects = projectDirty.filterValues { it }.keys.toSet(),
                    repoWatcher = repoWatcher,
                    onProjectDirty = { path, dirty -> if (projectDirty[path] != dirty) projectDirty[path] = dirty },
                    recentProjects = recentProjects.toList(),
                    darkMode = darkMode,
                    onFocused = { focusedId = workspace.id },
                    onSelectTab = { tab -> mutate(workspace.id) { it.copy(active = tab) } },
                    onCloseTab = { tab -> closeTab(workspace.id, tab) },
                    onNewTab = { newTab(workspace.id) },
                    onRenameTab = { tab, name -> renameTab(workspace.id, tab, name) },
                    onOpenProject = { path -> openProject(workspace.id, path) },
                    onOpenOther = {
                        pickProjectDir(initial = workspace.activeTab?.path?.toFile())
                            ?.let { openProject(workspace.id, it) }
                    },
                    onReorder = { tabs -> mutate(workspace.id) { it.copy(tabs = tabs) } },
                    onNewWindow = { name -> newWindow(name, workspace.id) },
                    onRenameWindow = { name -> renameWindow(workspace.id, name) },
                    onRenameOtherWindow = ::renameWindow,
                    onShowWindow = ::showWindow,
                    onDiscardWindow = ::discardWindow,
                    onMoveToWindow = ::moveTab,
                    onMoveToNewWindow = { tab, name -> moveTab(tab, newWindow(name, workspace.id)) },
                    onGeometry = { geometry -> mutate(workspace.id) { it.copy(geometry = geometry) } },
                    onToggleTheme = { darkMode = !darkMode },
                    onCloseWindow = { closeWindow(workspace.id) },
                    onRegister = { w -> windowRefs[workspace.id] = w },
                    onUnregister = { windowRefs.remove(workspace.id) },
                )
            }
        }
    }
}

@OptIn(FlowPreview::class, ExperimentalFoundationApi::class)
@Composable
private fun ApplicationScope.WorkspaceWindow(
    workspace: Workspace,
    windows: List<Workspace>,
    dirtyProjects: Set<Path>,
    repoWatcher: RepoWatcher,
    onProjectDirty: (Path, Boolean) -> Unit,
    recentProjects: List<Path>,
    darkMode: Boolean,
    onFocused: () -> Unit,
    onSelectTab: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    onNewTab: () -> Unit,
    onRenameTab: (Long, String) -> Unit,
    onOpenProject: (Path) -> Unit,
    onOpenOther: () -> Unit,
    onReorder: (List<ProjectTab>) -> Unit,
    onNewWindow: (String) -> Unit,
    onRenameWindow: (String) -> Unit,
    onRenameOtherWindow: (Long, String) -> Unit,
    onShowWindow: (Long) -> Unit,
    onDiscardWindow: (Long) -> Unit,
    onMoveToWindow: (Long, Long) -> Unit,
    onMoveToNewWindow: (Long, String) -> Unit,
    onGeometry: (WindowGeometry) -> Unit,
    onToggleTheme: () -> Unit,
    onCloseWindow: () -> Unit,
    onRegister: (androidx.compose.ui.awt.ComposeWindow) -> Unit = {},
    onUnregister: () -> Unit = {},
) {
    val activeTab = workspace.activeTab
    val activeProject = activeTab?.path
    // Read once, at the composition that opens this window: from here on the window state is the
    // authority on where the window is, and it reports changes back through [onGeometry]. A window
    // with nothing of its own falls back to the geometry the single-window builds saved, then to a
    // default — either way [asked] is the size this window was actually given, which is what the
    // first reading is measured against.
    val saved = remember { workspace.geometry ?: Settings.loadWindowGeometry() }
    val asked = remember {
        WindowGeometry(
            width = saved?.width ?: DEFAULT_WINDOW_WIDTH,
            height = saved?.height ?: DEFAULT_WINDOW_HEIGHT,
            x = saved?.x,
            y = saved?.y,
        )
    }
    val windowState = rememberWindowState(
        size = DpSize(asked.width.dp, asked.height.dp),
        position = if (asked.x != null && asked.y != null) {
            WindowPosition(asked.x.dp, asked.y.dp)
        } else {
            WindowPosition.PlatformDefault
        },
    )

    LaunchedEffect(windowState) {
        // What the window over-reports its own size by, measured off its first settled reading and
        // taken off every reading after that — without it the window grows by the decoration on
        // every launch. See [WindowOverhead].
        var overhead: WindowOverhead? = null
        snapshotFlow {
            WindowGeometry(
                width = windowState.size.width.value.toInt().coerceAtLeast(200),
                height = windowState.size.height.value.toInt().coerceAtLeast(200),
                x = windowState.position.x.takeIf { it.isSpecified }?.value?.toInt(),
                y = windowState.position.y.takeIf { it.isSpecified }?.value?.toInt(),
            )
        }
            .debounce(500)
            .distinctUntilChanged()
            .collectLatest { reported ->
                val gap = overhead ?: WindowOverhead.measure(asked, reported).also { overhead = it }
                onGeometry(gap.applyTo(reported))
            }
    }

    // Window icon tinted from the active project's path — gives the taskbar/dock entry a colour
    // that tracks whichever project is in front. Recomputed on theme flip so it stays legible.
    val windowIcon = remember(activeProject, darkMode) {
        activeProject?.let { projectWindowIcon(projectTint(it, isDark = darkMode)) }
    }

    // Two-tap-Shift triggers a project-wide file search. Tracked at window level via
    // onPreviewKeyEvent so it fires regardless of which child (tree, editor, …) has focus.
    val shiftDetector = remember { DoubleShiftDetector() }
    var fileSearchTrigger by remember { mutableStateOf(0) }
    // Ctrl+Shift+F focuses the bottom "Find in files" tab — same window-level wiring as the
    // double-shift detector so it works from any focused child.
    var findInFilesTrigger by remember { mutableStateOf(0) }
    // Ctrl+F opens the in-file search bar above the currently-focused file viewer.
    var findInFileTrigger by remember { mutableStateOf(0) }
    // Ctrl+R opens the same bar with its replacement half showing.
    var replaceInFileTrigger by remember { mutableStateOf(0) }
    // F4 ("jump to source", IntelliJ-style) opens the real working file behind the active diff.
    var jumpToSourceTrigger by remember { mutableStateOf(0) }
    // F5 reloads what's in front: git status, plus the active tab's content from disk.
    var refreshTrigger by remember { mutableStateOf(0) }
    // Ctrl+S writes the active editor's buffer now, rather than waiting out the autosave debounce.
    var saveTrigger by remember { mutableStateOf(0) }
    // Alt+F7 lists where the Java name under the caret is used; Shift+F6 renames it everywhere.
    // Both are IntelliJ's bindings, which is the muscle memory anyone arriving at this feature has.
    var findUsagesTrigger by remember { mutableStateOf(0) }
    var renameSymbolTrigger by remember { mutableStateOf(0) }

    Window(
        state = windowState,
        onCloseRequest = onCloseWindow,
        // The window goes by the name the user gave it — a window is what a group of projects is
        // now, so "games" names one outright. An unnamed window falls back to the project it is
        // showing. scripts/screenshot.sh greps the title to find a running instance.
        title = workspace.title,
        icon = windowIcon,
        onPreviewKeyEvent = { event ->
            val isShift = event.key == Key.ShiftLeft || event.key == Key.ShiftRight
            val fired = when (event.type) {
                KeyEventType.KeyDown -> shiftDetector.onKeyDown(isShift)
                KeyEventType.KeyUp -> shiftDetector.onKeyUp(isShift)
                else -> false
            }
            if (fired) fileSearchTrigger += 1
            if (event.type == KeyEventType.KeyDown &&
                event.isCtrlPressed && event.isShiftPressed && event.key == Key.F
            ) {
                findInFilesTrigger += 1
                return@Window true
            }
            if (event.type == KeyEventType.KeyDown &&
                event.isCtrlPressed && !event.isShiftPressed && event.key == Key.F
            ) {
                findInFileTrigger += 1
                return@Window true
            }
            // Ctrl+R widens that bar into find-and-replace. Consumed like Ctrl+F so it can't also
            // reach the field underneath; a terminal tab keeps its own Ctrl+R (reverse history
            // search) because focus there sits in the Swing terminal widget, which this preview
            // handler never sees.
            if (event.type == KeyEventType.KeyDown &&
                event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed &&
                event.key == Key.R
            ) {
                replaceInFileTrigger += 1
                return@Window true
            }
            // Ctrl+S saves the active editor immediately. Autosave already writes on a debounce,
            // so this is mostly about the answer it gives back: a save the user asked for reports
            // when it can't write, where the silent one behind it never could. Consumed so the
            // keystroke can't also reach the text field underneath.
            if (event.type == KeyEventType.KeyDown &&
                event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed &&
                event.key == Key.S
            ) {
                saveTrigger += 1
                return@Window true
            }
            // Alt+F7 — find usages of the name under the caret. Consumed so the editor beneath
            // never sees it; F7 alone is unbound, so only the Alt combination is claimed.
            if (event.type == KeyEventType.KeyDown && event.key == Key.F7 &&
                event.isAltPressed && !event.isCtrlPressed && !event.isShiftPressed
            ) {
                findUsagesTrigger += 1
                return@Window true
            }
            // Shift+F6 — rename it. Consumed for the same reason, and gated on Shift alone so a
            // bare F6 stays free.
            if (event.type == KeyEventType.KeyDown && event.key == Key.F6 &&
                event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed
            ) {
                renameSymbolTrigger += 1
                return@Window true
            }
            // Plain F4 jumps from the active diff to its working file. Exclude Alt so Alt+F4
            // (close window) keeps working.
            if (event.type == KeyEventType.KeyDown && event.key == Key.F4 &&
                !event.isAltPressed && !event.isCtrlPressed && !event.isShiftPressed
            ) {
                jumpToSourceTrigger += 1
                return@Window true
            }
            // F5 re-reads the world: git status and whatever the active tab is showing. Left
            // unconsumed, unlike F4 — a full-screen program running in a terminal tab may bind it
            // too, and refreshing nop's own view of the repo alongside that does no harm.
            if (event.type == KeyEventType.KeyDown && event.key == Key.F5 &&
                !event.isAltPressed && !event.isCtrlPressed && !event.isShiftPressed
            ) {
                refreshTrigger += 1
            }
            // Never consume — the underlying field/tree still needs to see the key.
            false
        },
    ) {
        DisposableEffect(Unit) {
            onRegister(window)
            // Whichever window the user last touched is where a project handed to nop from the
            // command line lands, so the focus listener is how that question gets answered.
            val listener = object : java.awt.event.WindowAdapter() {
                override fun windowActivated(e: java.awt.event.WindowEvent?) = onFocused()
            }
            window.addWindowListener(listener)
            onDispose {
                window.removeWindowListener(listener)
                onUnregister()
            }
        }
        IntUiTheme(
            theme = if (darkMode) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition(),
            styling = ComponentStyling.default().provide(LocalMenuStyle provides nopMenuStyle(darkMode)),
        ) {
            // nop's own text-field context menu, in place of Jewel's icon-carrying one — see
            // [NopTextContextMenu].
            CompositionLocalProvider(LocalTextContextMenu provides NopTextContextMenu) {
            // The project bar spans the top of the window, under the title; the workspace for the
            // active project fills everything under it.
            Column(modifier = Modifier.fillMaxSize()) {
                ProjectBar(
                    tabs = workspace.tabs,
                    activeTab = workspace.active,
                    dirtyProjects = dirtyProjects,
                    windows = windows,
                    windowId = workspace.id,
                    onSelect = onSelectTab,
                    onClose = onCloseTab,
                    onNewTab = onNewTab,
                    onRenameTab = onRenameTab,
                    onReorder = onReorder,
                    onOpenOther = onOpenOther,
                    onNewWindow = onNewWindow,
                    onRenameWindow = onRenameWindow,
                    onRenameOtherWindow = onRenameOtherWindow,
                    onShowWindow = onShowWindow,
                    onDiscardWindow = onDiscardWindow,
                    onMoveToWindow = onMoveToWindow,
                    onMoveToNewWindow = onMoveToNewWindow,
                    onToggleTheme = onToggleTheme,
                    isDark = darkMode,
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (activeTab != null && activeProject != null) {
                        // Key the workspace on the active tab: each App owns per-project state (git
                        // repo, tabs, indexes) behind remember(projectPath), so re-keying on a tab
                        // switch tears the old tab's state down and builds the new one's — including
                        // between two tabs that happen to be on the same project.
                        key(activeTab.id) {
                            App(
                                projectPath = activeProject,
                                repoWatcher = repoWatcher,
                                onDirtyChange = { dirty -> onProjectDirty(activeProject, dirty) },
                                recentProjects = recentProjects,
                                openProjects = workspace.projects,
                                onOpenProject = onOpenProject,
                                onOpenOtherProject = onOpenOther,
                                fileSearchTrigger = fileSearchTrigger,
                                findInFilesTrigger = findInFilesTrigger,
                                findInFileTrigger = findInFileTrigger,
                                replaceInFileTrigger = replaceInFileTrigger,
                                jumpToSourceTrigger = jumpToSourceTrigger,
                                refreshTrigger = refreshTrigger,
                                saveTrigger = saveTrigger,
                                findUsagesTrigger = findUsagesTrigger,
                                renameSymbolTrigger = renameSymbolTrigger,
                            )
                        }
                    } else {
                        WindowPickerPanel(
                            parked = Workspaces.parked(windows),
                            thisWindow = workspace,
                            onOpenWindow = onShowWindow,
                            onRenameWindow = onRenameOtherWindow,
                            onDiscardWindow = onDiscardWindow,
                            onNewWindow = { onNewWindow("") },
                            onOpenProject = onOpenOther,
                        )
                    }
                }
            }
            }
        }
    }
}

/** The window list nop starts with, and which of those windows the user is taken to. */
private data class Startup(val workspaces: List<Workspace>, val focused: Long)

/**
 * Works out what to put on screen. Normally that is the saved window list, with the windows that
 * were showing when nop last exited showing again.
 *
 * A project [arg] from the command line is added to the picture rather than replacing it: the window
 * that already holds that project is opened and focused, and if no window does, it joins the first
 * window that was going to open anyway. A launch that finds nothing saved at all — a first run —
 * asks for a directory and makes one unnamed window for it.
 */
private fun resolveStartup(arg: Path?): Startup {
    val saved = Workspaces.opened(Settings.loadWorkspaces(), Settings.loadActiveProject())
    if (arg != null) {
        val holder = Workspaces.containing(saved, arg)
        if (holder != null) {
            // Whichever of that window's tabs is on the project — a project may have more than one,
            // and the first is as good an answer as any to "show me this".
            val tab = holder.tabs.first { it.path == arg }
            val opened = Workspaces.update(saved, holder.id) { it.copy(open = true, active = tab.id) }
            return Startup(opened, holder.id)
        }
        val into = saved.firstOrNull { it.open } ?: saved.firstOrNull()
        if (into != null) {
            val tab = ProjectTab(Workspaces.nextTabId(saved), arg)
            val opened = Workspaces.update(saved, into.id) {
                it.copy(open = true, tabs = it.tabs + tab, active = tab.id)
            }
            return Startup(opened, into.id)
        }
        return Startup(listOf(singleTabWindow(arg)), 0)
    }
    if (saved.isNotEmpty()) {
        return Startup(saved, saved.first { it.open }.id)
    }
    val picked = pickProjectDir(initial = null) ?: return Startup(emptyList(), 0)
    return Startup(listOf(singleTabWindow(picked)), 0)
}

/** The one window a launch with nothing saved starts with: [project], and nothing else. */
private fun singleTabWindow(project: Path): Workspace {
    val tabs = ProjectTabs.of(listOf(project))
    return Workspace(id = 0, name = "", tabs = tabs, active = tabs.first().id)
}

/** Shows a directory chooser. Returns null if the user cancelled. */
private fun pickProjectDir(initial: File?): Path? {
    val chooser = JFileChooser().apply {
        dialogTitle = "nop — choose a project directory"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        currentDirectory = initial ?: File(System.getProperty("user.home"))
    }
    val res = chooser.showOpenDialog(null)
    if (res != JFileChooser.APPROVE_OPTION) return null
    val selected = chooser.selectedFile ?: return null
    return selected.toPath().toAbsolutePath().normalize()
}
