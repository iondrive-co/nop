package iondrive.nop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import iondrive.nop.ui.App
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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JFileChooser
import kotlin.system.exitProcess

@OptIn(FlowPreview::class)
fun main(args: Array<String>) {
    val initial = resolveProjectPath(args) ?: exitProcess(0)

    application {
        var projectPath by remember { mutableStateOf(initial) }
        var darkMode by remember { mutableStateOf(Settings.loadDarkMode()) }

        // Persist whichever project the user is currently looking at — initial pick or a later
        // change via the in-app picker — so the next launch reopens the same one.
        LaunchedEffect(projectPath) { Settings.saveLastProject(projectPath) }
        LaunchedEffect(darkMode) { Settings.saveDarkMode(darkMode) }

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

        // Persist size/position whenever they settle. Debounced so a drag-resize doesn't write
        // on every intermediate value.
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

        Window(
            state = windowState,
            onCloseRequest = ::exitApplication,
            title = "nop — ${projectPath.fileName}",
        ) {
            IntUiTheme(
                theme = if (darkMode) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition(),
                styling = ComponentStyling.default(),
            ) {
                App(
                    projectPath = projectPath,
                    onChangeProject = {
                        // Blocking Swing dialog — we're on the AWT/Compose dispatcher already,
                        // so it pauses the UI thread which is what we want for a modal picker.
                        pickProjectDir(initial = projectPath.toFile())?.let { picked ->
                            projectPath = picked
                        }
                    },
                    onToggleTheme = { darkMode = !darkMode },
                )
            }
        }
    }
}

private fun resolveProjectPath(args: Array<String>): Path? {
    if (args.isNotEmpty()) {
        return Paths.get(args[0]).toAbsolutePath().normalize()
    }
    val saved = Settings.loadLastProject()
    if (saved != null && Files.isDirectory(saved)) return saved
    return pickProjectDir(initial = saved?.takeIf { Files.exists(it) }?.toFile())
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
