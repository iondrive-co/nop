package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import iondrive.nop.Log
import iondrive.nop.Settings
import iondrive.nop.WindowGeometry
import iondrive.nop.WindowOverhead
import iondrive.nop.browser.SlackAttention
import iondrive.nop.browser.SlackBrowsers
import iondrive.nop.browser.SlackWorkspaceNames
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.theme.defaultTabStyle
import java.awt.CardLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JPanel

@Serializable
internal data class SlackPlacement(
    val floating: Boolean = false,
    val visible: Boolean = false,
    val workspace: String? = null,
    val width: Int = 900,
    val height: Int = 700,
    val x: Int? = null,
    val y: Int? = null,
)

/** The browser lease belongs to the floating view, independently of a project's editor. */
internal class SlackViewState(
    val project: Path,
    saved: SlackPlacement = SlackPlacement(),
    private val retain: (Path) -> AutoCloseable = SlackBrowsers::retainProjectView,
) {
    val selectedId = mutableStateOf(saved.workspace?.takeIf(SlackWorkspaceNames::validId))
    val visible = mutableStateOf(saved.visible)
    val connectionVersion = mutableStateOf(0)
    var floating by mutableStateOf(saved.floating)
        private set
    var geometry by mutableStateOf(saved)
    var raise by mutableIntStateOf(0)
    private var lease: AutoCloseable? = if (floating && visible.value) retain(project) else null

    fun show(id: String? = selectedId.value) {
        selectedId.value = id
        if (floating && lease == null) lease = retain(project)
        visible.value = true
        raise++
    }

    fun float() {
        if (lease == null) lease = retain(project)
        floating = true
        show()
    }

    fun dock() {
        floating = false
        visible.value = true
    }

    fun dockedViewReady() {
        lease?.close()
        lease = null
    }

    fun hide() {
        visible.value = false
        lease?.close()
        lease = null
    }

    fun placement() = geometry.copy(floating = floating, visible = visible.value, workspace = selectedId.value)
}

internal class SlackWindowStore(private val directory: (Path) -> Path) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(project: Path): SlackPlacement = runCatching {
        val file = directory(project).resolve("slack-window.json")
        if (!Files.isRegularFile(file) || Files.size(file) > 4096) return@runCatching SlackPlacement()
        val saved = json.decodeFromString<SlackPlacement>(Files.readString(file))
        saved.copy(width = saved.width.coerceIn(400, 10000), height = saved.height.coerceIn(300, 10000),
            workspace = saved.workspace?.takeIf(SlackWorkspaceNames::validId))
    }.getOrDefault(SlackPlacement())

    fun save(project: Path, placement: SlackPlacement) {
        runCatching {
            val file = directory(project).resolve("slack-window.json")
            Files.createDirectories(file.parent)
            val temporary = file.resolveSibling("slack-window.json.tmp")
            Files.writeString(temporary, json.encodeToString(placement))
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { Log.warn("Could not save Slack window: ${it.message}") }
    }
}

internal object SlackWindows {
    private val store = SlackWindowStore(Settings::projectDataDir)
    val views = mutableStateMapOf<Path, SlackViewState>()

    fun of(project: Path): SlackViewState {
        val path = project.toAbsolutePath().normalize()
        return views.getOrPut(path) { SlackViewState(path, store.load(path)) }
    }

    fun retain(projects: Collection<Path>) {
        val open = projects.map { it.toAbsolutePath().normalize() }.toSet()
        views.keys.filter { it !in open }.forEach { path ->
            views.remove(path)?.let { view -> view.hide(); save(view, view.placement()) }
        }
        open.forEach(::of)
    }

    fun save(view: SlackViewState, placement: SlackPlacement) = store.save(view.project, placement)
}

@OptIn(FlowPreview::class)
@Composable
internal fun SlackFloatingWindows(dark: Boolean, onDock: (Path) -> Unit) {
    SlackWindows.views.values.toList().forEach { view ->
        key(view.project) {
            LaunchedEffect(view) {
                snapshotFlow { view.placement() }.debounce(300).distinctUntilChanged().collect { SlackWindows.save(view, it) }
            }
            if (view.floating && view.visible.value) SlackFloatingWindow(view, dark) { onDock(view.project); view.dock() }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun SlackFloatingWindow(view: SlackViewState, dark: Boolean, onDock: () -> Unit) {
    val saved = remember { view.geometry }
    val state = rememberWindowState(
        width = saved.width.dp, height = saved.height.dp,
        position = if (saved.x != null && saved.y != null) WindowPosition(saved.x.dp, saved.y.dp) else WindowPosition.PlatformDefault,
    )
    val workspaces by remember(view.project) { SlackBrowsers.workspaces(view.project) }.collectAsState()
    val attention by remember(view.project) { SlackBrowsers.attention(view.project) }.collectAsState()
    val knownWorkspaces by SlackBrowsers.knownWorkspaces.collectAsState()
    val cards = remember { JPanel(CardLayout()) }
    Window(
        onCloseRequest = view::hide, state = state,
        title = "Slack — ${view.project.fileName} — nop",
    ) {
        LaunchedEffect(view.raise) {
            state.isMinimized = false
            window.toFront()
            window.requestFocus()
        }
        LaunchedEffect(state) {
            var overhead: WindowOverhead? = null
            snapshotFlow {
                saved.copy(width = state.size.width.value.toInt(), height = state.size.height.value.toInt(),
                    x = state.position.x.value.takeIf { it.isFinite() }?.toInt(),
                    y = state.position.y.value.takeIf { it.isFinite() }?.toInt())
            }.debounce(300).distinctUntilChanged().collect { reported ->
                val asked = WindowGeometry(saved.width, saved.height, saved.x, saved.y)
                val geometry = WindowGeometry(reported.width, reported.height, reported.x, reported.y)
                val gap = overhead ?: WindowOverhead.measure(asked, geometry).also { overhead = it }
                val corrected = gap.applyTo(geometry)
                view.geometry = reported.copy(width = corrected.width, height = corrected.height)
            }
        }
        IntUiTheme(
            theme = if (dark) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition(),
            styling = ComponentStyling.default(),
        ) {
            CompositionLocalProvider(LocalWindow provides window, LocalTerminalBottomInset provides 0.dp) {
                Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
                    Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onDock) { Text("Dock") }
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                            workspaces.forEach { workspace ->
                                val unread = attention[workspace.id]
                                ToolStripTab(
                                    label = workspace.name, selected = workspace.id == view.selectedId.value,
                                    isDark = dark, style = JewelTheme.defaultTabStyle,
                                    onClick = { view.show(workspace.id) },
                                    onClose = {
                                        SlackBrowsers.close(view.project, workspace.id)
                                        if (view.selectedId.value == workspace.id) {
                                            view.selectedId.value = SlackBrowsers.workspaces(view.project).value.firstOrNull()?.id
                                            if (view.selectedId.value == null) view.hide()
                                        }
                                    },
                                    leading = { SlackTabIcon(unread ?: SlackAttention()) },
                                    emphasis = if (unread?.unread == true) JewelTheme.globalColors.text.normal else null,
                                )
                            }
                            if (view.selectedId.value == null) Text("Sign in…", Modifier.padding(6.dp))
                        }
                        OutlinedButton(onClick = {
                            showTabContextMenu(window, dark,
                                knownWorkspaces.map { workspace ->
                                    TabMenuItem.Item(workspace.name) {
                                        SlackBrowsers.reopen(view.project, workspace.id)
                                        view.show(workspace.id)
                                    }
                                } + TabMenuItem.Item("Sign in to another workspace…") {
                                    SlackBrowsers.connect(view.project)
                                    view.connectionVersion.value++
                                    view.show(null)
                                })
                        }) { Text("+") }
                    }
                    SlackPanel(view.project, view.selectedId.value, view.connectionVersion.value, cards) { view.selectedId.value = it }
                }
            }
        }
    }
}
