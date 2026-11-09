package iondrive.nop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import iondrive.nop.git.ChangeKind
import iondrive.nop.git.FileChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.EventQueue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PinnedFilesPanelTest {
    @Test
    fun `diff and file views pin the same working file while library sources cannot be pinned`(@TempDir dir: Path) {
        val file = Files.writeString(dir.resolve("notes.txt"), "Notes").toFile()
        assertEquals(file, pinnableFile(Tab.FileView(file)))
        assertEquals(file, pinnableFile(Tab.Diff(FileChange("notes.txt", ChangeKind.MODIFIED), dir.toFile())))
        assertNull(pinnableFile(Tab.FileView(file, readOnly = true, sourceLabel = "Library")))
        assertNull(pinnableFile(Tab.Diff(FileChange("removed.txt", ChangeKind.REMOVED), dir.toFile())))
    }

    @Test
    fun `badge pins can be opened and removed from the conditional section`(@TempDir dir: Path) {
        for (dark in listOf(false, true)) {
            Viewer(dir, dark).use { viewer ->
                viewer.settle()
                assertFalse(viewer.has("Pinned files"))
                viewer.click("Pin file")
                assertTrue(viewer.has("Pinned files"))
                assertTrue(viewer.has("Unpin file"))
                viewer.clickText("notes.txt")
                assertEquals(dir.resolve("notes.txt").toFile(), viewer.opened)
                viewer.screenshot(dark)
                viewer.click("Remove pin for notes.txt")
                assertFalse(viewer.has("Pinned files"))
                assertTrue(viewer.has("Pin file"))
                viewer.click("Pin file")
                viewer.click("Unpin file")
                assertFalse(viewer.has("Pinned files"))
            }
        }
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private class Viewer(private val project: Path, dark: Boolean) : AutoCloseable {
        private val file = project.resolve("notes.txt")
        private var files by mutableStateOf(emptyList<Path>())
        var opened: File? = null
        private var time = 0L
        private val scene = onEdt {
            ImageComposeScene(420, 240, Density(1f), coroutineContext = Dispatchers.Swing) {
                IntUiTheme(isDark = dark) {
                    Column {
                        CompositionLocalProvider(LocalFileNameBadge provides FileNameBadge(
                            "notes.txt", false, false, {}, file in files,
                            { files = if (file in files) emptyList() else listOf(file) },
                        )) {
                            Box(Modifier.fillMaxWidth().height(120.dp)) { FileNameBadgeOverlay() }
                        }
                        PinnedFilesPanel(project, files, null, { opened = it }, { files = files - listOf(it) }, Modifier.fillMaxWidth().height(100.dp))
                    }
                }
            }
        }

        fun settle() = repeat(12) {
            time += 16_000_000
            onEdt { scene.render(time).close() }
            Thread.sleep(5)
        }

        private fun nodes(): List<SemanticsNode> = onEdt {
            fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
            scene.semanticsOwners.flatMap { all(it.unmergedRootSemanticsNode) }
        }

        fun has(label: String) = nodes().any { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

        fun click(label: String) = clickNode(nodes().first { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })

        fun clickText(label: String) = clickNode(nodes().last { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true })

        private fun clickNode(node: SemanticsNode) {
            val at = node.boundsInRoot.center
            onEdt { scene.sendPointerEvent(PointerEventType.Move, at) }
            settle()
            onEdt { scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary) }
            settle()
            onEdt { scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary) }
            settle()
        }

        fun screenshot(dark: Boolean) {
            val target = Path.of("build/test-screenshots/pinned-files-${if (dark) "dark" else "light"}.png")
            Files.createDirectories(target.parent)
            onEdt { scene.render(time).use { image -> image.encodeToData()!!.use { Files.write(target, it.bytes) } } }
        }

        override fun close() = onEdt { scene.close() }

        private fun <T> onEdt(block: () -> T): T {
            var result: Result<T>? = null
            EventQueue.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
    }
}
