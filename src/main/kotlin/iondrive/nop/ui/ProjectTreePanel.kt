package iondrive.nop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import iondrive.nop.git.GitStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.InternalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.lazy.tree.ChildrenGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.lazy.tree.rememberTreeState
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.component.styling.LazyTreeIcons
import org.jetbrains.jewel.ui.component.styling.LazyTreeStyle
import org.jetbrains.jewel.ui.component.styling.LocalLazyTreeStyle
import org.jetbrains.jewel.ui.icon.PathIconKey
import java.io.File
import java.nio.file.Path

internal val ProjectIconTintDark = Color(0xFFAFB8C4)
internal val ProjectIconTintLight = Color(0xFF4A5360)

// Tree row label colour. The dark theme keeps Jewel's default (null → inherit); the light theme's
// default ran too pale against the near-white panel, so name a near-black that matches the editor.
internal val ProjectTextLight = Color(0xFF1F2329)

// Jewel's LazyTree resolves its default chevrons through the IntelliJ Platform icons
// jar, which we don't depend on. Point it at bundled SVGs instead so folder rows
// don't render as magenta missing-icon placeholders.
private object ProjectIconsClass
private val ChevronCollapsedIconKey = PathIconKey("icons/chevron-right.svg", ProjectIconsClass::class.java)
private val ChevronExpandedIconKey = PathIconKey("icons/chevron-down.svg", ProjectIconsClass::class.java)

private val IGNORED_DIR_NAMES = setOf(
    ".git", ".idea", ".gradle", ".vscode",
    "node_modules", "build", "out", "target", "dist", ".next", "__pycache__",
)

private fun Path.asFilteredTree(): Tree<File> = buildTree {
    val root = toFile()
    addNode(root, id = root.absolutePath) { addChildren(root) }
}

private fun ChildrenGeneratorScope<File>.addChildren(dir: File) {
    val files = dir.listFiles() ?: return
    files
        // Dotfiles (.claude, .github, .gitignore, …) are shown; only the curated build/VCS
        // directories in IGNORED_DIR_NAMES are pruned, so the tree mirrors what's on disk.
        .filter { it.name !in IGNORED_DIR_NAMES }
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        .forEach { file ->
            if (file.isFile) {
                addLeaf(file, id = file.absolutePath)
            } else {
                addNode(file, id = file.absolutePath) { addChildren(file) }
            }
        }
}

private fun visibleChildren(dir: File): List<File> = (dir.listFiles() ?: emptyArray())
    .filter { it.name !in IGNORED_DIR_NAMES }
    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

/**
 * Returns the 0-based row index of [targetPath] in the same DFS-of-expanded-nodes ordering the
 * LazyTree uses, given [openIds] (absolute paths of directories that are expanded). Returns -1
 * when the target isn't part of the visible tree, e.g. because an ancestor isn't open.
 */
internal fun flattenedRowIndexOf(rootFile: File, targetPath: String, openIds: Set<String>): Int {
    var counter = 0
    fun walk(file: File): Int {
        if (file.absolutePath == targetPath) return counter
        counter++
        if (!file.isDirectory) return -1
        if (file.absolutePath != rootFile.absolutePath && file.absolutePath !in openIds) return -1
        for (child in visibleChildren(file)) {
            val found = walk(child)
            if (found >= 0) return found
        }
        return -1
    }
    return walk(rootFile)
}

private fun File.relativePathTo(repoRoot: Path): String? = runCatching {
    repoRoot.toAbsolutePath().normalize().relativize(this.toPath().toAbsolutePath().normalize())
        .toString().replace(File.separatorChar, '/')
}.getOrNull()?.takeIf { !it.startsWith("..") }

@OptIn(
    ExperimentalJewelApi::class,
    InternalJewelApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)
@Composable
fun ProjectTreePanel(
    projectPath: Path,
    status: GitStatus,
    refreshKey: Int = 0,
    revealFile: File? = null,
    // Like revealFile, but driven by the app rather than the active tab: set to a just-created
    // directory/package so the tree expands its ancestors, selects it, and scrolls it into view.
    revealRequest: File? = null,
    onFileClick: (File) -> Unit,
    onDeleteRequest: (File) -> Unit = {},
    onHistoryRequest: (File) -> Unit = {},
    blameEnabled: Boolean = false,
    onToggleBlame: () -> Unit = {},
    onOpenInSystem: (File) -> Unit = ::openInSystem,
    // Context-menu actions. The File passed is whatever the user right-clicked; the app decides
    // where to create relative to it (inside a directory, alongside a file).
    onNewFile: (File) -> Unit = {},
    onNewDirectory: (File) -> Unit = {},
    onNewPackage: (File) -> Unit = {},
    onCopyFile: (File) -> Unit = {},
    headerExtras: @Composable () -> Unit = {},
) {
    val tree = remember(projectPath, refreshKey) { projectPath.asFilteredTree() }
    val treeState = rememberTreeState()
    val rootId = remember(projectPath) { projectPath.toFile().absolutePath }

    LaunchedEffect(rootId) {
        treeState.openNodes(listOf(rootId))
    }

    // Expand ancestors and select [target] so the tree mirrors it, scrolling it into view when
    // it's offscreen (typical after a Ctrl-click jump into a deeply nested role, or after
    // creating a directory under a collapsed folder). Shared by the active-tab reveal and the
    // app-driven revealRequest below.
    suspend fun revealInTree(target: File) {
        val rootFile = projectPath.toFile().absoluteFile
        val abs = target.absoluteFile
        if (abs.path != rootFile.path &&
            !abs.path.startsWith(rootFile.path + File.separator)) {
            return
        }
        val ancestors = mutableListOf<String>()
        var cur: File? = abs.parentFile
        while (cur != null) {
            ancestors += cur.absolutePath
            if (cur.absolutePath == rootFile.absolutePath) break
            cur = cur.parentFile
        }
        treeState.openNodes(ancestors)
        treeState.selectedKeys = setOf(abs.absolutePath)

        // Walk the same filtered tree the UI renders to find the row index of the target.
        // The LazyTree's internal node list isn't part of its public API, so we rebuild the
        // flattened order from the filesystem + the set of open node IDs.
        val openIds = treeState.openNodes.filterIsInstance<String>().toSet() + rootFile.absolutePath
        val targetIndex = flattenedRowIndexOf(rootFile, abs.absolutePath, openIds)
        if (targetIndex >= 0) {
            // Yield one frame so the LazyList re-measures with the newly-opened ancestors —
            // otherwise visibleItemsInfo still reflects the pre-expansion layout and we'd
            // scroll unnecessarily.
            withFrameNanos { }
            val lazyList = treeState.lazyListState.lazyListState
            val info = lazyList.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == targetIndex }
            val fullyVisible = item != null &&
                item.offset >= info.viewportStartOffset &&
                item.offset + item.size <= info.viewportEndOffset
            if (!fullyVisible) {
                lazyList.animateScrollToItem(targetIndex)
            }
        }
    }

    // When the active tab changes, mirror its file in the tree.
    LaunchedEffect(revealFile, projectPath) {
        revealFile?.let { revealInTree(it) }
    }
    // When the app reveals a freshly-created directory/package. Keyed on revealRequest alone (not
    // refreshKey): the app bumps refreshKey and sets revealRequest in the same batch, so the tree
    // has already rescanned by the time this effect body runs. Keying on refreshKey too would
    // re-yank the selection back here on every later refresh (git poll, file save).
    LaunchedEffect(revealRequest, projectPath) {
        revealRequest?.let { revealInTree(it) }
    }

    fun selectedFile(): File? {
        // Selection keys are the absolute paths we used as element IDs in asFilteredTree.
        val key = treeState.selectedKeys.firstOrNull() as? String ?: return null
        if (key == rootId) return null
        return File(key).takeIf { it.exists() }
    }

    // History falls back to the project root so the button can show whole-repo log
    // when nothing (or the root itself) is selected.
    fun historyTarget(): File = selectedFile() ?: projectPath.toFile()

    // For "open in system": the user is most likely targeting whatever they've picked in the
    // tree, but fall back to the project root so the button always does something useful.
    fun systemOpenTarget(): File = selectedFile() ?: projectPath.toFile()

    val baseTreeStyle = LocalLazyTreeStyle.current
    val treeStyle = remember(baseTreeStyle) {
        LazyTreeStyle(
            colors = baseTreeStyle.colors,
            metrics = baseTreeStyle.metrics,
            icons = LazyTreeIcons(
                chevronCollapsed = ChevronCollapsedIconKey,
                chevronExpanded = ChevronExpandedIconKey,
                chevronSelectedCollapsed = ChevronCollapsedIconKey,
                chevronSelectedExpanded = ChevronExpandedIconKey,
            ),
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isDark = JewelTheme.isDark
            val tint = if (isDark) ProjectIconTintDark else ProjectIconTintLight
            Text("Project")
            Tooltip(tooltip = {
                Text("Open selected with the system default app")
            }) {
                IconButton(onClick = { onOpenInSystem(systemOpenTarget()) }) {
                    Canvas(Modifier.size(16.dp)) { drawExternalOpenIcon(tint) }
                }
            }
            Tooltip(tooltip = { Text("Show git history for the selected file (H)") }) {
                IconButton(onClick = { onHistoryRequest(historyTarget()) }) {
                    Canvas(Modifier.size(16.dp)) { drawHistoryIcon(tint) }
                }
            }
            Tooltip(tooltip = {
                Text(if (blameEnabled) "Hide git blame annotations (B)" else "Annotate open file with git blame (B)")
            }) {
                // Highlight the icon while the annotate column is showing so the toggle reads as on.
                val blameTint = if (blameEnabled) {
                    if (isDark) Color(0xFF6DA9FF) else Color(0xFF2F6FE0)
                } else tint
                IconButton(onClick = onToggleBlame) {
                    Canvas(Modifier.size(16.dp)) { drawBlameIcon(blameTint) }
                }
            }
            // Push headerExtras (the launcher ▶) to the far right
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            headerExtras()
        }
        LazyTree(
            tree = tree,
            treeState = treeState,
            style = treeStyle,
            modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Delete -> selectedFile()?.let { onDeleteRequest(it); true } ?: false
                    Key.H -> { onHistoryRequest(historyTarget()); true }
                    Key.B -> { onToggleBlame(); true }
                    else -> false
                }
            },
            onElementClick = { element ->
                val file = element.data
                if (file.isFile) onFileClick(file)
            },
        ) { element ->
            val file: File = element.data
            val relPath = file.relativePathTo(projectPath)
            val kind = when {
                relPath == null -> null
                file.isFile -> status.byPath[relPath]
                else -> status.changes.firstOrNull { it.path.startsWith("$relPath/") }?.kind
            }
            val color = kind?.let(ChangeColors::forKind)
            // Git status colour wins; otherwise darken the label in light mode (Jewel's default is
            // too pale) and leave the dark theme on its inherited default.
            val labelColor = color ?: if (!JewelTheme.isDark) ProjectTextLight else null
            val iconTint = if (JewelTheme.isDark) ProjectIconTintDark else ProjectIconTintLight
            ContextMenuArea(items = {
                buildList {
                    add(ContextMenuItem("New File…") { onNewFile(file) })
                    add(ContextMenuItem("New Directory…") { onNewDirectory(file) })
                    add(ContextMenuItem("New Package…") { onNewPackage(file) })
                    if (file.isFile) add(ContextMenuItem("Copy File…") { onCopyFile(file) })
                }
            }) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Directories carry a folder glyph between the expand chevron and the name, the
                    // way IntelliJ's project view does. Files have none (the task asked only for
                    // folders), so their label aligns where the chevron's gap leaves them.
                    if (file.isDirectory) {
                        Canvas(Modifier.size(16.dp)) { drawFolderIcon(iconTint) }
                    }
                    if (labelColor != null) Text(file.name, color = labelColor) else Text(file.name)
                }
            }
        }
    }
}

/**
 * Hands the target off to the OS:
 * - Regular files: through java.awt.Desktop, which on Linux means xdg-open and honours the
 *   user's per-MIME defaults (image viewer for PNGs, editor for source files, etc).
 * - Directories on Linux: through the freedesktop FileManager1 DBus interface, which talks
 *   to whichever file manager the desktop has registered (Thunar, Nautilus, Dolphin, …).
 *   We deliberately skip xdg-open here because users can accidentally set inode/directory to
 *   a non-file-manager (the canonical accident: "Open with → some app, always") and then every
 *   folder click in the system tries to launch a music player. FileManager1 ignores that
 *   mapping. macOS/Windows fall back to Desktop.open, which already does the right thing.
 *
 * Done on a daemon thread so the UI doesn't stall while the JVM forks dbus-send / xdg-open
 * (which on a heap-heavy Compose process can take several seconds to copy the page table).
 * Best-effort — failures are swallowed since there's nothing useful to surface from a
 * header icon.
 */
fun openInSystem(target: File) {
    Thread {
        if (target.isDirectory && System.getProperty("os.name").lowercase().contains("linux")) {
            if (openDirectoryViaFileManager1(target)) return@Thread
        }
        runCatching { java.awt.Desktop.getDesktop().open(target) }
    }.apply { name = "nop-open-in-system"; isDaemon = true }.start()
}

private fun openDirectoryViaFileManager1(dir: File): Boolean {
    val uri = dir.toURI().toString()
    val cmd = listOf(
        "dbus-send", "--session", "--dest=org.freedesktop.FileManager1", "--type=method_call",
        "/org/freedesktop/FileManager1", "org.freedesktop.FileManager1.ShowFolders",
        "array:string:$uri", "string:",
    )
    return runCatching {
        // dbus-send exits 0 when the call dispatched successfully, including when DBus
        // auto-starts Thunar/Nautilus/etc. to handle it.
        ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)
}

// A filled manila folder with a tab on the left, drawn in the 16-unit space the other header icons
// use. Sits between the expand chevron and the directory name in the project tree.
private fun DrawScope.drawFolderIcon(tint: Color) {
    drawPath(
        path = ComposePath().apply {
            moveTo(2f, 4.5f)       // top-left of the tab
            lineTo(6f, 4.5f)
            lineTo(7.3f, 6f)       // diagonal down to where the body's top edge begins
            lineTo(14f, 6f)        // body top-right
            lineTo(14f, 12.5f)     // body bottom-right
            lineTo(2f, 12.5f)      // body bottom-left
            close()
        },
        color = tint,
    )
}

// "Open externally" — a rounded box with an arrow leaving the top-right corner. Conveys
// "hand this off to something outside the app".
private fun DrawScope.drawExternalOpenIcon(tint: Color) {
    val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Box: open at the top-right corner where the arrow exits.
    drawPath(
        path = ComposePath().apply {
            moveTo(8.5f, 3f)
            lineTo(3f, 3f)
            lineTo(3f, 13f)
            lineTo(13f, 13f)
            lineTo(13f, 7.5f)
        },
        color = tint,
        style = stroke,
    )
    // Arrow shaft (diagonal) + head.
    drawLine(
        color = tint,
        start = Offset(8f, 8f),
        end = Offset(13.5f, 2.5f),
        strokeWidth = 1.3f,
        cap = StrokeCap.Round,
    )
    drawPath(
        path = ComposePath().apply {
            moveTo(9.5f, 2.5f)
            lineTo(13.5f, 2.5f)
            lineTo(13.5f, 6.5f)
        },
        color = tint,
        style = stroke,
    )
}

// "Annotate" — a left margin rule with three text rows beside it, evoking per-line blame
// annotations pinned to the gutter.
private fun DrawScope.drawBlameIcon(tint: Color) {
    // Gutter rule down the left.
    drawLine(
        color = tint,
        start = Offset(3.5f, 2.5f),
        end = Offset(3.5f, 13.5f),
        strokeWidth = 1.3f,
        cap = StrokeCap.Round,
    )
    // Three annotation rows of varying length beside the rule.
    val rows = listOf(4.5f to 12.5f, 7.5f to 11f, 10.5f to 13f)
    for ((y, x2) in rows) {
        drawLine(
            color = tint,
            start = Offset(6f, y),
            end = Offset(x2, y),
            strokeWidth = 1.3f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawHistoryIcon(tint: Color) {
    val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    drawPath(
        path = ComposePath().apply {
            moveTo(4.05f, 5.35f)
            lineTo(1.85f, 5.35f)
            lineTo(1.85f, 3.15f)
        },
        color = tint,
        style = stroke,
    )
    drawPath(
        path = ComposePath().apply {
            moveTo(2.15f, 8.1f)
            cubicTo(2.15f, 4.87f, 4.77f, 2.25f, 8f, 2.25f)
            cubicTo(11.23f, 2.25f, 13.85f, 4.87f, 13.85f, 8.1f)
            cubicTo(13.85f, 11.33f, 11.23f, 13.95f, 8f, 13.95f)
            cubicTo(5.36f, 13.95f, 3.11f, 12.18f, 2.39f, 9.75f)
        },
        color = tint,
        style = stroke,
    )
    drawLine(
        color = tint,
        start = Offset(8f, 4.75f),
        end = Offset(8f, 8f),
        strokeWidth = 1.3f,
        cap = StrokeCap.Round,
    )
    drawLine(
        color = tint,
        start = Offset(8f, 8f),
        end = Offset(10.25f, 9.35f),
        strokeWidth = 1.3f,
        cap = StrokeCap.Round,
    )
}

