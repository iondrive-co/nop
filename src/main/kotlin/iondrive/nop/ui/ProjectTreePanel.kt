package iondrive.nop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

internal val ProjectIconTintDark = Color(0xFF9DA3AB)
internal val ProjectIconTintLight = Color(0xFF4A5360)

// Leaf (file) rows have no expand chevron and no folder glyph, so without help their label starts
// where a directory's chevron would — i.e. to the *left* of a sibling directory's name, which reads
// as a file being *out*dented from the folder it lives in. Reserving the chevron + folder-icon
// columns pushes every file name into the same column as directory names, so a file sits one level
// in from (visibly nested under) its parent directory, the way IntelliJ's project view lays it out.
private val FileRowIndent = 28.dp

// Background wash for the directory row currently under a drag — same accent in both themes so it
// reads clearly against either panel background.
private val DropTargetHighlight = Color(0x403574F0)

// Tree row label colour. The dark theme keeps Jewel's default (null → inherit); the light theme's
// default ran too pale against the near-white panel, so name a near-black that matches the editor.
internal val ProjectTextLight = Color(0xFF000000)

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

sealed class TreeEntry {
    abstract val id: String

    data class Node(
        val file: File,
        override val id: String = file.absolutePath,
    ) : TreeEntry()

    data class Ellipsis(
        val parentDir: File,
        val hiddenItems: List<File>,
        val rangeId: String,
        val isDirectory: Boolean,
        override val id: String = rangeId,
    ) : TreeEntry() {
        val hiddenFiles: List<File> get() = hiddenItems
    }

    data class Collapse(
        val parentDir: File,
        val rangeId: String,
        val count: Int,
        val isDirectory: Boolean,
        override val id: String = "collapse:$rangeId",
    ) : TreeEntry()
}

/**
 * Groups children in [dir], compressing long runs of non-open subdirectories and files
 * into expandable [TreeEntry.Ellipsis] entries.
 */
internal fun computeDirectoryEntries(
    dir: File,
    files: List<File>,
    openFilePaths: Set<String>,
    expandedEllipsisKeys: Set<String>,
    openDirectoryIds: Set<String> = emptySet(),
    minItemsForEllipsis: Int = 6,
    minRunForEllipsis: Int = 3,
): List<TreeEntry> {
    val (dirs, leafFiles) = files.partition { it.isDirectory }
    val sortedDirs = dirs.sortedBy { it.name.lowercase() }
    val sortedFiles = leafFiles.sortedBy { it.name.lowercase() }

    val entries = mutableListOf<TreeEntry>()

    // 1. Process Directories
    if (sortedDirs.size <= minItemsForEllipsis) {
        for (d in sortedDirs) {
            entries.add(TreeEntry.Node(d))
        }
    } else {
        // A directory is forced-visible if it contains an open file, is expanded, or is an open target
        val openDirIndices = sortedDirs.mapIndexedNotNull { idx, d ->
            val dirPrefix = d.absolutePath + File.separator
            val containsOpenFile = openFilePaths.any { it.startsWith(dirPrefix) || it == d.absolutePath }
            val isExpanded = d.absolutePath in openDirectoryIds || openDirectoryIds.any { it.startsWith(dirPrefix) }
            if (containsOpenFile || isExpanded) idx else null
        }

        val visibleDirIndices = mutableSetOf<Int>()
        if (openDirIndices.isEmpty()) {
            for (i in 0 until minOf(3, sortedDirs.size)) {
                visibleDirIndices.add(i)
            }
        } else {
            for (idx in openDirIndices) {
                if (idx - 1 >= 0) visibleDirIndices.add(idx - 1)
                visibleDirIndices.add(idx)
                if (idx + 1 < sortedDirs.size) visibleDirIndices.add(idx + 1)
            }
        }

        var idx = 0
        while (idx < sortedDirs.size) {
            if (idx in visibleDirIndices) {
                entries.add(TreeEntry.Node(sortedDirs[idx]))
                idx++
            } else {
                val hiddenRun = mutableListOf<File>()
                while (idx < sortedDirs.size && idx !in visibleDirIndices) {
                    hiddenRun.add(sortedDirs[idx])
                    idx++
                }
                if (hiddenRun.size < minRunForEllipsis) {
                    for (d in hiddenRun) {
                        entries.add(TreeEntry.Node(d))
                    }
                } else {
                    val rangeId = "ellipsis:dirs:${dir.absolutePath}:${hiddenRun.first().name}..${hiddenRun.last().name}"
                    if (rangeId in expandedEllipsisKeys) {
                        for (d in hiddenRun) {
                            entries.add(TreeEntry.Node(d))
                        }
                        entries.add(TreeEntry.Collapse(dir, rangeId, hiddenRun.size, isDirectory = true))
                    } else {
                        entries.add(TreeEntry.Ellipsis(dir, hiddenRun, rangeId, isDirectory = true))
                    }
                }
            }
        }
    }

    // 2. Process Files
    if (sortedFiles.size <= minItemsForEllipsis) {
        for (f in sortedFiles) {
            entries.add(TreeEntry.Node(f))
        }
        return entries
    }

    val openIndices = sortedFiles.mapIndexedNotNull { idx, f ->
        if (f.absolutePath in openFilePaths) idx else null
    }

    val visibleIndices = mutableSetOf<Int>()
    if (openIndices.isEmpty()) {
        for (i in 0 until minOf(3, sortedFiles.size)) {
            visibleIndices.add(i)
        }
    } else {
        for (idx in openIndices) {
            if (idx - 1 >= 0) visibleIndices.add(idx - 1)
            visibleIndices.add(idx)
            if (idx + 1 < sortedFiles.size) visibleIndices.add(idx + 1)
        }
    }

    var idx = 0
    while (idx < sortedFiles.size) {
        if (idx in visibleIndices) {
            entries.add(TreeEntry.Node(sortedFiles[idx]))
            idx++
        } else {
            val hiddenRun = mutableListOf<File>()
            while (idx < sortedFiles.size && idx !in visibleIndices) {
                hiddenRun.add(sortedFiles[idx])
                idx++
            }
            if (hiddenRun.size < minRunForEllipsis) {
                for (f in hiddenRun) {
                    entries.add(TreeEntry.Node(f))
                }
            } else {
                val rangeId = "ellipsis:files:${dir.absolutePath}:${hiddenRun.first().name}..${hiddenRun.last().name}"
                if (rangeId in expandedEllipsisKeys) {
                    for (f in hiddenRun) {
                        entries.add(TreeEntry.Node(f))
                    }
                    entries.add(TreeEntry.Collapse(dir, rangeId, hiddenRun.size, isDirectory = false))
                } else {
                    entries.add(TreeEntry.Ellipsis(dir, hiddenRun, rangeId, isDirectory = false))
                }
            }
        }
    }

    return entries
}

private fun Path.asFilteredTree(
    openFilePaths: Set<String>,
    expandedEllipsisKeys: Set<String>,
    openDirectoryIds: Set<String> = emptySet(),
): Tree<TreeEntry> = buildTree {
    val root = toFile()
    val rootEntry = TreeEntry.Node(root)
    addNode(rootEntry, id = root.absolutePath) {
        addChildren(root, openFilePaths, expandedEllipsisKeys, openDirectoryIds)
    }
}

private fun ChildrenGeneratorScope<TreeEntry>.addChildren(
    dir: File,
    openFilePaths: Set<String>,
    expandedEllipsisKeys: Set<String>,
    openDirectoryIds: Set<String>,
) {
    val entries = computeDirectoryEntries(
        dir = dir,
        files = visibleChildren(dir),
        openFilePaths = openFilePaths,
        expandedEllipsisKeys = expandedEllipsisKeys,
        openDirectoryIds = openDirectoryIds,
    )
    for (entry in entries) {
        when (entry) {
            is TreeEntry.Node -> {
                val file = entry.file
                if (file.isDirectory) {
                    addNode(entry, id = file.absolutePath) {
                        addChildren(file, openFilePaths, expandedEllipsisKeys, openDirectoryIds)
                    }
                } else {
                    addLeaf(entry, id = file.absolutePath)
                }
            }
            is TreeEntry.Ellipsis -> {
                addLeaf(entry, id = entry.rangeId)
            }
            is TreeEntry.Collapse -> {
                addLeaf(entry, id = entry.id)
            }
        }
    }
}

internal fun visibleChildren(dir: File): List<File> = (dir.listFiles() ?: emptyArray())
    .filter { it.name !in IGNORED_DIR_NAMES }
    .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

/**
 * Returns the 0-based row index of [targetPath] in the same DFS-of-expanded-nodes ordering the
 * LazyTree uses, given [openIds] (absolute paths of directories that are expanded). Returns -1
 * when the target isn't part of the visible tree, e.g. because an ancestor isn't open.
 */
internal fun flattenedRowIndexOf(
    rootFile: File,
    targetPath: String,
    openIds: Set<String>,
    openFilePaths: Set<String> = emptySet(),
    expandedEllipsisKeys: Set<String> = emptySet(),
): Int {
    var counter = 0
    fun walk(dir: File): Int {
        if (dir.absolutePath == targetPath) return counter
        counter++
        if (!dir.isDirectory) return -1
        if (dir.absolutePath != rootFile.absolutePath && dir.absolutePath !in openIds) return -1
        val entries = computeDirectoryEntries(
            dir = dir,
            files = visibleChildren(dir),
            openFilePaths = openFilePaths,
            expandedEllipsisKeys = expandedEllipsisKeys,
            openDirectoryIds = openIds,
        )
        for (entry in entries) {
            when (entry) {
                is TreeEntry.Node -> {
                    val file = entry.file
                    if (file.isDirectory) {
                        val found = walk(file)
                        if (found >= 0) return found
                    } else {
                        if (file.absolutePath == targetPath) return counter
                        counter++
                    }
                }
                is TreeEntry.Ellipsis -> {
                    if (entry.hiddenItems.any { it.absolutePath == targetPath }) return counter
                    counter++
                }
                is TreeEntry.Collapse -> {
                    counter++
                }
            }
        }
        return -1
    }
    return walk(rootFile)
}

/**
 * The absolute path of the directory row whose vertical band (root-relative Y range) contains
 * [pointerY], or null when the pointer isn't over any tracked directory row. Pulled out as a pure
 * function so the drag-to-move hit test is unit-testable without spinning up Compose.
 */
internal fun directoryPathAtY(rowRanges: Map<String, ClosedFloatingPointRange<Float>>, pointerY: Float): String? =
    rowRanges.entries.firstOrNull { pointerY in it.value }?.key

/**
 * The multi-selection as files: every selection key that is an existing path other than the project
 * root. The LazyTree stores its selection (built up with Ctrl/Shift click) as the set of element
 * IDs, which for this tree are absolute paths — so this just filters and rehydrates them. Pulled out
 * as a pure function so the selection-to-files mapping is unit-testable without Compose.
 */
internal fun selectedFilesOf(selectedKeys: Set<Any?>, rootId: String): List<File> =
    selectedKeys.asSequence()
        .filterIsInstance<String>()
        .filter { it != rootId }
        .map(::File)
        .filter { it.exists() }
        .toList()

/**
 * Which rows a context-menu action (delete, copy) triggered from [clicked] should act on: the whole
 * [selection] when the clicked row is part of it, otherwise just the clicked row. Mirrors the
 * file-manager convention where right-clicking a row outside the current selection acts only on
 * that row.
 */
internal fun menuTargetsFor(clicked: File, selection: List<File>): List<File> =
    if (selection.any { it.absolutePath == clicked.absolutePath }) selection else listOf(clicked)

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
    openFiles: List<File> = emptyList(),
    activeFile: File? = null,
    dirtyFiles: Set<File> = emptySet(),
    revealFile: File? = null,
    revealRequest: File? = null,
    onFileClick: (File) -> Unit,
    onCloseFile: (File) -> Unit = {},
    onCloseOtherFiles: (File) -> Unit = {},
    onCloseAllFiles: () -> Unit = {},
    onDeleteRequest: (List<File>) -> Unit = {},
    onHistoryRequest: (File) -> Unit = {},
    onCompareWithRevision: (File) -> Unit = {},
    gitEnabled: Boolean = false,
    blameEnabled: Boolean = false,
    onToggleBlame: () -> Unit = {},
    wrapLines: Boolean = false,
    onToggleWrap: () -> Unit = {},
    onOpenInSystem: (File) -> Unit = ::openInSystem,
    onNewFile: (File) -> Unit = {},
    onNewDirectory: (File) -> Unit = {},
    onNewPackage: (File) -> Unit = {},
    onCopyFile: (File) -> Unit = {},
    onRenameRequest: (File) -> Unit = {},
    onClipboardCopy: (List<File>) -> Unit = {},
    onPasteRequest: (targetDir: File) -> Unit = {},
    canPaste: () -> Boolean = { false },
    onMoveRequest: (source: File, targetDir: File) -> Unit = { _, _ -> },
    // The word at the head of the panel. A slot rather than a plain label because it is also the way
    // to another project — see [ProjectMenuLabel] — which is a question for whoever owns the window's
    // tabs, not for a tree showing one project's files.
    projectLabel: @Composable () -> Unit = { Text("Project") },
    headerExtras: @Composable () -> Unit = {},
) {
    val rootId = remember(projectPath) { projectPath.toFile().absolutePath }

    var draggedFile by remember(projectPath) { mutableStateOf<File?>(null) }
    var dropTargetPath by remember(projectPath) { mutableStateOf<String?>(null) }
    val dirRowRanges = remember(projectPath) { mutableStateMapOf<String, ClosedFloatingPointRange<Float>>() }

    var pressCarriedSelectModifier by remember(projectPath) { mutableStateOf(false) }
    var selectionBeforeSecondaryPress by remember(projectPath) { mutableStateOf<List<File>>(emptyList()) }

    val effectiveOpenFiles = remember(openFiles, revealFile) {
        if (openFiles.isNotEmpty()) openFiles else listOfNotNull(revealFile)
    }
    val effectiveActiveFile = activeFile ?: revealFile
    val openFilePaths = remember(effectiveOpenFiles) { effectiveOpenFiles.map { it.absolutePath }.toSet() }

    // User-expanded directory/file ellipsis blocks
    var expandedEllipsisKeys by remember(projectPath) { mutableStateOf<Set<String>>(emptySet()) }

    val treeState = rememberTreeState()
    val openDirectoryIds = treeState.openNodes.filterIsInstance<String>().toSet()

    val tree = remember(projectPath, refreshKey, openFilePaths, expandedEllipsisKeys, openDirectoryIds) {
        projectPath.asFilteredTree(openFilePaths, expandedEllipsisKeys, openDirectoryIds)
    }

    // Track which files have had their ancestors opened so we don't re-expand folders the user collapsed
    val revealedFilePaths = remember(projectPath) { mutableSetOf<String>() }

    LaunchedEffect(rootId) {
        treeState.openNodes(listOf(rootId))
    }

    // Expand ancestor folders for open files on initial load or when a newly opened file appears
    LaunchedEffect(effectiveOpenFiles, projectPath) {
        val rootFile = projectPath.toFile().absoluteFile
        val toOpen = mutableListOf<String>()
        for (f in effectiveOpenFiles) {
            val abs = f.absoluteFile
            if (abs.path !in revealedFilePaths) {
                revealedFilePaths.add(abs.path)
                var cur: File? = abs.parentFile
                while (cur != null) {
                    toOpen += cur.absolutePath
                    if (cur.absolutePath == rootFile.absolutePath) break
                    cur = cur.parentFile
                }
            }
        }
        if (toOpen.isNotEmpty()) {
            treeState.openNodes(toOpen)
        }
    }

    // Expand ancestors and select [target] so the tree mirrors it, scrolling it into view when
    // it's offscreen. Only triggered when active file changes.
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

        val openIds = treeState.openNodes.filterIsInstance<String>().toSet() + rootFile.absolutePath
        val targetIndex = flattenedRowIndexOf(rootFile, abs.absolutePath, openIds, openFilePaths, expandedEllipsisKeys)
        if (targetIndex >= 0) {
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

    LaunchedEffect(revealFile, projectPath) {
        revealFile?.let { revealInTree(it) }
    }

    LaunchedEffect(revealRequest, projectPath) {
        revealRequest?.let { revealInTree(it) }
    }

    fun selectedFile(): File? {
        val key = treeState.selectedKeys.firstOrNull() as? String ?: return null
        if (key == rootId) return null
        return File(key).takeIf { it.exists() }
    }

    fun selectedFiles(): List<File> = selectedFilesOf(treeState.selectedKeys, rootId)

    fun pasteTarget(): File = selectedFile()?.let(FileOperations::parentDirFor) ?: projectPath.toFile()
    fun historyTarget(): File = selectedFile() ?: projectPath.toFile()
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
            projectLabel()
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
                val blameTint = if (blameEnabled) {
                    if (isDark) Color(0xFF6DA9FF) else Color(0xFF2F6FE0)
                } else tint
                IconButton(onClick = onToggleBlame) {
                    Canvas(Modifier.size(16.dp)) { drawBlameIcon(blameTint) }
                }
            }
            Tooltip(tooltip = {
                Text(if (wrapLines) "Unwrap long lines" else "Wrap long lines")
            }) {
                val wrapTint = if (wrapLines) {
                    if (isDark) Color(0xFF6DA9FF) else Color(0xFF2F6FE0)
                } else tint
                IconButton(onClick = onToggleWrap) {
                    Canvas(Modifier.size(16.dp)) { drawWrapIcon(wrapTint) }
                }
            }
            Spacer(Modifier.weight(1f))
            headerExtras()
        }
        LazyTree(
            tree = tree,
            treeState = treeState,
            style = treeStyle,
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type != PointerEventType.Press) continue
                            val mods = event.keyboardModifiers
                            pressCarriedSelectModifier =
                                mods.isCtrlPressed || mods.isMetaPressed || mods.isShiftPressed
                            if (event.buttons.isSecondaryPressed) {
                                selectionBeforeSecondaryPress = selectedFilesOf(treeState.selectedKeys, rootId)
                            }
                        }
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val copyModifier = event.isCtrlPressed || event.isMetaPressed
                    when (event.key) {
                        Key.Delete -> selectedFiles().takeIf { it.isNotEmpty() }
                            ?.let { onDeleteRequest(it); true } ?: false
                        Key.C -> if (copyModifier) {
                            selectedFiles().takeIf { it.isNotEmpty() }?.let { onClipboardCopy(it) }
                            true
                        } else false
                        Key.V -> if (copyModifier) { onPasteRequest(pasteTarget()); true } else false
                        Key.F2 -> selectedFile()?.let { onRenameRequest(it); true } ?: false
                        Key.H -> { onHistoryRequest(historyTarget()); true }
                        Key.B -> { onToggleBlame(); true }
                        else -> false
                    }
                },
            onElementClick = { element ->
                when (val entry = element.data) {
                    is TreeEntry.Node -> {
                        val file = entry.file
                        if (file.isFile && !pressCarriedSelectModifier) onFileClick(file)
                    }
                    is TreeEntry.Ellipsis -> {
                        expandedEllipsisKeys = expandedEllipsisKeys + entry.rangeId
                    }
                    is TreeEntry.Collapse -> {
                        expandedEllipsisKeys = expandedEllipsisKeys - entry.rangeId
                    }
                }
            },
        ) { element ->
            val isDark = JewelTheme.isDark
            val iconTint = if (isDark) ProjectIconTintDark else ProjectIconTintLight

            when (val entry = element.data) {
                is TreeEntry.Ellipsis -> {
                    val interactionSource = remember(entry.rangeId) { MutableInteractionSource() }
                    val isHovered by interactionSource.collectIsHoveredAsState()
                    val count = entry.hiddenItems.size
                    val noun = if (entry.isDirectory) {
                        if (count == 1) "folder" else "folders"
                    } else {
                        if (count == 1) "file" else "files"
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isHovered) (if (isDark) Color(0x20FFFFFF) else Color(0x10000000)) else Color.Transparent,
                            )
                            .hoverable(interactionSource)
                            .clickable { expandedEllipsisKeys = expandedEllipsisKeys + entry.rangeId },
                    ) {
                        if (entry.isDirectory) {
                            Canvas(Modifier.size(16.dp)) { drawFolderIcon(iconTint.copy(alpha = 0.7f)) }
                        } else {
                            Spacer(Modifier.width(FileRowIndent))
                        }
                        Text(
                            text = "··· $count more $noun ···",
                            fontSize = 11.sp,
                            fontFamily = NopFonts.Mono,
                            color = if (isDark) Color(0xFF8B8F99) else Color(0xFF6C707E),
                        )
                        if (isHovered) {
                            Text(
                                text = "show",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDark) Color(0xFF6DA9FF) else Color(0xFF2F6FE0),
                            )
                        }
                    }
                }

                is TreeEntry.Collapse -> {
                    val interactionSource = remember(entry.rangeId) { MutableInteractionSource() }
                    val isHovered by interactionSource.collectIsHoveredAsState()
                    val noun = if (entry.isDirectory) {
                        if (entry.count == 1) "folder" else "folders"
                    } else {
                        if (entry.count == 1) "file" else "files"
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .padding(end = 8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isHovered) (if (isDark) Color(0x20FFFFFF) else Color(0x10000000)) else Color.Transparent,
                            )
                            .hoverable(interactionSource)
                            .clickable { expandedEllipsisKeys = expandedEllipsisKeys - entry.rangeId },
                    ) {
                        if (entry.isDirectory) {
                            Spacer(Modifier.width(16.dp))
                        } else {
                            Spacer(Modifier.width(FileRowIndent))
                        }
                        Text(
                            text = "▴ collapse ${entry.count} $noun",
                            fontSize = 10.sp,
                            fontFamily = NopFonts.Mono,
                            color = if (isDark) Color(0xFF6DA9FF) else Color(0xFF2F6FE0),
                        )
                    }
                }

                is TreeEntry.Node -> {
                    val file: File = entry.file
                    val isOpen = file.isFile && file.absolutePath in openFilePaths
                    val isActive = file.isFile && file.absolutePath == effectiveActiveFile?.absolutePath
                    val isDirty = file.isFile && file in dirtyFiles

                    val relPath = file.relativePathTo(projectPath)
                    val kind = when {
                        relPath == null -> null
                        file.isFile -> status.byPath[relPath]
                        else -> status.changes.firstOrNull { it.path.startsWith("$relPath/") }?.kind
                    }
                    val color = kind?.let(ChangeColors::forKind)

                    // High contrast text color: Active is bright white (dark) / deep blue (light)
                    val labelColor = color ?: when {
                        isActive -> if (isDark) Color(0xFFFFFFFF) else Color(0xFF0F3E85)
                        isOpen -> if (isDark) Color(0xFFDFE1E5) else Color(0xFF1F2329)
                        else -> if (!isDark) ProjectTextLight else null
                    }

                    ContextMenuArea(items = {
                        buildList {
                            if (isOpen) {
                                add(ContextMenuItem("Close") { onCloseFile(file) })
                                if (effectiveOpenFiles.size > 1) {
                                    add(ContextMenuItem("Close Others") { onCloseOtherFiles(file) })
                                    add(ContextMenuItem("Close All") { onCloseAllFiles() })
                                }
                            }
                            add(ContextMenuItem("New File…") { onNewFile(file) })
                            add(ContextMenuItem("New Directory…") { onNewDirectory(file) })
                            add(ContextMenuItem("New Package…") { onNewPackage(file) })
                            if (file.isFile) add(ContextMenuItem("Copy File…") { onCopyFile(file) })
                            add(ContextMenuItem("Copy") { onClipboardCopy(menuTargetsFor(file, selectionBeforeSecondaryPress)) })
                            if (canPaste()) {
                                add(ContextMenuItem("Paste") { onPasteRequest(FileOperations.parentDirFor(file)) })
                            }
                            if (file.absolutePath != rootId) {
                                add(ContextMenuItem("Rename…") { onRenameRequest(file) })
                            }
                            if (gitEnabled) {
                                add(ContextMenuItem("Show History") { onHistoryRequest(file) })
                                if (file.isFile) {
                                    add(ContextMenuItem("Compare with Revision…") { onCompareWithRevision(file) })
                                }
                            }
                            if (file.absolutePath != rootId) {
                                val targets = if (pressCarriedSelectModifier) {
                                    menuTargetsFor(file, selectionBeforeSecondaryPress)
                                } else {
                                    listOf(file)
                                }
                                if (targets.size > 1) {
                                    treeState.selectedKeys = targets.mapTo(mutableSetOf()) { it.absolutePath }
                                }
                                val label = if (targets.size > 1) "Delete ${targets.size} Items" else "Delete"
                                add(ContextMenuItem(label) { onDeleteRequest(targets) })
                            }
                        }
                    }) {
                        val rowCoords = remember(file.absolutePath) { mutableStateOf<LayoutCoordinates?>(null) }
                        val isDropTarget = file.isDirectory && dropTargetPath == file.absolutePath &&
                            draggedFile != null && draggedFile != file
                        val interactionSource = remember(file.absolutePath) { MutableInteractionSource() }
                        val isHovered by interactionSource.collectIsHoveredAsState()

                        // Highlight styling: Active file has vivid BLUE background; Open file has neutral GRAY background + border
                        val rowBackground = when {
                            isDropTarget -> DropTargetHighlight
                            isActive -> if (isDark) Color(0xFF2B5282) else Color(0xFFCEE0FD)
                            isOpen -> if (isDark) Color(0xFF26282D) else Color(0xFFEEF0F4)
                            else -> Color.Transparent
                        }

                        val rowBorder = when {
                            isOpen && !isActive -> BorderStroke(1.dp, if (isDark) Color(0x28FFFFFF) else Color(0x18000000))
                            else -> null
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .then(if (rowBorder != null) Modifier.border(rowBorder, RoundedCornerShape(4.dp)) else Modifier)
                                .hoverable(interactionSource)
                                .onGloballyPositioned { coords ->
                                    rowCoords.value = coords
                                    if (file.isDirectory) {
                                        val bounds = coords.boundsInRoot()
                                        dirRowRanges[file.absolutePath] = bounds.top..bounds.bottom
                                    } else {
                                        dirRowRanges.remove(file.absolutePath)
                                    }
                                }
                                .then(
                                    if (file.absolutePath == rootId) {
                                        Modifier
                                    } else {
                                        Modifier.pointerInput(file.absolutePath) {
                                            detectDragGestures(
                                                onDragStart = { draggedFile = file },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    val coords = rowCoords.value ?: return@detectDragGestures
                                                    val rootY = coords.localToRoot(change.position).y
                                                    dropTargetPath = directoryPathAtY(dirRowRanges, rootY)
                                                        ?.takeIf { it != file.absolutePath }
                                                },
                                                onDragEnd = {
                                                    val target = dropTargetPath
                                                    val source = draggedFile
                                                    draggedFile = null
                                                    dropTargetPath = null
                                                    if (target != null && source != null) onMoveRequest(source, File(target))
                                                },
                                                onDragCancel = {
                                                    draggedFile = null
                                                    dropTargetPath = null
                                                },
                                            )
                                        }
                                    },
                                )
                                .then(
                                    if (isOpen) {
                                        Modifier.pointerInput(file.absolutePath) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    if (event.type == PointerEventType.Press && event.buttons.isTertiaryPressed) {
                                                        onCloseFile(file)
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Modifier
                                    }
                                )
                                .alpha(if (draggedFile == file) 0.5f else 1f)
                                .background(rowBackground),
                        ) {
                            if (file.isDirectory) {
                                Canvas(Modifier.size(16.dp)) { drawFolderIcon(iconTint) }
                            } else {
                                // Active file gets prominent glowing vertical accent bar
                                if (isActive) {
                                    Box(
                                        modifier = Modifier
                                            .width(3.5.dp)
                                            .height(16.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(if (isDark) Color(0xFF6DA9FF) else Color(0xFF2F6FE0)),
                                    )
                                    Spacer(Modifier.width(FileRowIndent - 3.5.dp))
                                } else {
                                    Spacer(Modifier.width(FileRowIndent))
                                }
                            }

                            if (labelColor != null) {
                                Text(
                                    file.name,
                                    color = labelColor,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            } else {
                                Text(
                                    file.name,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }

                            if (isDirty) {
                                Spacer(Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(ChangeColors.MODIFIED),
                                )
                            }

                            if (isOpen) {
                                Spacer(Modifier.weight(1f))
                                Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                                    if (isHovered || isActive) {
                                        CloseButton(isDark = isDark, onClose = { onCloseFile(file) })
                                    }
                                }
                            }
                        }
                    }
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
