package iondrive.nop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import iondrive.nop.history.LocalHistory
import java.awt.EventQueue
import java.awt.event.KeyEvent as AwtKeyEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JPanel
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaLibraryViewTest {
    @Test fun `library buffer cannot save or overwrite its cached source`(@TempDir root: Path) {
        val file = root.resolve("GadgetWriter.java").toFile().apply { writeText("class GadgetWriter {}") }
        val store = FileEditStore()
        val tab = Tab.FileView(file, readOnly = true, sourceLabel = "toolkit.jar!/example/GadgetWriter.java")
        val edit = store.edit(tab)
        edit.state.edit { replace(0, length, "changed") }
        edit.markUserEdit()
        assertFalse(edit.hasUserEdit)
        assertTrue(edit.save() is SaveResult.Failed)
        assertTrue(edit.overwriteDisk() is SaveResult.Failed)
        assertEquals("class GadgetWriter {}", file.readText())
        assertEquals(assertNotNull(tab.sourceLabel), assertNotNull(fileNameOf(tab, null)).first)
        assertFalse(assertNotNull(fileNameOf(tab, null)).second)
    }

    @Test fun `library types appear in name and qualified name search`() {
        val library = "Libraries/toolkit.jar/example/library/GadgetWriter.java"
        val files = listOf("src/Example.java", library, "Libraries/JDK java.base/java/lang/String.java")
        assertEquals(library, FileSearchRanking.rank("GadgetWriter", files).first())
        assertEquals(listOf(library), FileSearchRanking.rank("example.library.GadgetWriter", files))
        assertEquals(listOf(files.last()), FileSearchRanking.rank("java.lang.String", files))
    }

    @Test fun `library declaration supports selection and find while typing and replace are disabled`(@TempDir root: Path) {
        val text = "package example.library;\n\npublic class GadgetWriter {\n    public void close();\n}\n"
        val file = root.resolve("GadgetWriter.java").toFile().apply { writeText(text) }
        Viewer(root, Tab.FileView(file, true, "toolkit.jar!/example/library/GadgetWriter.class")).use { viewer ->
            viewer.settle()
            assertTrue(viewer.shows("Compiled declaration (read-only)"))
            assertFalse(viewer.texts().any { it.startsWith("Line ") }, "Declarations are not checked as editable source")
            viewer.focusSource()
            viewer.type('x')
            viewer.settle()
            assertEquals(text, viewer.store.peek(Tab.FileView(file).id)?.state?.text?.toString())
            viewer.find()
            viewer.settle()
            assertEquals(2, viewer.editableFields())
            viewer.replace()
            viewer.settle()
            assertEquals(2, viewer.editableFields(), "Read-only views do not open a replacement field")
            assertEquals(text, file.readText())
            viewer.snapshot(Path.of("build/verification/java-library-declaration.png"))
        }
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private class Viewer(root: Path, tab: Tab.FileView) : AutoCloseable {
        val store = FileEditStore()
        private var find by mutableStateOf(0)
        private var replace by mutableStateOf(0)
        private var time = 0L
        private val source = JPanel()
        private val scene = onEdt {
            val tabs = TabsState().apply { open(tab) }
            ImageComposeScene(1100, 550, Density(1f)) {
                IntUiTheme(isDark = false) {
                    TabbedViewerPanel(tabs, null, store, LocalHistory(root.resolve("history")),
                        findInFileTrigger = find, replaceInFileTrigger = replace)
                }
            }
        }

        fun settle() = repeat(30) {
            time += 16_000_000
            onEdt { scene.render(time).close() }
            Thread.sleep(5)
        }

        fun find() = onEdt { find++ }
        fun replace() = onEdt { replace++ }
        fun texts(): List<String> = onEdt {
            nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text)?.map { value -> value.text } ?: emptyList() }
        }
        fun shows(text: String) = texts().any { text in it }
        fun editableFields() = onEdt { nodes().count { it.config.getOrNull(SemanticsProperties.EditableText) != null } }

        fun focusSource() = onEdt {
            val node = nodes().first { it.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains("GadgetWriter") == true }
            assertTrue(assertNotNull(node.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            assertTrue(assertNotNull(node.config.getOrNull(SemanticsActions.SetSelection)?.action).invoke(0, 0, false))
        }

        fun type(character: Char) = onEdt {
            val event = AwtKeyEvent(source, AwtKeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, AwtKeyEvent.VK_UNDEFINED, character)
            scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.KeyDown, character.code, nativeEvent = event))
        }

        fun snapshot(path: Path) = onEdt {
            Files.createDirectories(path.parent)
            scene.render(time).use { image -> image.encodeToData()!!.use { Files.write(path, it.bytes) } }
        }

        private fun nodes(): List<SemanticsNode> {
            fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
            return scene.semanticsOwners.flatMap { all(it.unmergedRootSemanticsNode) }
        }

        override fun close() = onEdt { scene.close() }
        private fun <T> onEdt(block: () -> T): T {
            var result: Result<T>? = null
            EventQueue.invokeAndWait { result = runCatching(block) }
            return result!!.getOrThrow()
        }
    }
}
