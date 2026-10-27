package iondrive.nop.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import iondrive.nop.git.ChangeKind
import iondrive.nop.git.CommitFile
import iondrive.nop.git.CommitFileChange
import iondrive.nop.git.FileChange
import iondrive.nop.git.GitRepo
import iondrive.nop.history.LocalHistory
import org.eclipse.jgit.api.Git
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.nio.file.Files
import java.time.Duration

/**
 * Every viewer tab, fed content nobody would write on purpose but that turns up in real repositories
 * all the same: a minified line, a generated file, a binary checked in by mistake, a stray encoding.
 *
 * The bar is only that nop shows it without throwing or hanging. A view is free to clip, truncate or
 * refuse to lay something out; it is not free to take the window down, because the tab it is in is
 * restored on the next launch and takes that down too. That happened: a 70k-character line in a
 * diff asked Compose for a cell 546566px wide, and nop could not start until the tab was removed.
 *
 * Rendered at density 2, because a HiDPI screen doubles every pixel size and so meets Compose's
 * limits at half the content.
 */
class HostileContentTest {
    private class Hostile(val name: String, val bytes: ByteArray)

    private val contents = listOf(
        Hostile("a 70k-character line", ("start\n" + "x".repeat(70_579) + "\nend\n").toByteArray()),
        Hostile("a line at the wrap limit", ("x".repeat(MAX_WRAP_LINE_CHARS) + "\n").toByteArray()),
        Hostile("a 1M-character line with no newline", "y".repeat(1_000_000).toByteArray()),
        Hostile("30k lines", (1..30_000).joinToString("\n") { "line $it of a generated file" }.toByteArray()),
        Hostile("NUL and control bytes", ("\u0000\u0001\u0002\u001b[31mred\u001b[0m\u007f\u0008\u000c\n".repeat(200)).toByteArray()),
        Hostile("invalid UTF-8", ByteArray(4096) { i -> byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte(), 0xFE.toByte(), 0x0A)[i % 5] }),
        Hostile(
            "zero-width, combining and right-to-left text",
            ("a​‍" + "́".repeat(500) + " ‮ reversed ⁧ עברית العربية ﻿\n").repeat(50).toByteArray(),
        ),
        Hostile("CR-only line endings", "a\rb\rc\r".repeat(2000).toByteArray()),
        Hostile("tabs", ("\t".repeat(5000) + "deep\n").repeat(20).toByteArray()),
        Hostile("empty", ByteArray(0)),
    )

    @TestFactory
    fun `every viewer survives hostile content`(): List<DynamicContainer> = contents.map { hostile ->
        DynamicContainer.dynamicContainer(
            hostile.name,
            tabsFor(hostile).map { (label, setup) ->
                DynamicTest.dynamicTest(label) {
                    assertTimeoutPreemptively(Duration.ofSeconds(60)) { renderUntilSettled(setup(), "${hostile.name} / $label") }
                }
            },
        )
    }

    private class Setup(val tab: Tab, val repo: GitRepo, val history: LocalHistory, val wrap: Boolean)

    /** A repo whose last commit wrote [hostile] into three files, then changed each again on disk. */
    private fun tabsFor(hostile: Hostile): List<Pair<String, () -> Setup>> {
        val names = listOf("hostile.txt", "hostile.md", "Hostile.java")
        val repoFixture by lazy {
            val dir = Files.createTempDirectory("nop-hostile").toFile().apply { deleteOnExit() }
            names.forEach { File(dir, it).writeText("base\n") }
            val (baseSha, hostileSha) = Git.init().setDirectory(dir).call().use { git ->
                fun commit(message: String): String {
                    git.add().addFilepattern(".").call()
                    return git.commit().setMessage(message).setAuthor("dev", "dev@example.test")
                        .setCommitter("dev", "dev@example.test").setSign(false).call().name
                }
                val base = commit("base")
                names.forEach { File(dir, it).writeBytes(hostile.bytes) }
                base to commit("hostile")
            }
            // Changed again, so the working tree differs from the commit: hostile on both sides.
            names.forEach { File(dir, it).appendBytes("\nmore\n".toByteArray()) }
            val history = LocalHistory(Files.createTempDirectory("nop-hostile-history"))
            val recorded = history.record(File(dir, "hostile.txt"), String(hostile.bytes), at = 1_000L)
            Fixture(dir, GitRepo.discover(dir.toPath())!!, history, baseSha, hostileSha, recorded != null)
        }

        fun file(name: String) = File(repoFixture.dir, name)
        fun setup(tab: () -> Tab, wrap: Boolean = false): () -> Setup = {
            Setup(tab(), repoFixture.repo, repoFixture.history, wrap)
        }

        return buildList {
            for (name in names) {
                add("file view of $name" to setup({ Tab.FileView(file(name)) }))
            }
            add("file view, wrapped" to setup({ Tab.FileView(file("hostile.txt")) }, wrap = true))
            add("diff" to setup({ Tab.Diff(FileChange("hostile.txt", ChangeKind.MODIFIED), repoFixture.dir) }))
            add("diff, wrapped" to setup({ Tab.Diff(FileChange("hostile.txt", ChangeKind.MODIFIED), repoFixture.dir) }, wrap = true))
            add("diff of the java file" to setup({ Tab.Diff(FileChange("Hostile.java", ChangeKind.MODIFIED), repoFixture.dir) }))
            add(
                "commit diff" to setup({
                    val sha = repoFixture.hostileSha
                    Tab.CommitDiff(sha, sha.take(7), CommitFile("hostile.txt", CommitFileChange.MODIFIED), repoFixture.dir)
                }),
            )
            add(
                "revision diff" to setup({
                    val sha = repoFixture.baseSha
                    Tab.RevisionDiff(file("hostile.txt"), sha, sha.take(7), repoFixture.dir)
                }),
            )
            add("local history" to setup({ Tab.LocalHistory(file("hostile.txt")) }))
            add("local diff" to setup({ Tab.LocalDiff(file("hostile.txt"), 1_000L) }))
        }
    }

    private class Fixture(
        val dir: File,
        val repo: GitRepo,
        val history: LocalHistory,
        val baseSha: String,
        val hostileSha: String,
        val recorded: Boolean,
    )

    /**
     * Renders [setup]'s tab for long enough that whatever it loads off the UI thread has arrived and
     * been laid out. Any throw from composition, measure, layout or draw comes out of render().
     */
    private fun renderUntilSettled(setup: Setup, label: String) {
        val tabsState = TabsState().apply { open(setup.tab) }
        val scene = ImageComposeScene(1400, 900, Density(2f)) {
            IntUiTheme(isDark = false) {
                TabbedViewerPanel(
                    tabsState = tabsState,
                    repo = setup.repo,
                    editStore = FileEditStore(),
                    localHistory = setup.history,
                    wrapLines = setup.wrap,
                )
            }
        }
        try {
            var time = 0L
            var slowest = 0L
            val until = System.currentTimeMillis() + SETTLE_MS
            while (System.currentTimeMillis() < until) {
                time += 16_000_000
                val started = System.nanoTime()
                scene.render(time).close()
                slowest = maxOf(slowest, (System.nanoTime() - started) / 1_000_000)
                Thread.sleep(20)
            }
            println("slowest frame ${slowest}ms: $label")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val SETTLE_MS = 1_500L
    }
}
