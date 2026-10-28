package iondrive.nop.ui

import iondrive.nop.git.ChangeKind
import iondrive.nop.git.FileChange
import iondrive.nop.lang.JavaParse
import iondrive.nop.lang.JavaUsages
import iondrive.nop.lang.UsageTarget
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JavaEditorTest {
    @Test fun `library source supports references but cannot be a rename editor`(@TempDir root: Path) {
        val project = root.resolve("project").toFile().apply { mkdirs() }
        val file = root.resolve("cache/Inner.java").toFile().apply {
            parentFile.mkdirs()
            writeText("package example; public class Outer { public static class Inner {} }")
        }
        val tab = Tab.FileView(file, readOnly = true, sourceLabel = "toolkit.jar!/example/Outer.java")
        val store = FileEditStore()
        assertNull(javaEditorFor(tab, project.toPath(), store))
        val active = assertNotNull(javaEditorFor(tab, project.toPath(), store, allowReadOnly = true))
        assertEquals("", active.second)
        val parsed = assertNotNull(JavaParse.parse(active.third.state.text.toString(), file.name))
        assertEquals(UsageTarget.Type("Inner", "example.Outer.Inner", "example"),
            JavaUsages.targetForReferences(parsed, file.name, 0, 0))
        assertTrue(active.third.readOnly)
    }
    @Test fun `a Java source tab initializes its buffer for analysis`(@TempDir root: Path) {
        val file = root.resolve("Widget.java").toFile().apply { writeText("class Widget {}") }
        val store = FileEditStore()
        val active = assertNotNull(javaEditorFor(Tab.FileView(file), root, store))
        assertEquals("Widget.java", active.second)
        assertEquals(file.readText(), active.third.state.text.toString())
        assertSame(store.peek(Tab.FileView(file).id), active.third)
    }

    @Test fun `references from an editable diff use its working buffer and default to the class`(@TempDir root: Path) = runBlocking {
        val file = root.resolve("Widget.java").toFile().apply { writeText("class Widget { int value; }") }
        root.resolve("Use.java").toFile().writeText("class Use { Widget widget; }")
        val store = FileEditStore()
        val expected = store.edit(Tab.FileView(file))
        val diff = Tab.Diff(FileChange("Widget.java", ChangeKind.MODIFIED), root.toFile())
        val active = assertNotNull(javaEditorFor(diff, root, store))
        assertSame(expected, active.third)
        val text = active.third.state.text.toString()
        val caret = text.indexOf("value")
        val parsed = assertNotNull(JavaParse.parse(text, file.name))
        val target = assertNotNull(JavaUsages.targetForReferences(parsed, file.name, caret, caret))
        assertEquals(UsageTarget.Type("Widget", "Widget", ""), target)
        val result = JavaUsages.find(root.toFile(), listOf("Widget.java", "Use.java"), target, active.second)
        assertTrue(result.usages.any { it.path == "Use.java" })
    }

    @Test fun `unrelated files and missing working files do not become Java editors`(@TempDir root: Path) {
        val store = FileEditStore()
        assertNull(javaEditorFor(null, root, store))
        assertNull(javaEditorFor(Tab.FileView(root.resolve("notes.txt").toFile()), root, store))
        assertNull(javaEditorFor(Tab.FileView(root.resolve("../Elsewhere.java").toFile()), root, store))
        assertNull(javaEditorFor(Tab.Diff(FileChange("Gone.java", ChangeKind.REMOVED), root.toFile()), root, store))
    }
}
