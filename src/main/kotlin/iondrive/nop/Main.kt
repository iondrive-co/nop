package iondrive.nop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import iondrive.nop.ipc.SingleInstance
import iondrive.nop.ui.App
import iondrive.nop.ui.DoubleShiftDetector
import iondrive.nop.ui.EmptyProjectState
import iondrive.nop.ui.ProjectRail
import iondrive.nop.ui.projectTint
import iondrive.nop.ui.projectWindowIcon
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.ui.ComponentStyling
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

@OptIn(FlowPreview::class)
fun main(args: Array<String>) {
    val argPaths = args.map { Paths.get(it).toAbsolutePath().normalize() }
        .filter { Files.isDirectory(it) }

    // If another nop is already running, hand the requested paths off to it (or just ask it
    // to come to the foreground when no paths were supplied) and exit. The primary will dedupe
    // already-open projects and focus their windows, so the user gets one window per project.
    if (SingleInstance.tryForward(argPaths, Settings.configRoot)) {
        exitProcess(0)
    }

    val initial = resolveInitialProjects(args)
    if (initial.isEmpty()) exitProcess(0)

    application {
        // The persistent rail layout shown in the left rail: project tabs interleaved with named
        // separators (bold group labels). Every project opened stays here — switching tabs only
        // changes which one is active; the little "x" removes one for good. Separators are added
        // from the "+" menu and dragged into place. Restored from disk; when nop was launched with
        // an explicit project arg we start fresh from that arg instead. Saved on every change.
        val railItems = remember {
            mutableStateListOf<RailItem>().apply {
                if (args.isNotEmpty()) addAll(initial.map { RailItem.Project(it) })
                else addAll(Settings.loadRailLayout().ifEmpty { initial.map { RailItem.Project(it) } })
            }
        }
        // Next id to hand a freshly-created separator. Separator ids are runtime-only (drag keys +
        // rename/remove targeting); seed past whatever the restored layout already used.
        var nextSepId by remember {
            mutableStateOf((railItems.filterIsInstance<RailItem.Separator>().maxOfOrNull { it.id } ?: -1L) + 1L)
        }
        // Most-recently-used projects, newest first, backing the "+" tab's dropdown. Seeded from
        // disk unioned with whatever's open now, so even a first run (before this list was tracked)
        // offers the current projects once they're closed.
        val recentProjects = remember {
            mutableStateListOf<Path>().apply {
                addAll((initial + Settings.loadRecentProjects()).map { it.toAbsolutePath().normalize() }.distinct())
            }
        }
        var darkMode by remember { mutableStateOf(Settings.loadDarkMode()) }
        // The active tab — the project the workspace is currently showing. Restored from disk when
        // it's still one of the open tabs, otherwise the first tab. Null only once every tab is
        // closed, which surfaces the empty state.
        var activeProject by remember {
            mutableStateOf(ProjectTabs.initialActive(RailLayout.projects(railItems), Settings.loadActiveProject()))
        }
        // The live window, captured so the IPC "focus" signal can raise it.
        val windowRef = remember { mutableStateOf<androidx.compose.ui.awt.ComposeWindow?>(null) }

        fun bumpRecent(path: Path) {
            val norm = path.toAbsolutePath().normalize()
            recentProjects.remove(norm)
            recentProjects.add(0, norm)
        }

        fun openProject(path: Path) {
            val norm = path.toAbsolutePath().normalize()
            if (railItems.none { it is RailItem.Project && it.path == norm }) {
                railItems.add(RailItem.Project(norm))
            }
            activeProject = norm
            bumpRecent(norm)
        }

        fun closeProject(path: Path) {
            val norm = path.toAbsolutePath().normalize()
            val idx = railItems.indexOfFirst { it is RailItem.Project && it.path == norm }
            if (idx < 0) return
            // Compute the next active tab from the pre-removal project order, then drop the tab.
            val next = ProjectTabs.activeAfterClose(RailLayout.projects(railItems), norm, activeProject)
            railItems.removeAt(idx)
            activeProject = next
            // Keep the just-closed project at the top of recents so it's one click to reopen.
            bumpRecent(norm)
        }

        // Append a new separator; the user drags it up into place. Blank names get a placeholder so
        // the row is still visible and right-clickable to rename.
        fun addSeparator(name: String) {
            railItems.add(RailItem.Separator(name.trim().ifBlank { "Group" }, nextSepId))
            nextSepId += 1
        }

        fun renameSeparator(index: Int, name: String) {
            val item = railItems.getOrNull(index)
            if (item is RailItem.Separator) {
                railItems[index] = RailItem.Separator(name.trim().ifBlank { "Group" }, item.id)
            }
        }

        fun removeSeparator(index: Int) {
            if (railItems.getOrNull(index) is RailItem.Separator) railItems.removeAt(index)
        }

        // Drag-reorder: move the rail row at [from] to index [to]. Guards keep it a no-op for stale
        // indices that can arrive mid-drag as the list mutates under the pointer.
        fun moveItem(from: Int, to: Int) {
            if (from == to || from !in railItems.indices || to !in railItems.indices) return
            railItems.add(to, railItems.removeAt(from))
        }

        fun raiseWindow() {
            val w = windowRef.value ?: return
            if ((w.extendedState and Frame.ICONIFIED) != 0) {
                w.extendedState = w.extendedState and Frame.ICONIFIED.inv()
            }
            w.toFront()
            w.requestFocus()
        }

        // Start the IPC server so subsequent `nop /some/path` invocations can forward to us.
        // Closed via DisposableEffect's onDispose when the app shuts down.
        DisposableEffect(Unit) {
            val handle = SingleInstance.bind(
                configRoot = Settings.configRoot,
                onOpen = { path ->
                    SwingUtilities.invokeLater {
                        openProject(path)
                        raiseWindow()
                    }
                },
                onFocus = { SwingUtilities.invokeLater { raiseWindow() } },
                onQuit = {
                    // A newer build is taking over the single-instance slot — step aside so the
                    // fresh code runs instead of this stale process lingering in the background.
                    SwingUtilities.invokeLater { exitApplication() }
                },
            )
            onDispose { handle?.close() }
        }

        // Persist the rail layout (projects + separators, in order) and active tab on every change.
        // Empty is saved too: the only way the list empties is the user closing every tab, and that
        // choice should survive a restart.
        LaunchedEffect(Unit) {
            snapshotFlow { railItems.toList() }
                .distinctUntilChanged()
                .collectLatest { Settings.saveRailLayout(it) }
        }
        LaunchedEffect(Unit) {
            snapshotFlow { activeProject }
                .distinctUntilChanged()
                .collectLatest { Settings.saveActiveProject(it) }
        }
        LaunchedEffect(Unit) {
            snapshotFlow { recentProjects.toList() }
                .distinctUntilChanged()
                .collectLatest { Settings.saveRecentProjects(it) }
        }
        LaunchedEffect(darkMode) { Settings.saveDarkMode(darkMode) }

        WorkspaceWindow(
            railItems = railItems.toList(),
            activeProject = activeProject,
            recentProjects = recentProjects.toList(),
            darkMode = darkMode,
            onSelectProject = { activeProject = it },
            onCloseProject = ::closeProject,
            onOpenRecent = ::openProject,
            onOpenOther = { pickProjectDir(initial = activeProject?.toFile())?.let(::openProject) },
            onAddSeparator = ::addSeparator,
            onRenameSeparator = ::renameSeparator,
            onRemoveSeparator = ::removeSeparator,
            onMoveItem = ::moveItem,
            onToggleTheme = { darkMode = !darkMode },
            onCloseWindow = ::exitApplication,
            onRegister = { w -> windowRef.value = w },
            onUnregister = { windowRef.value = null },
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ApplicationScope.WorkspaceWindow(
    railItems: List<RailItem>,
    activeProject: Path?,
    recentProjects: List<Path>,
    darkMode: Boolean,
    onSelectProject: (Path) -> Unit,
    onCloseProject: (Path) -> Unit,
    onOpenRecent: (Path) -> Unit,
    onOpenOther: () -> Unit,
    onAddSeparator: (String) -> Unit,
    onRenameSeparator: (Int, String) -> Unit,
    onRemoveSeparator: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    onToggleTheme: () -> Unit,
    onCloseWindow: () -> Unit,
    onRegister: (androidx.compose.ui.awt.ComposeWindow) -> Unit = {},
    onUnregister: () -> Unit = {},
) {
    val saved = Settings.loadWindowGeometry()
    val windowState = rememberWindowState(
        size = DpSize(
            width = saved?.width?.dp ?: 1000.dp,
            height = saved?.height?.dp ?: 700.dp,
        ),
        position = if (saved?.x != null && saved.y != null) {
            WindowPosition(saved.x.dp, saved.y.dp)
        } else {
            WindowPosition.PlatformDefault
        },
    )

    LaunchedEffect(windowState) {
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
            .collectLatest { Settings.saveWindowGeometry(it) }
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
    // F4 ("jump to source", IntelliJ-style) opens the real working file behind the active diff.
    var jumpToSourceTrigger by remember { mutableStateOf(0) }

    Window(
        state = windowState,
        onCloseRequest = onCloseWindow,
        // Title carries both the app and the active project. scripts/screenshot.sh greps for
        // "nop — " to find a running instance.
        title = "nop — ${activeProject?.fileName ?: "no project"}",
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
            // Plain F4 jumps from the active diff to its working file. Exclude Alt so Alt+F4
            // (close window) keeps working.
            if (event.type == KeyEventType.KeyDown && event.key == Key.F4 &&
                !event.isAltPressed && !event.isCtrlPressed && !event.isShiftPressed
            ) {
                jumpToSourceTrigger += 1
                return@Window true
            }
            // Never consume — the underlying field/tree still needs to see the key.
            false
        },
    ) {
        DisposableEffect(Unit) {
            onRegister(window)
            onDispose { onUnregister() }
        }
        IntUiTheme(
            theme = if (darkMode) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition(),
            styling = ComponentStyling.default(),
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                ProjectRail(
                    items = railItems,
                    activeProject = activeProject,
                    recentProjects = recentProjects,
                    onSelect = onSelectProject,
                    onClose = onCloseProject,
                    onOpenRecent = onOpenRecent,
                    onOpenOther = onOpenOther,
                    onAddSeparator = onAddSeparator,
                    onRenameSeparator = onRenameSeparator,
                    onRemoveSeparator = onRemoveSeparator,
                    onMoveItem = onMoveItem,
                    isDark = darkMode,
                )
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (activeProject != null) {
                        // Key the workspace on the active project: each App owns per-project state
                        // (git repo, tabs, indexes) behind remember(projectPath), so re-keying on a
                        // tab switch tears the old project's state down and builds the new one's.
                        androidx.compose.runtime.key(activeProject) {
                            App(
                                projectPath = activeProject,
                                onToggleTheme = onToggleTheme,
                                fileSearchTrigger = fileSearchTrigger,
                                findInFilesTrigger = findInFilesTrigger,
                                findInFileTrigger = findInFileTrigger,
                                jumpToSourceTrigger = jumpToSourceTrigger,
                            )
                        }
                    } else {
                        EmptyProjectState(onAdd = onOpenOther)
                    }
                }
            }
        }
    }
}

private fun resolveInitialProjects(args: Array<String>): List<Path> {
    if (args.isNotEmpty()) {
        return listOf(Paths.get(args[0]).toAbsolutePath().normalize())
    }
    val saved = Settings.loadOpenProjects().filter { Files.isDirectory(it) }
    if (saved.isNotEmpty()) return saved.map { it.toAbsolutePath().normalize() }.distinct()
    val picked = pickProjectDir(initial = null) ?: return emptyList()
    return listOf(picked)
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
