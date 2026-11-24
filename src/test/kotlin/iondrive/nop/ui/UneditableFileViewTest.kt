package iondrive.nop.ui

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import iondrive.nop.TextFiles
import iondrive.nop.git.ChangeKind
import iondrive.nop.git.CommitFile
import iondrive.nop.git.CommitFileChange
import iondrive.nop.git.FileChange
import iondrive.nop.git.GitRepo
import iondrive.nop.git.GitStatus
import iondrive.nop.history.LocalHistory
import org.eclipse.jgit.api.Git
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.EventQueue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class UneditableFileViewTest {

    @Test
    fun `opening large binary and oversized text tabs shows refusal view without loading buffers`(@TempDir dir: File) {
        val archive = File(dir, "release.tar.gz").apply {
            val bytes = ByteArray(8 * 1024 * 1024)
            bytes[0] = 0x1f
            bytes[1] = 0x8b.toByte()
            bytes[2] = 0x08
            writeBytes(bytes)
        }
        val rawBinary = File(dir, "binary-blob").apply {
            writeBytes(ByteArray(16_384) { i -> if (i % 7 == 0) 0 else 65 })
        }
        val bigLog = File(dir, "oversized.log").apply {
            writeBytes(ByteArray((TextFiles.MAX_TEXT_BYTES + 65_536).toInt()) { 'x'.code.toByte() })
        }
        val history = LocalHistory(Files.createTempDirectory("nop-uneditable-history"))

        val cases = listOf(
            archive to "Binary file cannot be opened in the text editor",
            rawBinary to "Binary file cannot be opened in the text editor",
            bigLog to "File is too large to open in the text editor",
        )
        for ((file, expectedHeading) in cases) {
            assertTimeoutPreemptively(Duration.ofSeconds(15)) {
                val store = FileEditStore()
                val tab = Tab.FileView(file)
                val shown = renderAndCollectTexts(frames = 8) {
                    TabbedViewerPanel(
                        tabsState = TabsState().apply { open(tab) },
                        repo = null,
                        editStore = store,
                        localHistory = history,
                    )
                }
                assertTrue(expectedHeading in shown, "expected '$expectedHeading' for ${file.name}; got $shown")
                assertTrue("Open with system app" in shown, "expected system-app action for ${file.name}; got $shown")
                assertNull(store.peek(tab.id), "${file.name} must not be loaded into FileEditStore")
                assertTrue(store.snapshot().isEmpty(), "${file.name} must leave FileEditStore empty")
            }
        }
    }

    @Test
    fun `diff viewers and diff panel refuse binary and oversized files cleanly`(@TempDir dir: File) {
        val archive = File(dir, "bundle.tar.gz").apply {
            writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 1, 2, 3, 4))
        }
        val bigLog = File(dir, "large.log").apply {
            writeBytes(ByteArray((TextFiles.MAX_TEXT_BYTES + 32_768).toInt()) { 'a'.code.toByte() })
        }
        val history = LocalHistory(Files.createTempDirectory("nop-uneditable-diff-history"))
        history.record(archive, "earlier text\n", at = 1_000L)

        val commitSha = Git.init().setDirectory(dir).call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("add binary and large log")
                .setAuthor("dev", "dev@example.test")
                .setCommitter("dev", "dev@example.test")
                .setSign(false).call().name
        }
        // Mutate both in working tree so every diff surface has both sides populated.
        archive.appendBytes(byteArrayOf(5, 6, 7, 8))
        bigLog.appendBytes("tail\n".toByteArray())

        val repo = GitRepo.discover(dir.toPath())!!
        try {
            val cases = listOf(
                Tab.Diff(FileChange(archive.name, ChangeKind.MODIFIED), dir) to "diff not shown",
                Tab.Diff(FileChange(bigLog.name, ChangeKind.MODIFIED), dir) to "too large to diff",
                Tab.CommitDiff(commitSha, commitSha.take(7), CommitFile(archive.name, CommitFileChange.ADDED), dir) to "diff not shown",
                Tab.CommitDiff(commitSha, commitSha.take(7), CommitFile(bigLog.name, CommitFileChange.ADDED), dir) to "too large to diff",
                Tab.RevisionDiff(archive, commitSha, commitSha.take(7), dir) to "diff not shown",
                Tab.RevisionDiff(bigLog, commitSha, commitSha.take(7), dir) to "too large to diff",
                Tab.LocalDiff(archive, 1_000L) to "diff not shown",
            )
            for ((tab, expectedFragment) in cases) {
                assertTimeoutPreemptively(Duration.ofSeconds(15)) {
                    val store = FileEditStore()
                    val shown = awaitTextContaining(expectedFragment) {
                        TabbedViewerPanel(
                            tabsState = TabsState().apply { open(tab) },
                            repo = repo,
                            editStore = store,
                            localHistory = history,
                        )
                    }
                    assertTrue(
                        shown.any { expectedFragment in it },
                        "expected '$expectedFragment' for $tab; got $shown",
                    )
                    assertTrue(store.snapshot().isEmpty(), "$tab must not populate FileEditStore")
                }
            }

            // DiffPanel auto-selects the first changed file; it must refuse a binary archive cleanly.
            assertTimeoutPreemptively(Duration.ofSeconds(15)) {
                val status = GitStatus(
                    branch = "main",
                    changes = listOf(
                        FileChange(archive.name, ChangeKind.MODIFIED),
                        FileChange(bigLog.name, ChangeKind.MODIFIED),
                    ),
                )
                val shown = awaitTextContaining("diff not shown") {
                    DiffPanel(
                        repo = repo,
                        status = status,
                        refreshKey = 0,
                        base = DiffBase.Uncommitted,
                        onBaseChange = {},
                        sessionBaselineSha = null,
                        splitRatio = 0.5f,
                        onSplitRatioChange = {},
                    )
                }
                assertTrue(shown.any { "diff not shown" in it }, "DiffPanel must show binary refusal; got $shown")
            }
        } finally {
            repo.close()
        }
    }

    @Test
    fun `javaEditorFor refuses an oversized java file without loading it into FileEditStore`(@TempDir tmp: Path) {
        val hugeJava = tmp.resolve("Generated.java").toFile().apply {
            writeBytes(ByteArray((TextFiles.MAX_TEXT_BYTES + 16_384).toInt()) { ' '.code.toByte() })
        }
        val store = FileEditStore()
        assertNull(javaEditorFor(Tab.FileView(hugeJava), tmp, store))
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun `right-clicking a large binary file in ProjectTreePanel opens context menu without opening the file`(
        @TempDir tmp: Path,
    ) {
        val archive = tmp.resolve("archive.tar.gz").toFile().apply {
            writeBytes(ByteArray(4 * 1024 * 1024).also { it[0] = 0x1f; it[1] = 0x8b.toByte() })
        }
        val note = tmp.resolve("note.txt").toFile().apply { writeText("hello\n") }
        val opened = mutableListOf<File>()
        var capturedMenu: List<String>? = null
        val spyMenu = object : ContextMenuRepresentation {
            @Composable
            override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
                if (state.status is ContextMenuState.Status.Open) {
                    capturedMenu = items().map { it.label }
                }
            }
        }

        assertTimeoutPreemptively(Duration.ofSeconds(15)) {
            val scene = onEdt {
                ImageComposeScene(400, 400, Density(1f)) {
                    IntUiTheme(isDark = false) {
                        CompositionLocalProvider(LocalContextMenuRepresentation provides spyMenu) {
                            ProjectTreePanel(
                                projectPath = tmp,
                                status = GitStatus.EMPTY,
                                onFileClick = { opened += it },
                            )
                        }
                    }
                }
            }
            try {
                fun frame() {
                    onEdt { scene.render(System.nanoTime()).close() }
                }

                // Wait for background listing and Compose layout to show both rows.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
                var archiveCenter = findCenter(scene, archive.name)
                while (archiveCenter == null && System.nanoTime() < deadline) {
                    frame()
                    Thread.sleep(25)
                    archiveCenter = findCenter(scene, archive.name)
                }
                val pos = requireNotNull(archiveCenter) { "archive row never appeared" }

                onEdt { scene.sendPointerEvent(PointerEventType.Move, pos) }
                frame()
                onEdt {
                    scene.sendPointerEvent(
                        PointerEventType.Press,
                        pos,
                        buttons = PointerButtons(isSecondaryPressed = true),
                        button = PointerButton.Secondary,
                    )
                }
                frame()
                onEdt {
                    scene.sendPointerEvent(
                        PointerEventType.Release,
                        pos,
                        buttons = PointerButtons(),
                        button = PointerButton.Secondary,
                    )
                }
                repeat(5) {
                    frame()
                    Thread.sleep(20)
                }

                assertEquals(emptyList<File>(), opened, "right-click must never open ${archive.name}")
                assertTrue(!capturedMenu.isNullOrEmpty(), "right-click must open the context menu")
                assertTrue("Delete" in capturedMenu!!, "context menu should include Delete; got $capturedMenu")

                // Primary click on a sibling text file still opens it afterwards.
                val notePos = requireNotNull(findCenter(scene, note.name)) { "note row never appeared" }
                onEdt { scene.sendPointerEvent(PointerEventType.Move, notePos) }
                frame()
                onEdt {
                    scene.sendPointerEvent(
                        PointerEventType.Press,
                        notePos,
                        buttons = PointerButtons(isPrimaryPressed = true),
                        button = PointerButton.Primary,
                    )
                }
                frame()
                onEdt {
                    scene.sendPointerEvent(
                        PointerEventType.Release,
                        notePos,
                        buttons = PointerButtons(),
                        button = PointerButton.Primary,
                    )
                }
                repeat(3) {
                    frame()
                    Thread.sleep(20)
                }
                assertEquals(listOf(note.absolutePath), opened.map { it.absolutePath })
            } finally {
                onEdt { scene.close() }
            }
        }
    }

    private fun renderAndCollectTexts(frames: Int = 10, content: @Composable () -> Unit): List<String> {
        val scene = onEdt {
            ImageComposeScene(1200, 800, Density(1f)) {
                IntUiTheme(isDark = false) { content() }
            }
        }
        try {
            repeat(frames) {
                onEdt { scene.render(System.nanoTime()).close() }
                Thread.sleep(15)
            }
            return onEdt { scene.semanticsOwners.flatMap { allTexts(it.unmergedRootSemanticsNode) } }
        } finally {
            onEdt { scene.close() }
        }
    }

    private fun awaitTextContaining(fragment: String, content: @Composable () -> Unit): List<String> {
        val scene = onEdt {
            ImageComposeScene(1200, 800, Density(1f)) {
                IntUiTheme(isDark = false) { content() }
            }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            var texts: List<String>
            do {
                texts = onEdt {
                    scene.render(System.nanoTime()).close()
                    scene.semanticsOwners.flatMap { allTexts(it.unmergedRootSemanticsNode) }
                }
                if (texts.any { fragment in it }) return texts
                Thread.sleep(25)
            } while (System.nanoTime() < deadline)
            return texts
        } finally {
            onEdt { scene.close() }
        }
    }

    private fun findCenter(scene: ImageComposeScene, label: String) = onEdt {
        fun find(node: SemanticsNode): SemanticsNode? =
            node.takeIf { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true }
                ?: node.children.firstNotNullOfOrNull(::find)
        scene.semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode) }?.boundsInRoot?.center
    }

    private fun allTexts(node: SemanticsNode): List<String> {
        val own = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        return own + node.children.flatMap(::allTexts)
    }

    private fun <T> onEdt(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
