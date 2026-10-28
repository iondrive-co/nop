package iondrive.nop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import iondrive.nop.git.ChangeKind
import iondrive.nop.git.FileChange
import iondrive.nop.git.GitRepo
import iondrive.nop.history.LocalHistory
import iondrive.nop.lang.GradleJavaModel
import iondrive.nop.lang.GradleJavaSourceSet
import iondrive.nop.lang.JavaAnalysisProject
import iondrive.nop.lang.JavaDeclKind
import iondrive.nop.lang.JavaParse
import iondrive.nop.lang.JavaUsages
import iondrive.nop.lang.UsageTarget
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaDiagnosticsTest {
    @Test fun `dependency import status suppresses unresolved errors until the model is ready`(@TempDir root: Path) {
        val file = root.resolve("Widget.java").toFile().apply { writeText("class Widget { MissingType value; }") }
        Viewer(root, Tab.FileView(file), initialStatus = "Importing Gradle dependencies…").use { viewer ->
            viewer.await { viewer.shows("Importing Gradle dependencies…") }
            assertTrue(!viewer.hasProblemBar())
            viewer.snapshot(Path.of("build/verification/gradle-import-status.png"))
            val model = GradleJavaModel(listOf(GradleJavaSourceSet(root.toString(), "main", listOf(root.toString()), emptyList())))
            viewer.project(JavaAnalysisProject(root.toFile(), gradle = model))
            viewer.await { viewer.shows("cannot find symbol") }
            viewer.project(JavaAnalysisProject(root.toFile(), gradle = model, dependencyStatus = "Gradle import failed — press F5 to retry"))
            viewer.await { viewer.shows("Gradle import failed") && !viewer.hasProblemBar() }
            viewer.project(JavaAnalysisProject(root.toFile(), gradle = model))
            viewer.replace(file, "class Widget { String value; }")
            viewer.await { !viewer.shows("Gradle import failed") && !viewer.hasProblemBar() }
        }
    }

    @Test fun `compiler errors appear and clear for unsaved editor text`(@TempDir root: Path) {
        val file = root.resolve("Widget.java").toFile().apply { writeText("class Widget { MissingType value; }") }
        Viewer(root, Tab.FileView(file)).use { viewer ->
            viewer.await { viewer.shows("cannot find symbol") }
            viewer.await { viewer.hasProblemMarker() }
            viewer.replace(file, "class Widget { int value = \"text\"; }")
            viewer.await { viewer.shows("incompatible types") }
            viewer.replace(file, "class Widget { int value = 1; }")
            viewer.await { !viewer.hasProblemBar() && !viewer.hasProblemMarker() }
            viewer.replace(file, "class Widget { MissingType value; }")
            viewer.await { viewer.shows("cannot find symbol") }
            viewer.snapshot(Path.of("build/verification/java-analysis.png"))
        }
    }

    @Test fun `compiler analysis refreshes when another open buffer changes`(@TempDir root: Path) {
        val sibling = root.resolve("Other.java").toFile().apply {
            writeText("class Other { static String value() { return \"text\"; } }")
        }
        val file = root.resolve("Widget.java").toFile().apply { writeText("class Widget { int n = Other.value(); }") }
        Viewer(root, Tab.FileView(file)).use { viewer ->
            viewer.await { viewer.shows("incompatible types") }
            viewer.replace(sibling, "class Other { static int value() { return 1; } }")
            viewer.await { !viewer.hasProblemBar() }
            viewer.replace(sibling, "class Other { static String value() { return \"text\"; } }")
            viewer.await { viewer.shows("incompatible types") }
        }
    }

    @Test fun `editable diff shows compiler errors and shares a selected symbol with references`(@TempDir root: Path) {
        val file = root.resolve("Widget.java").toFile()
        val base = "class Widget {\n  int first = 1;\n\n  private int target = 1;\n}\n"
        file.writeText(base)
        Git.init().setDirectory(root.toFile()).call().use {
            it.add().addFilepattern(".").call()
            it.commit().setMessage("Initial sources").setAuthor("dev", "dev@example.test")
                .setCommitter("dev", "dev@example.test").setSign(false).call()
        }
        file.writeText(base.replace("target = 1", "target = missing()"))
        val repo = assertNotNull(GitRepo.discover(root))
        try {
            val tab = Tab.Diff(FileChange("Widget.java", ChangeKind.MODIFIED), root.toFile())
            Viewer(root, tab, repo).use { viewer ->
                viewer.await { viewer.shows("cannot find symbol") && viewer.hasEditableText("target") }
                viewer.select("target")
                viewer.await { viewer.selection(file) == "target" }
                val active = assertNotNull(javaEditorFor(tab, root, viewer.store))
                val text = active.third.state.text.toString()
                val selected = active.third.state.selection
                assertEquals(text.indexOf("target"), selected.min)
                val parsed = assertNotNull(JavaParse.parse(text, file.name))
                assertEquals(UsageTarget.Member("target", "Widget", JavaDeclKind.FIELD, true),
                    JavaUsages.targetForReferences(parsed, file.name, selected.start, selected.end))
            }
        } finally { repo.close() }
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private class Viewer(root: Path, tab: Tab, repo: GitRepo? = null, initialStatus: String? = null) : AutoCloseable {
        private var project by mutableStateOf(JavaAnalysisProject(root.toFile(), dependencyStatus = initialStatus))
        val store = FileEditStore()
        private var time = 0L
        private val scene = onEdt {
            val tabs = TabsState().apply { open(tab) }
            ImageComposeScene(1100, 550, Density(1f)) {
                IntUiTheme(isDark = false) {
                    TabbedViewerPanel(tabs, repo, store, LocalHistory(root.resolve("history")),
                        javaProject = project)
                }
            }
        }

        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            do {
                time += 16_000_000
                onEdt { scene.render(time).close() }
                if (condition()) return
                Thread.sleep(15)
            } while (System.nanoTime() < deadline)
            assertTrue(condition(), "Expected editor state did not arrive; visible text: ${texts()}")
        }

        fun project(value: JavaAnalysisProject) = onEdt { project = value }

        fun replace(file: java.io.File, text: String) = onEdt {
            store.edit(Tab.FileView(file)).state.edit { replace(0, length, text) }
        }

        fun shows(text: String) = texts().any { text in it }
        fun hasProblemBar() = texts().any { it.startsWith("Line ") }
        fun hasProblemMarker(): Boolean = onEdt {
            scene.render(time).use { image ->
                Bitmap.makeFromImage(image).use { bitmap ->
                    (0 until image.height).any { y ->
                        (image.width - 48 until image.width).any { x -> bitmap.getColor(x, y) == PROBLEM_MARK.toArgb() }
                    }
                }
            }
        }
        private fun texts(): List<String> = onEdt {
            nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text)?.map { text -> text.text } ?: emptyList() }
        }

        fun hasEditableText(name: String) = onEdt {
            nodes().any { name in (it.config.getOrNull(SemanticsProperties.EditableText)?.text ?: "") }
        }

        fun select(name: String) = onEdt {
            val node = nodes().first { name in (it.config.getOrNull(SemanticsProperties.EditableText)?.text ?: "") }
            assertTrue(assertNotNull(node.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            val text = node.config[SemanticsProperties.EditableText].text
            val start = text.indexOf(name)
            assertTrue(assertNotNull(node.config.getOrNull(SemanticsActions.SetSelection)?.action).invoke(start, start + name.length, false))
        }

        fun selection(file: java.io.File): String = onEdt {
            val state = store.edit(Tab.FileView(file)).state
            state.text.subSequence(state.selection.min, state.selection.max).toString()
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
