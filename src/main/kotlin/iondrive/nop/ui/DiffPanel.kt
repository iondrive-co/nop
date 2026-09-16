package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import iondrive.nop.diff.DiffComputer
import iondrive.nop.diff.DiffResult
import iondrive.nop.git.ChangeKind
import iondrive.nop.git.FileChange
import iondrive.nop.git.GitRepo
import iondrive.nop.git.GitStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import java.awt.Cursor
import java.io.File

/**
 * What the Diff tab compares the working tree against.
 *
 * Three answers to one question — "changed since when?" — and each is the right one at a different
 * moment. [Session] is the default because the tab exists to show what an agent has been doing, and
 * that work does not stop being interesting when the agent commits it.
 */
enum class DiffBase(val label: String) {
    /** HEAD as it stood when the selected agent session opened. See `AgentSession.baselineSha`. */
    Session("session"),

    /** Plain working tree against HEAD — the same set of changes the Commit tab lists. */
    Uncommitted("uncommitted"),

    /** Everything on this branch: the merge base with its upstream (or the repo's trunk). */
    Branch("branch"),
}

/**
 * The changed files and the diff of whichever one is selected, side by side in one panel.
 *
 * This is nop's answer to the change-review panel the vendor CLIs draw inside their own TUI. Theirs
 * cannot be anything but a strip of the terminal: nop's is the editor's diff — the same renderer,
 * fonts, syntax highlighting and search as every other diff in the window — with the terminal left
 * to be a terminal.
 *
 * The file list carries no +/− counts, deliberately. A count per row costs a full diff per changed
 * file on every refresh, and the git poll refreshes often; the kind letter down the left says what
 * happened to each file, and the one file the user is actually looking at gets a real diff.
 */
@Composable
fun DiffPanel(
    repo: GitRepo?,
    status: GitStatus,
    /** Bumped by the caller when git has moved, so the panel re-reads rather than polling itself. */
    refreshKey: Int,
    base: DiffBase,
    onBaseChange: (DiffBase) -> Unit,
    /**
     * HEAD when the agent on screen started, or null when no session is selected (or it predates
     * nop recording one). Null makes [DiffBase.Session] fall back to HEAD, which is honest: with
     * nothing to date the changes from, "since the session started" and "uncommitted" are the same
     * set.
     */
    sessionBaselineSha: String?,
    splitRatio: Float,
    onSplitRatioChange: (Float) -> Unit,
) {
    val density = LocalDensity.current
    // Panel-local, not persisted: how tall the list wants to be depends on the change set in front
    // of you, and nop has never remembered the commit panel's message height across projects either.
    var listHeight by remember { mutableStateOf(DEFAULT_LIST_HEIGHT) }
    if (repo == null) {
        EmptyDiffPanel("This project isn't in a git repository, so there is nothing to diff.")
        return
    }

    // Resolved off the EDT: a merge base walks the graph, and a big repository makes that visible.
    var branchBase by remember(repo, refreshKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(repo, refreshKey, base) {
        if (base == DiffBase.Branch) {
            branchBase = withContext(Dispatchers.IO) { runCatching { repo.branchBaseSha() }.getOrNull() }
        }
    }

    val baseRev = when (base) {
        DiffBase.Uncommitted -> "HEAD"
        DiffBase.Session -> sessionBaselineSha ?: "HEAD"
        DiffBase.Branch -> branchBase ?: "HEAD"
    }

    var changes by remember(repo) { mutableStateOf<List<FileChange>>(emptyList()) }
    LaunchedEffect(repo, refreshKey, baseRev, status) {
        changes = withContext(Dispatchers.IO) {
            runCatching { repo.changesSince(baseRev, status) }.getOrDefault(status.changes)
        }
    }

    var selectedPath by remember(repo) { mutableStateOf<String?>(null) }
    // Keep a selection pointed at something that still exists, and open on the first file rather
    // than on nothing: a panel whose whole job is to show a diff should be showing one.
    LaunchedEffect(changes) {
        if (changes.none { it.path == selectedPath }) selectedPath = changes.firstOrNull()?.path
    }
    val selected = changes.firstOrNull { it.path == selectedPath }

    Column(modifier = Modifier.fillMaxSize()) {
        DiffPanelHeader(count = changes.size, base = base, onBaseChange = onBaseChange)
        Box(modifier = Modifier.fillMaxWidth().height(listHeight)) {
            ChangedFileList(changes, selectedPath) { selectedPath = it.path }
        }
        ListResizeHandle { dy ->
            val next = with(density) { listHeight + dy.toDp() }
            listHeight = next.coerceIn(MIN_LIST_HEIGHT, MAX_LIST_HEIGHT)
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                selected == null ->
                    EmptyDiffPanel(
                        when (base) {
                            DiffBase.Session -> "Nothing has changed since this session started."
                            DiffBase.Uncommitted -> "No uncommitted changes."
                            DiffBase.Branch -> "Nothing has changed on this branch."
                        },
                    )

                else -> FileDiff(
                    repo = repo,
                    change = selected,
                    baseRev = baseRev,
                    refreshKey = refreshKey,
                    splitRatio = splitRatio,
                    onSplitRatioChange = onSplitRatioChange,
                )
            }
        }
    }
}

/** "N files changed", and the base the count is against. */
@Composable
private fun DiffPanelHeader(count: Int, base: DiffBase, onBaseChange: (DiffBase) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (count == 1) "1 file changed" else "$count files changed",
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BaseSelector(base, onBaseChange)
    }
}

/**
 * The base picker. A plain clickable label rather than a button: it is a caption on the count
 * beside it, and a full button would read as the panel's primary action when the primary action is
 * reading the diff.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BaseSelector(base: DiffBase, onBaseChange: (DiffBase) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Tooltip(tooltip = { Text("What the working tree is compared against") }) {
            Text(
                "base: ${base.label} ▾",
                color = AgentMuted,
                modifier = Modifier.clickable { expanded = !expanded }.padding(horizontal = 4.dp),
            )
        }
        if (expanded) {
            Popup(
                onDismissRequest = { expanded = false },
                offset = IntOffset(0, 24),
                properties = PopupProperties(focusable = true),
            ) {
                val border = if (JewelTheme.isDark) Color(0xFF393B40) else Color(0xFFD3D5DB)
                Column(
                    modifier = Modifier
                        .width(180.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(JewelTheme.globalColors.panelBackground)
                        .border(1.dp, border, RoundedCornerShape(6.dp))
                        .padding(vertical = 4.dp),
                ) {
                    DiffBase.entries.forEach { option ->
                        Text(
                            option.label,
                            color = if (option == base) ChangeColors.MODIFIED else Color.Unspecified,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expanded = false
                                    onBaseChange(option)
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The changed files, grouped and labelled the way the commit panel's columns are. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChangedFileList(
    changes: List<FileChange>,
    selectedPath: String?,
    onSelect: (FileChange) -> Unit,
) {
    val listState = rememberLazyListState()
    val selectedBg = if (JewelTheme.isDark) Color(0x332F6FEB) else Color(0x1A2F6FEB)
    ScrollableColumn(listState = listState, modifier = Modifier.fillMaxSize()) {
        items(changes, key = { it.path }) { change ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (change.path == selectedPath) selectedBg else Color.Transparent)
                    .clickable { onSelect(change) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(ChangeColors.prefixFor(change.kind), color = ChangeColors.forKind(change.kind))
                Tooltip(tooltip = { Text(change.path) }) {
                    Text(
                        change.path.substringAfterLast('/'),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    change.path.substringBeforeLast('/', ""),
                    color = AgentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * One file at [baseRev] against what is on disk now.
 *
 * Both sides are read by path rather than by change kind, and a side that isn't there reads as
 * empty. That is what lets one loader serve all three bases: a file added in a commit since the
 * baseline is absent from the base tree and present on disk, which is the same shape as a file the
 * agent has only just created and never committed.
 */
@Composable
private fun FileDiff(
    repo: GitRepo,
    change: FileChange,
    baseRev: String,
    refreshKey: Int,
    splitRatio: Float,
    onSplitRatioChange: (Float) -> Unit,
) {
    var result by remember(change.path, baseRev) { mutableStateOf<DiffResult?>(null) }
    var error by remember(change.path, baseRev) { mutableStateOf<String?>(null) }

    LaunchedEffect(change.path, change.kind, baseRev, refreshKey) {
        try {
            val (oldText, newText) = withContext(Dispatchers.IO) {
                val old = if (change.kind == ChangeKind.UNTRACKED) "" else repo.readContentAt(baseRev, change.path) ?: ""
                val new = repo.readWorkingTreeContent(change.path) ?: ""
                old to new
            }
            result = withContext(Dispatchers.Default) { DiffComputer.compute(oldText, newText) }
            error = null
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName
        }
    }

    val tokenize = remember(change.path) {
        tokenizerForExtension(File(change.path).extension)
    }
    CompositionLocalProvider(LocalDiffTokenizer provides tokenize) {
        when {
            error != null -> EmptyDiffPanel("Could not load diff: $error")
            result == null -> EmptyDiffPanel("Loading diff…")
            else -> ReadOnlyDiffList(
                result = result!!,
                splitRatio = splitRatio,
                onSplitRatioChange = onSplitRatioChange,
                onTopLine = {},
                searchKey = change.path to baseRev,
                findTrigger = 0,
            )
        }
    }
}

@Composable
private fun EmptyDiffPanel(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = AgentMuted)
    }
}

/**
 * The grab strip between the file list and the diff, in the same 4dp-with-a-resize-cursor form the
 * commit panel's message box uses — so every draggable boundary in nop's panels reads the same.
 */
@Composable
private fun ListResizeHandle(onDelta: (Float) -> Unit) {
    val color = if (JewelTheme.isDark) Color(0xFF2B2D30) else Color(0xFFD3D5DB)
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(color)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .draggable(
                state = rememberDraggableState(onDelta = onDelta),
                orientation = Orientation.Vertical,
                startDragImmediately = true,
            ),
    )
}

private val MIN_LIST_HEIGHT = 48.dp
private val MAX_LIST_HEIGHT = 480.dp
private val DEFAULT_LIST_HEIGHT = 140.dp
