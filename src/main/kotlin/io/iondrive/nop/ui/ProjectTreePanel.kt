package io.iondrive.nop.ui

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
import androidx.compose.ui.unit.dp
import io.iondrive.nop.git.GitStatus
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.lazy.tree.ChildrenGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.lazy.tree.rememberTreeState
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.PathIconKey
import java.io.File
import java.nio.file.Path

// "Open" icon from the bundled IntelliJ Platform icons jar — used as the change-project button.
// Jewel automatically picks the _dark variant when running under the dark theme.
private val OpenFolderIconKey = PathIconKey("expui/general/open.svg", ProjectTreePanelClass::class.java)

private object ProjectTreePanelClass

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
    headerExtras: @Composable () -> Unit = {},
) {
    val tree = remember(projectPath, refreshKey) { projectPath.asFilteredTree() }
    val treeState = rememberTreeState()
    val rootId = remember(projectPath) { projectPath.toFile().absolutePath }

    LaunchedEffect(rootId) {
        treeState.openNodes(listOf(rootId))
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Project")
            IconButton(onClick = onChangeProject) {
                Icon(
                    key = OpenFolderIconKey,
                    contentDescription = "Change project",
                    modifier = Modifier.size(16.dp),
                )
            }
            // Push headerExtras (the launcher ▶) to the far right
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            headerExtras()
        }
        LazyTree(
            tree = tree,
            treeState = treeState,
            modifier = Modifier.fillMaxSize(),
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
