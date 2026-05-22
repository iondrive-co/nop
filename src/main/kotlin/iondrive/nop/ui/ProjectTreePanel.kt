package iondrive.nop.ui

import androidx.compose.foundation.Canvas
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
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.foundation.lazy.tree.ChildrenGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.lazy.tree.rememberTreeState
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.styling.LazyTreeIcons
import org.jetbrains.jewel.ui.component.styling.LazyTreeStyle
import org.jetbrains.jewel.ui.component.styling.LocalLazyTreeStyle
import org.jetbrains.jewel.ui.icon.PathIconKey
import java.io.File
import java.nio.file.Path

private val ProjectIconTintDark = Color(0xFFAFB8C4)
private val ProjectIconTintLight = Color(0xFF4A5360)

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
        .filter { it.name !in IGNORED_DIR_NAMES && !it.isHidden }
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        .forEach { file ->
            if (file.isFile) {
                addLeaf(file, id = file.absolutePath)
            } else {
                addNode(file, id = file.absolutePath) { addChildren(file) }
            }
        }
}

private fun File.relativePathTo(repoRoot: Path): String? = runCatching {
    repoRoot.toAbsolutePath().normalize().relativize(this.toPath().toAbsolutePath().normalize())
        .toString().replace(File.separatorChar, '/')
}.getOrNull()?.takeIf { !it.startsWith("..") }

@OptIn(ExperimentalJewelApi::class)
@Composable
fun ProjectTreePanel(
    projectPath: Path,
    status: GitStatus,
    refreshKey: Int = 0,
    onFileClick: (File) -> Unit,
    onChangeProject: () -> Unit = {},
    onToggleTheme: () -> Unit = {},
    onDeleteRequest: (File) -> Unit = {},
    onHistoryRequest: (File) -> Unit = {},
    headerExtras: @Composable () -> Unit = {},
) {
    val tree = remember(projectPath, refreshKey) { projectPath.asFilteredTree() }
    val treeState = rememberTreeState()
    val rootId = remember(projectPath) { projectPath.toFile().absolutePath }

    LaunchedEffect(rootId) {
        treeState.openNodes(listOf(rootId))
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
            IconButton(onClick = onChangeProject) {
                Canvas(Modifier.size(16.dp)) { drawOpenFolderIcon(tint) }
            }
            IconButton(onClick = { onHistoryRequest(historyTarget()) }) {
                Canvas(Modifier.size(16.dp)) { drawHistoryIcon(tint) }
            }
            IconButton(onClick = onToggleTheme) {
                Canvas(Modifier.size(16.dp)) {
                    if (isDark) drawSunIcon(tint) else drawMoonIcon(tint)
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
            if (color != null) Text(file.name, color = color) else Text(file.name)
        }
    }
}

private fun DrawScope.drawOpenFolderIcon(tint: Color) {
    val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    drawPath(
        path = ComposePath().apply {
            moveTo(1.5f, 4.5f)
            lineTo(5.95f, 4.5f)
            lineTo(7.08f, 5.62f)
            lineTo(13f, 5.62f)
            lineTo(13.5f, 6.12f)
            lineTo(13.5f, 6.9f)
            lineTo(2.5f, 6.9f)
            close()
        },
        color = tint,
        style = stroke,
    )

    drawPath(
        path = ComposePath().apply {
            moveTo(1.5f, 6.5f)
            lineTo(14.5f, 6.5f)
            lineTo(13.45f, 11.74f)
            cubicTo(13.31f, 12.44f, 12.7f, 12.95f, 11.98f, 12.95f)
            lineTo(3.38f, 12.95f)
            cubicTo(2.66f, 12.95f, 2.04f, 12.44f, 1.91f, 11.73f)
            close()
        },
        color = tint,
        style = stroke,
    )
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

// Sun: shown when the app is dark (clicking switches to light).
private fun DrawScope.drawSunIcon(tint: Color) {
    val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawCircle(color = tint, radius = 2.6f, center = Offset(8f, 8f), style = stroke)
    // Eight rays at the cardinal/diagonal directions.
    val rays = listOf(
        0f to -5.4f, 0f to 5.4f, -5.4f to 0f, 5.4f to 0f,
        -3.8f to -3.8f, 3.8f to -3.8f, -3.8f to 3.8f, 3.8f to 3.8f,
    )
    for ((dx, dy) in rays) {
        val inner = 1.4f / kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val outer = 0.6f
        drawLine(
            color = tint,
            start = Offset(8f + dx * (1f - outer * 0.18f), 8f + dy * (1f - outer * 0.18f)),
            end = Offset(8f + dx * (1f - inner * 0.6f), 8f + dy * (1f - inner * 0.6f)),
            strokeWidth = 1.3f,
            cap = StrokeCap.Round,
        )
    }
}

// Moon: shown when the app is light (clicking switches to dark).
private fun DrawScope.drawMoonIcon(tint: Color) {
    val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Crescent: full circle minus an offset bite. We approximate with a single concave path.
    drawPath(
        path = ComposePath().apply {
            // Start at top-right, sweep around to bottom-right via the outer disk.
            moveTo(10.5f, 2.5f)
            cubicTo(7.5f, 3f, 5f, 5.6f, 5f, 8.8f)
            cubicTo(5f, 12.2f, 7.8f, 14.5f, 10.5f, 14f)
            cubicTo(8.4f, 13.1f, 7f, 11.1f, 7f, 8.6f)
            cubicTo(7f, 6.1f, 8.4f, 4.1f, 10.5f, 2.5f)
            close()
        },
        color = tint,
        style = stroke,
    )
}
