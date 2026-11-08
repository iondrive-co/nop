package iondrive.nop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import iondrive.nop.ProjectTab
import iondrive.nop.ProjectTreeLayout
import iondrive.nop.Settings
import iondrive.nop.Workspace
import iondrive.nop.git.GitStatus
import java.awt.EventQueue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.assertEquals
import kotlin.test.fail

class ProjectTreeRefreshTest {
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private class Panel(
        initialRoot: Path,
        openFiles: List<File> = emptyList(),
        revealFile: File? = null,
        initialLayout: ProjectTreeLayout? = null,
    ) : AutoCloseable {
        var root by mutableStateOf(initialRoot)
        var tabId by mutableStateOf(0L)
        var activeFile by mutableStateOf(revealFile)
        var filesRestored by mutableStateOf(true)
        private val states = mutableMapOf<Pair<Path, Long>, ProjectTreeState>()
        // Scene operations share the AWT thread with Compose's snapshot notifications.
        private val scene = onEdt {
            ImageComposeScene(600, 500, Density(1f)) {
                IntUiTheme(isDark = false) {
                    key(root, tabId) {
                        ProjectTreePanel(
                            projectPath = root,
                            viewState = states.getOrPut(root to tabId) { ProjectTreeState(initialLayout) },
                            filesRestored = filesRestored,
                            status = GitStatus.EMPTY,
                            openFiles = openFiles.filter { it.toPath().startsWith(root) },
                            revealFile = activeFile?.takeIf { it.toPath().startsWith(root) },
                            onFileClick = {},
                        )
                    }
                }
            }
        }

        fun renderFor(milliseconds: Long) {
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds)
            do {
                onEdt { scene.render(System.nanoTime()) }
                Thread.sleep(25)
            } while (System.nanoTime() < until)
        }

        fun cachedNames(project: Path, dir: Path): Set<String> = onEdt {
            DirectoryListings.forProject(project).peek(dir.toFile()).orEmpty().map { it.file.name }.toSet()
        }

        fun expand(name: String) = setExpanded(name, Key.DirectionRight)

        fun collapse(name: String) = setExpanded(name, Key.DirectionLeft)

        private fun setExpanded(name: String, direction: Key) {
            click(name)
            onEdt { scene.sendKeyEvent(KeyEvent(direction, KeyEventType.KeyDown)) }
            onEdt { scene.sendKeyEvent(KeyEvent(direction, KeyEventType.KeyUp)) }
            renderFor(100)
        }

        fun layout(): ProjectTreeLayout = onEdt { states.getValue(root to tabId).snapshot()!! }

        fun click(name: String) {
            val at = onEdt {
                fun find(node: SemanticsNode): SemanticsNode? =
                    node.takeIf { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == name } == true }
                        ?: node.children.firstNotNullOfOrNull(::find)
                scene.semanticsOwners.firstNotNullOf { find(it.unmergedRootSemanticsNode) }.boundsInRoot.center
            }
            onEdt { scene.sendPointerEvent(PointerEventType.Move, at) }
            renderFor(30)
            onEdt { scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary) }
            renderFor(30)
            onEdt { scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary) }
            renderFor(30)
        }

        fun scrollDown() {
            onEdt { scene.sendPointerEvent(PointerEventType.Scroll, Offset(300f, 250f), scrollDelta = Offset(0f, 100f)) }
            renderFor(500)
        }

        fun awaitNames(vararg present: String, absent: Set<String> = emptySet()) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            var names: Set<String>
            do {
                names = onEdt {
                    scene.render(System.nanoTime())
                    scene.semanticsOwners.flatMap { texts(it.unmergedRootSemanticsNode) }.toSet()
                }
                if (present.all { it in names } && absent.none { it in names }) return
                Thread.sleep(25)
            } while (System.nanoTime() < deadline)
            fail("Expected ${present.toList()} and no $absent; panel shows $names")
        }

        override fun close() = onEdt { scene.close() }

        private fun texts(node: SemanticsNode): List<String> {
            val bounds = node.boundsInRoot
            val visible = bounds.width > 0 && bounds.height > 0 && bounds.top < 500 && bounds.bottom > 0
            val own = if (visible) node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } else emptyList()
            return own + node.children.flatMap(::texts)
        }

        private fun <T> onEdt(block: () -> T): T {
            var result: Result<T>? = null
            EventQueue.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
    }

    @Test fun `external creates renames and deletes appear without a git refresh`(@TempDir tmp: Path) {
        tmp.resolve("anchor.txt").createFile()
        Panel(tmp).use { panel ->
            panel.awaitNames("anchor.txt")

            val added = tmp.resolve("added.txt").createFile()
            panel.awaitNames("added.txt")

            val renamed = Files.move(added, tmp.resolve("renamed.txt"))
            panel.awaitNames("renamed.txt", absent = setOf("added.txt"))

            Files.delete(renamed)
            panel.awaitNames("anchor.txt", absent = setOf("renamed.txt"))
        }
    }

    private fun persistedLayout(project: Path, config: Path, layout: ProjectTreeLayout): ProjectTreeLayout {
        val originalRoot = Settings.configRoot
        return try {
            Settings.configRoot = config
            Settings.saveWorkspaces(listOf(Workspace(0, "", listOf(ProjectTab(0, project, treeLayout = layout)))))
            Settings.loadWorkspaces().single().tabs.single().treeLayout!!
        } finally {
            Settings.configRoot = originalRoot
        }
    }

    @Test fun `restarting restores nested directories and expanded file groups`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").createDirectories()
        val nested = project.resolve("folder/nested").createDirectories()
        (1..10).forEach { nested.resolve("file-%02d.txt".format(it)).createFile() }
        val saved = Panel(project).use { panel ->
            panel.awaitNames("folder")
            panel.expand("folder")
            panel.awaitNames("nested")
            panel.expand("nested")
            panel.awaitNames("··· 7 more files ···", absent = setOf("file-10.txt"))
            panel.click("··· 7 more files ···")
            panel.awaitNames("file-10.txt")
            persistedLayout(project, tmp.resolve("config"), panel.layout())
        }
        Panel(project, initialLayout = saved).use { panel ->
            panel.awaitNames("file-10.txt", "▴ collapse 7 files")
        }
    }

    @Test fun `restarting respects collapsed ancestors of restored files`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").createDirectories()
        val file = project.resolve("parent/nested").createDirectories().resolve("anchor.txt").createFile().toFile()
        val saved = Panel(project, openFiles = listOf(file), revealFile = file).use { panel ->
            panel.awaitNames("anchor.txt")
            panel.collapse("parent")
            panel.awaitNames("parent", absent = setOf("anchor.txt"))
            persistedLayout(project, tmp.resolve("config"), panel.layout())
        }
        Panel(project, openFiles = listOf(file), initialLayout = saved).use { panel ->
            panel.filesRestored = false
            panel.renderFor(200)
            panel.activeFile = file
            panel.filesRestored = true
            panel.renderFor(500)
            panel.awaitNames("parent", absent = setOf("nested", "anchor.txt"))
            panel.expand("parent")
            panel.awaitNames("anchor.txt")
        }
    }

    @Test fun `restarting preserves a collapsed project root`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").createDirectories()
        project.resolve("anchor.txt").createFile()
        val saved = Panel(project).use { panel ->
            panel.awaitNames("anchor.txt")
            panel.collapse("project")
            panel.awaitNames("project", absent = setOf("anchor.txt"))
            persistedLayout(project, tmp.resolve("config"), panel.layout())
        }
        Panel(project, initialLayout = saved).use { panel ->
            panel.renderFor(500)
            panel.awaitNames("project", absent = setOf("anchor.txt"))
        }
    }

    @Test fun `an expanded symlink target refreshes independently of the project`(@TempDir tmp: Path) {
        val project = tmp.resolve("project").createDirectories()
        val target = tmp.resolve("external").createDirectories()
        target.resolve("anchor.txt").createFile()
        val link = Files.createSymbolicLink(project.resolve("linked"), target)

        Panel(project, openFiles = listOf(link.resolve("anchor.txt").toFile())).use { panel ->
            panel.awaitNames("anchor.txt")

            target.resolve("remote.txt").createFile()
            panel.awaitNames("remote.txt")
        }
    }

    @Test fun `switching projects stops polling the old project`(@TempDir tmp: Path) {
        val first = tmp.resolve("first").createDirectories()
        val second = tmp.resolve("second").createDirectories()
        first.resolve("first.txt").createFile()
        second.resolve("second.txt").createFile()
        Panel(first).use { panel ->
            panel.awaitNames("first.txt")
            panel.root = second
            panel.awaitNames("second.txt")

            first.resolve("hidden.txt").createFile()
            panel.renderFor(3_300)
            assertEquals(setOf("first.txt"), panel.cachedNames(first, first))

            panel.root = first
            panel.awaitNames("hidden.txt")
        }
    }

    @Test fun `switching projects preserves manually expanded directories`(@TempDir tmp: Path) {
        val first = tmp.resolve("first").createDirectories()
        val second = tmp.resolve("second").createDirectories()
        first.resolve("folder/nested").createDirectories().resolve("detail.txt").createFile()
        second.resolve("other").createDirectories().resolve("second.txt").createFile()
        Panel(first).use { panel ->
            panel.awaitNames("folder", absent = setOf("nested", "detail.txt"))
            panel.expand("folder")
            panel.awaitNames("nested")
            panel.expand("nested")
            panel.awaitNames("detail.txt")

            panel.root = second
            panel.awaitNames("other", absent = setOf("second.txt"))
            panel.expand("other")
            panel.awaitNames("second.txt")

            panel.root = first
            panel.awaitNames("detail.txt", absent = setOf("other"))
            panel.root = second
            panel.awaitNames("second.txt", absent = setOf("folder"))
        }
    }

    @Test fun `switching projects preserves collapsed ancestors of an open file`(@TempDir tmp: Path) {
        val first = tmp.resolve("first").createDirectories()
        val second = tmp.resolve("second").createDirectories()
        val anchor = first.resolve("parent/nested").createDirectories().resolve("anchor.txt").createFile()
        second.resolve("second.txt").createFile()
        Panel(first, openFiles = listOf(anchor.toFile()), revealFile = anchor.toFile()).use { panel ->
            panel.awaitNames("anchor.txt")
            panel.collapse("parent")
            panel.awaitNames("parent", absent = setOf("anchor.txt"))

            panel.root = second
            panel.awaitNames("second.txt")
            panel.filesRestored = false
            panel.activeFile = null
            panel.root = first
            panel.renderFor(200)
            panel.activeFile = anchor.toFile()
            panel.filesRestored = true
            panel.renderFor(500)
            panel.awaitNames("parent", absent = setOf("nested", "anchor.txt"))
            panel.expand("parent")
            panel.awaitNames("anchor.txt")
        }
    }

    @Test fun `tabs on the same project keep independent expansions`(@TempDir tmp: Path) {
        tmp.resolve("folder").createDirectories().resolve("detail.txt").createFile()
        Panel(tmp).use { panel ->
            panel.awaitNames("folder", absent = setOf("detail.txt"))
            panel.expand("folder")
            panel.awaitNames("detail.txt")

            panel.tabId = 1L
            panel.renderFor(200)
            panel.awaitNames("folder", absent = setOf("detail.txt"))

            panel.tabId = 0L
            panel.awaitNames("detail.txt")
        }
    }

    @Test fun `a collapsed project root stays collapsed after switching projects`(@TempDir tmp: Path) {
        val first = tmp.resolve("first").createDirectories()
        val second = tmp.resolve("second").createDirectories()
        first.resolve("first.txt").createFile()
        second.resolve("second.txt").createFile()
        Panel(first).use { panel ->
            panel.awaitNames("first.txt")
            panel.collapse("first")
            panel.awaitNames("first", absent = setOf("first.txt"))

            panel.root = second
            panel.awaitNames("second.txt")
            panel.root = first
            panel.renderFor(200)
            panel.awaitNames("first", absent = setOf("first.txt"))
        }
    }

    @Test fun `collapsing an ancestor stops polling expanded descendants`(@TempDir tmp: Path) {
        val nested = tmp.resolve("parent/nested").createDirectories()
        val anchor = nested.resolve("anchor.txt").createFile()
        Panel(tmp, openFiles = listOf(anchor.toFile())).use { panel ->
            panel.awaitNames("anchor.txt")
            panel.collapse("parent")
            panel.awaitNames("parent", absent = setOf("anchor.txt"))

            nested.resolve("hidden.txt").createFile()
            panel.renderFor(3_300)
            assertEquals(setOf("anchor.txt"), panel.cachedNames(tmp, nested))
        }
    }

    @Test fun `expanded directories outside the viewport are not polled`(@TempDir tmp: Path) {
        val folder = tmp.resolve("folder").createDirectories()
        val anchor = folder.resolve("anchor.txt").createFile()
        val files = (1..40).map { tmp.resolve("file-%02d.txt".format(it)).createFile().toFile() }
        Panel(tmp, openFiles = listOf(anchor.toFile()) + files).use { panel ->
            panel.awaitNames("anchor.txt")
            panel.scrollDown()
            panel.awaitNames("file-40.txt")

            folder.resolve("hidden.txt").createFile()
            panel.renderFor(3_300)
            assertEquals(setOf("anchor.txt"), panel.cachedNames(tmp, folder))
        }
    }
}
