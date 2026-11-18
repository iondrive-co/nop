package iondrive.nop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import iondrive.nop.Log
import iondrive.nop.Settings
import iondrive.nop.WindowGeometry
import iondrive.nop.WindowOverhead
import iondrive.nop.browser.SlackAttention
import iondrive.nop.browser.SlackBrowsers
import iondrive.nop.browser.SlackWorkspace
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
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.theme.defaultTabStyle
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JPanel
import kotlin.math.roundToInt

internal const val MIN_SLACK_WIDTH = 400
internal const val MIN_SLACK_HEIGHT = 300
internal const val MAX_SLACK_SIZE = 10000

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
        saved.copy(
            width = saved.width.coerceIn(MIN_SLACK_WIDTH, MAX_SLACK_SIZE),
            height = saved.height.coerceIn(MIN_SLACK_HEIGHT, MAX_SLACK_SIZE),
            workspace = saved.workspace?.takeIf(SlackWorkspaceNames::validId),
        )
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

/** Usable desktop rectangles across connected screens (monitor bounds minus taskbar/panel insets). */
internal fun usableScreenBounds(): List<Rectangle> {
    if (GraphicsEnvironment.isHeadless()) return emptyList()
    return runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.mapNotNull { device ->
            val config = device.defaultConfiguration ?: return@mapNotNull null
            val bounds = Rectangle(config.bounds)
            val insets = runCatching { toolkit.getScreenInsets(config) }.getOrNull()
            if (insets != null) {
                bounds.x += insets.left
                bounds.y += insets.top
                bounds.width -= insets.left + insets.right
                bounds.height -= insets.top + insets.bottom
            }
            bounds.takeIf { it.width > 0 && it.height > 0 }
        }
    }.getOrDefault(emptyList())
}

/** Picks the screen holding the largest share of the window, or the nearest screen if none intersect. */
internal fun pickTargetScreen(x: Int?, y: Int?, width: Int, height: Int, screens: List<Rectangle>): Rectangle? {
    if (screens.isEmpty()) return null
    if (x == null || y == null) return screens.first()
    val target = Rectangle(x, y, width.coerceAtLeast(1), height.coerceAtLeast(1))
    val intersected = screens.maxByOrNull { screen ->
        val hit = target.intersection(screen)
        if (hit.width > 0 && hit.height > 0) hit.width.toLong() * hit.height.toLong() else 0L
    }
    if (intersected != null) {
        val hit = target.intersection(intersected)
        if (hit.width > 0 && hit.height > 0) return intersected
    }
    val cx = x.toLong() + target.width / 2L
    val cy = y.toLong() + target.height / 2L
    return screens.minByOrNull { screen ->
        val sx = screen.x.toLong() + screen.width / 2L
        val sy = screen.y.toLong() + screen.height / 2L
        val dx = cx - sx
        val dy = cy - sy
        dx * dx + dy * dy
    }
}

/** Clamps a floating window's size and position inside its target usable screen so it never opens offscreen. */
internal fun clampPlacement(placement: SlackPlacement, screens: List<Rectangle>): SlackPlacement {
    val screen = pickTargetScreen(placement.x, placement.y, placement.width, placement.height, screens) ?: return placement
    val minW = MIN_SLACK_WIDTH.coerceAtMost(screen.width)
    val minH = MIN_SLACK_HEIGHT.coerceAtMost(screen.height)
    val width = placement.width.coerceIn(minW, screen.width)
    val height = placement.height.coerceIn(minH, screen.height)
    if (placement.x == null || placement.y == null) return placement.copy(width = width, height = height)
    val x = placement.x.coerceIn(screen.x, screen.x + screen.width - width)
    val y = placement.y.coerceIn(screen.y, screen.y + screen.height - height)
    return placement.copy(width = width, height = height, x = x, y = y)
}

/** Whether any corner of [bounds] falls outside all usable [screens] or exceeds its target screen. */
internal fun isWindowOffscreen(bounds: Rectangle, screens: List<Rectangle>): Boolean {
    if (screens.isEmpty() || bounds.width <= 0 || bounds.height <= 0) return false
    val right = bounds.x + bounds.width - 1
    val bottom = bounds.y + bounds.height - 1
    val cornersOnScreen = listOf(
        bounds.x to bounds.y,
        right to bounds.y,
        bounds.x to bottom,
        right to bottom,
    ).all { (cx, cy) -> screens.any { it.contains(cx, cy) } }
    if (!cornersOnScreen) return true
    val target = pickTargetScreen(bounds.x, bounds.y, bounds.width, bounds.height, screens) ?: return false
    return bounds.width > target.width || bounds.height > target.height
}

/** Computes [bounds] clamped onto its target usable screen. */
internal fun clampedWindowBounds(bounds: Rectangle, screens: List<Rectangle>): Rectangle {
    val placement = SlackPlacement(
        width = bounds.width.coerceAtLeast(1),
        height = bounds.height.coerceAtLeast(1),
        x = bounds.x,
        y = bounds.y,
    )
    val clamped = clampPlacement(placement, screens)
    return Rectangle(clamped.x ?: bounds.x, clamped.y ?: bounds.y, clamped.width, clamped.height)
}

/** Keeps the top edge of a dragged window below the top of the screen under [pointer]. */
internal fun clampDragTopY(desiredY: Int, pointer: Point, screens: List<Rectangle>): Int {
    val screen = screens.firstOrNull { it.contains(pointer) }
        ?: pickTargetScreen(pointer.x, pointer.y, 1, 1, screens)
        ?: return desiredY
    return desiredY.coerceAtLeast(screen.y)
}

@OptIn(FlowPreview::class)
@Composable
private fun SlackFloatingWindow(view: SlackViewState, dark: Boolean, onDock: () -> Unit) {
    val saved = remember {
        clampPlacement(view.geometry, usableScreenBounds()).also { clamped ->
            if (clamped != view.geometry) view.geometry = clamped
        }
    }
    val state = rememberWindowState(
        width = saved.width.dp, height = saved.height.dp,
        position = if (saved.x != null && saved.y != null) WindowPosition(saved.x.dp, saved.y.dp) else WindowPosition.PlatformDefault,
    )
    val workspaces by remember(view.project) { SlackBrowsers.workspaces(view.project) }.collectAsState()
    val attention by remember(view.project) { SlackBrowsers.attention(view.project) }.collectAsState()
    val knownWorkspaces by SlackBrowsers.knownWorkspaces.collectAsState()
    val cards = remember { JPanel(CardLayout()) }
    val asked = remember(saved) { WindowGeometry(saved.width, saved.height, saved.x, saved.y) }
    var overhead by remember { mutableStateOf<WindowOverhead?>(null) }
    fun applyReported(reported: WindowGeometry) {
        val gap = overhead ?: WindowOverhead.measure(asked, reported).also { overhead = it }
        val corrected = gap.applyTo(reported)
        view.geometry = saved.copy(
            width = corrected.width,
            height = corrected.height,
            x = reported.x,
            y = reported.y,
        )
    }
    fun syncGeometry() {
        val w = state.size.width.value.takeIf { it.isFinite() }?.toInt() ?: return
        val h = state.size.height.value.takeIf { it.isFinite() }?.toInt() ?: return
        applyReported(
            WindowGeometry(
                width = w,
                height = h,
                x = state.position.x.value.takeIf { it.isFinite() }?.toInt(),
                y = state.position.y.value.takeIf { it.isFinite() }?.toInt(),
            ),
        )
    }
    Window(
        onCloseRequest = {
            syncGeometry()
            view.hide()
        },
        state = state,
        title = "Slack — ${view.project.fileName} — nop",
    ) {
        var offscreen by remember { mutableStateOf(false) }
        fun updateOffscreen() {
            if (window.isShowing) {
                offscreen = isWindowOffscreen(window.bounds, usableScreenBounds())
            }
        }
        fun bringOnScreen() {
            val screens = usableScreenBounds()
            if (screens.isEmpty()) return
            val current = window.bounds
            val target = clampedWindowBounds(current, screens)
            if (target != current) {
                if (target.size != current.size) window.setBounds(target) else window.setLocation(target.x, target.y)
            }
            updateOffscreen()
        }
        DisposableEffect(window) {
            val componentListener = object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent?) = updateOffscreen()
                override fun componentResized(e: ComponentEvent?) = updateOffscreen()
                override fun componentShown(e: ComponentEvent?) = updateOffscreen()
            }
            val windowListener = object : WindowAdapter() {
                override fun windowOpened(e: WindowEvent?) {
                    val screens = usableScreenBounds()
                    if (isWindowOffscreen(window.bounds, screens)) bringOnScreen() else updateOffscreen()
                }
            }
            window.addComponentListener(componentListener)
            window.addWindowListener(windowListener)
            onDispose {
                window.removeComponentListener(componentListener)
                window.removeWindowListener(windowListener)
            }
        }
        var raisedOnce by remember { mutableStateOf(false) }
        LaunchedEffect(view.raise) {
            state.isMinimized = false
            window.toFront()
            window.requestFocus()
            // Skip initial composition before the WM maps the window; windowOpened handles that once mapped.
            if (raisedOnce && isWindowOffscreen(window.bounds, usableScreenBounds())) {
                bringOnScreen()
            }
            raisedOnce = true
        }
        LaunchedEffect(state) {
            snapshotFlow {
                WindowGeometry(
                    width = state.size.width.value.toInt(),
                    height = state.size.height.value.toInt(),
                    x = state.position.x.value.takeIf { it.isFinite() }?.toInt(),
                    y = state.position.y.value.takeIf { it.isFinite() }?.toInt(),
                )
            }.debounce(300).distinctUntilChanged().collect(::applyReported)
        }
        IntUiTheme(
            theme = if (dark) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition(),
            styling = ComponentStyling.default(),
        ) {
            CompositionLocalProvider(LocalWindow provides window, LocalTerminalBottomInset provides 0.dp) {
                SlackFloatingContent(
                    workspaces = workspaces,
                    selectedId = view.selectedId.value,
                    attention = attention,
                    onDock = {
                        syncGeometry()
                        onDock()
                    },
                    onSelect = { view.show(it) },
                    onClose = { id ->
                        SlackBrowsers.close(view.project, id)
                        if (view.selectedId.value == id) {
                            view.selectedId.value = SlackBrowsers.workspaces(view.project).value.firstOrNull()?.id
                            if (view.selectedId.value == null) view.hide()
                        }
                    },
                    onAdd = {
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
                    },
                    offscreen = offscreen,
                    onBringOnScreen = ::bringOnScreen,
                    window = window,
                ) {
                    SlackPanel(view.project, view.selectedId.value, view.connectionVersion.value, cards) { view.selectedId.value = it }
                }
            }
        }
    }
}

/** Tabs fill the toolbar's fixed height; the browser owns the remaining window space. */
@Composable
internal fun SlackFloatingContent(
    workspaces: List<SlackWorkspace>,
    selectedId: String?,
    attention: Map<String, SlackAttention>,
    onDock: () -> Unit,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onAdd: () -> Unit,
    offscreen: Boolean = false,
    onBringOnScreen: () -> Unit = {},
    window: java.awt.Window? = null,
    onDragBy: ((Int, Int) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val dark = JewelTheme.isDark
    Column(Modifier.fillMaxSize().background(JewelTheme.globalColors.panelBackground)) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onDock) { Text("Dock") }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                workspaces.forEach { workspace ->
                    val unread = attention[workspace.id]
                    ToolStripTab(
                        label = workspace.name, selected = workspace.id == selectedId,
                        isDark = dark, style = JewelTheme.defaultTabStyle,
                        onClick = { onSelect(workspace.id) }, onClose = { onClose(workspace.id) },
                        leading = { SlackTabIcon(unread ?: SlackAttention()) },
                        emphasis = if (unread?.unread == true) JewelTheme.globalColors.text.normal else null,
                    )
                }
                if (selectedId == null) Text("Sign in…", Modifier.padding(6.dp))
            }
            OutlinedButton(onClick = onAdd) { Text("Add workspace…") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        FloatingMoveBar(
            dark = dark,
            offscreen = offscreen,
            onDock = onDock,
            onBringOnScreen = onBringOnScreen,
            window = window,
            onDragBy = onDragBy,
        )
    }
}

/**
 * Bottom move handle so the floating window can still be dragged, brought back on screen, or
 * docked even when its OS title bar and top toolbar are pushed above the top of the screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FloatingMoveBar(
    dark: Boolean,
    offscreen: Boolean,
    onDock: () -> Unit,
    onBringOnScreen: () -> Unit,
    window: java.awt.Window?,
    onDragBy: ((Int, Int) -> Unit)?,
) {
    val palette = remember(dark) { StripPalette(dark) }
    val gripColor = if (dark) Color(0xFF6F737A) else Color(0xFF9AA0A6)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(palette.strip)
            .drawWithContent {
                drawContent()
                drawLine(palette.divider, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1f)
            }
            .windowDragMove(window, onDragBy)
            .let { m ->
                if (window != null) {
                    m.pointerInput(window, dark, onDock, onBringOnScreen) {
                        secondaryClicks {
                            showTabContextMenu(
                                window,
                                dark,
                                listOf(
                                    TabMenuItem.Item("Bring on screen", onClick = onBringOnScreen),
                                    TabMenuItem.Item("Dock in window", onClick = onDock),
                                ),
                            )
                        }
                    }
                } else {
                    m
                }
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (offscreen) {
            Text(
                "Dock",
                color = palette.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onDock).padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Tooltip(tooltip = { Text("Drag to move window") }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.semantics { contentDescription = "Drag to move window" },
                ) {
                    Box(
                        Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .background(gripColor, RoundedCornerShape(2.dp)),
                    )
                    Text("Move", color = palette.text, fontSize = 10.sp)
                }
            }
        }
        if (offscreen) {
            Text(
                "Bring on screen",
                color = palette.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onBringOnScreen).padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Drags [window] using global screen pointer coordinates so moving the window itself does not
 * introduce feedback oscillation in local Compose coordinates.
 */
private fun Modifier.windowDragMove(
    window: java.awt.Window?,
    onDragBy: ((Int, Int) -> Unit)? = null,
): Modifier {
    if (window == null && onDragBy == null) return this
    return this
        .pointerHoverIcon(PointerIcon(Cursor(Cursor.MOVE_CURSOR)))
        .pointerInput(window, onDragBy) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                val startPointer = if (window != null) MouseInfo.getPointerInfo()?.location else null
                val startWindow = window?.location
                var lastLocal = down.position
                down.consume()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    change.consume()
                    if (window != null && startPointer != null && startWindow != null) {
                        val current = MouseInfo.getPointerInfo()?.location ?: continue
                        val targetX = startWindow.x + (current.x - startPointer.x)
                        val rawY = startWindow.y + (current.y - startPointer.y)
                        val targetY = clampDragTopY(rawY, current, usableScreenBounds())
                        if (window.x != targetX || window.y != targetY) {
                            window.setLocation(targetX, targetY)
                        }
                    } else if (onDragBy != null) {
                        val dx = (change.position.x - lastLocal.x).roundToInt()
                        val dy = (change.position.y - lastLocal.y).roundToInt()
                        if (dx != 0 || dy != 0) {
                            onDragBy(dx, dy)
                            lastLocal = change.position
                        }
                    }
                }
            }
        }
}
