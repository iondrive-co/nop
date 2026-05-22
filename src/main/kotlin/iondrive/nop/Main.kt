package iondrive.nop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
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
        // Tracks the ComposeWindow currently rendering each project, keyed by absolute path. The
        // openProject() callback below reads this to bring an existing window to the foreground
        // instead of trying (and silently failing) to add a duplicate entry to openProjects.
        // Mutated only from the EDT, so a plain HashMap is fine.
        val windowRegistry = remember { mutableMapOf<Path, androidx.compose.ui.awt.ComposeWindow>() }

        // One entry per open window. Adding a path opens a new window; removing one closes it.
        // When the list empties we exit. Using a state list so Compose recomposes on changes.
        val openProjects = remember { mutableStateListOf<Path>().apply { addAll(initial) } }
        var darkMode by remember { mutableStateOf(Settings.loadDarkMode()) }
        var recentProjects by remember { mutableStateOf(Settings.loadRecentProjects()) }

        // Seed the recents list with whatever we just opened, so the dropdown is populated on a
        // brand-new install too. Reversed so the very first entry ends up at the top.
        LaunchedEffect(Unit) {
            for (p in initial.reversed()) Settings.addRecentProject(p)
            recentProjects = Settings.loadRecentProjects()
        }

        fun focusExistingWindow(path: Path): Boolean {
            val existing = windowRegistry[path] ?: return false
            // De-iconify if the user had it minimised, otherwise toFront() doesn't make it
            // visible.
            if ((existing.extendedState and Frame.ICONIFIED) != 0) {
                existing.extendedState = existing.extendedState and Frame.ICONIFIED.inv()
            }
            existing.toFront()
            existing.requestFocus()
            return true
        }

        fun openProject(path: Path) {
            val norm = path.toAbsolutePath().normalize()
            if (!focusExistingWindow(norm) && norm !in openProjects) {
                openProjects.add(norm)
            }
            Settings.addRecentProject(norm)
            recentProjects = Settings.loadRecentProjects()
        }

        // Start the IPC server so subsequent `nop /some/path` invocations can forward to us.
        // Closed via DisposableEffect's onDispose when the app shuts down.
        DisposableEffect(Unit) {
            val handle = SingleInstance.bind(
                configRoot = Settings.configRoot,
                onOpen = { path ->
                    SwingUtilities.invokeLater { openProject(path) }
                },
                onFocus = {
                    SwingUtilities.invokeLater {
                        // No specific project requested — surface whichever window is on top of
                        // the registry (insertion-ordered HashMap gives us the most recently
                        // added project, which is good enough as a "raise nop" signal).
                        windowRegistry.keys.lastOrNull()?.let { focusExistingWindow(it) }
                    }
                },
            )
            onDispose { handle?.close() }
        }

        LaunchedEffect(Unit) {
            // Never persist an empty list: when the user closes the last window we want the
            // *previous* state to survive on disk so the next launch reopens it instead of
            // dropping back to a fresh picker. The list naturally going to zero means the app
            // is about to exit — leaving the previous content in place is the desired behaviour.
            snapshotFlow { openProjects.toList() }
                .distinctUntilChanged()
                .collectLatest { if (it.isNotEmpty()) Settings.saveOpenProjects(it) }
        }
        LaunchedEffect(darkMode) { Settings.saveDarkMode(darkMode) }

        val snapshot = openProjects.toList()
        snapshot.forEachIndexed { index, projectPath ->
            ProjectWindow(
                projectPath = projectPath,
                isFirstWindow = index == 0,
                darkMode = darkMode,
                recentProjects = recentProjects,
                onPickRecent = ::openProject,
                onPickNew = {
                    pickProjectDir(initial = projectPath.toFile())?.let(::openProject)
                },
                onToggleTheme = { darkMode = !darkMode },
                onCloseWindow = {
                    openProjects.remove(projectPath)
                    if (openProjects.isEmpty()) exitApplication()
                },
                onRegister = { w -> windowRegistry[projectPath] = w },
                onUnregister = { windowRegistry.remove(projectPath) },
            )
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ApplicationScope.ProjectWindow(
    projectPath: Path,
    isFirstWindow: Boolean,
    darkMode: Boolean,
    recentProjects: List<Path>,
    onPickRecent: (Path) -> Unit,
    onPickNew: () -> Unit,
    onToggleTheme: () -> Unit,
    onCloseWindow: () -> Unit,
    onRegister: (androidx.compose.ui.awt.ComposeWindow) -> Unit = {},
    onUnregister: () -> Unit = {},
) {
    // The first window restores the persisted geometry; additional windows let the OS pick
    // a fresh position so they don't all stack on top of each other.
    val saved = Settings.loadWindowGeometry()
    val windowState = rememberWindowState(
        size = DpSize(
            width = saved?.width?.dp ?: 1000.dp,
            height = saved?.height?.dp ?: 700.dp,
        ),
        position = if (isFirstWindow && saved?.x != null && saved.y != null) {
            WindowPosition(saved.x.dp, saved.y.dp)
        } else {
            WindowPosition.PlatformDefault
        },
    )

    // Only the first window writes back geometry — otherwise every newly-opened additional
    // window would race to overwrite the saved position with its own.
    if (isFirstWindow) {
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
    }

    // Per-window icon tinted from the project path — gives each project a recognisable colour in
    // the taskbar/dock instead of every nop window looking identical. The tint matches the in-app
    // identity strip, and is recomputed on theme flip so the icon stays legible.
    val windowIcon = remember(projectPath, darkMode) {
        projectWindowIcon(projectTint(projectPath, isDark = darkMode))
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

    Window(
        state = windowState,
        onCloseRequest = onCloseWindow,
        // Title carries both the app and the project so taskbars are scannable across many
        // open windows. scripts/screenshot.sh greps for "nop — " to find a running instance.
        title = "nop — ${projectPath.fileName}",
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
            App(
                projectPath = projectPath,
                recentProjects = recentProjects,
                onPickRecent = onPickRecent,
                onPickNew = onPickNew,
                onToggleTheme = onToggleTheme,
                fileSearchTrigger = fileSearchTrigger,
                findInFilesTrigger = findInFilesTrigger,
                findInFileTrigger = findInFileTrigger,
            )
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
