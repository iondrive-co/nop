package iondrive.nop.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import iondrive.nop.git.ChangeKind
import iondrive.nop.git.FileChange
import iondrive.nop.history.LocalHistory
import iondrive.nop.recovery.LocalWindowId
import iondrive.nop.recovery.Quarantine
import iondrive.nop.recovery.WindowRecovery
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class QuarantinedTabTest {
    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun isolate() {
        Quarantine.fileOverride = dir.resolve("quarantine")
        WindowRecovery.showingFileOverride = dir.resolve("showing")
        Quarantine.clearForTest()
        WindowRecovery.resetForTest()
    }

    @AfterEach
    fun restore() {
        Quarantine.clearForTest()
        WindowRecovery.resetForTest()
        Quarantine.fileOverride = null
        WindowRecovery.showingFileOverride = null
    }

    @Test
    fun `a diff is named by its file, so two projects on one repo agree on it`() {
        val repo = File("/work/hermes")
        assertEquals(
            "diff:/work/hermes/docs/IDEAS.md",
            Tab.Diff(FileChange("docs/IDEAS.md", ChangeKind.MODIFIED), repo).recoveryKey,
        )
        assertEquals("file:/work/hermes/a.md", Tab.FileView(File("/work/hermes/a.md")).recoveryKey)
    }

    @Test
    fun `plain text bounds every line, and the whole`() {
        val lines = plainTextLines(("short\r\n" + "x".repeat(70_579) + "\rcr\nlf").byteInputStream())
        assertEquals("short", lines[0])
        assertEquals("x".repeat(MAX_PLAIN_LINE_CHARS) + " … (+${70_579 - MAX_PLAIN_LINE_CHARS} characters)", lines[1])
        assertEquals(listOf("cr", "lf"), lines.drop(2))

        val huge = plainTextLines(ByteArray(MAX_PLAIN_BYTES + 10) { 'a'.code.toByte() }.inputStream())
        assertTrue(huge.last().startsWith("… (the rest of the file"), huge.last())
    }

    @Test
    fun `plain text shows control and direction-override characters, rather than obeying them`() {
        val lines = plainTextLines("a\u0000b\u001b[31m‮evil\tend".byteInputStream())
        assertEquals(listOf("a�b�[31m�evil end"), lines)
    }

    @Test
    fun `plain text of invalid UTF-8 is replacement characters, not an error`() {
        val lines = plainTextLines(byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte()).inputStream())
        assertEquals(1, lines.size)
        assertTrue('�' in lines[0])
    }

    @Test
    fun `the viewer reports the tab it draws to its window, and not one it holds back`() {
        val file = dir.resolve("notes.md").toFile().apply { writeText("# notes\n") }
        val shown = Tab.FileView(file)
        render(shown, window = 3L) {
            assertEquals(setOf(shown.recoveryKey), WindowRecovery.showingIn(3L))
        }

        Quarantine.add(listOf(shown.recoveryKey), "IllegalArgumentException: earlier")
        WindowRecovery.resetForTest()
        render(shown, window = 3L) {
            assertTrue(WindowRecovery.showingIn(3L).isEmpty(), "a held-back tab's content is not drawn, so not reported")
        }

        // Trying again puts it back on screen.
        Quarantine.release(shown.recoveryKey)
        render(shown, window = 3L) {
            assertEquals(setOf(shown.recoveryKey), WindowRecovery.showingIn(3L))
        }
    }

    @Test
    fun `the plain text of a held-back tab renders whatever the file holds`() {
        val file = dir.resolve("blob.txt").toFile().apply { writeText("x".repeat(1_000_000)) }
        val tab = Tab.FileView(file)
        val entry = Quarantine.Entry(tab.recoveryKey, 0, "boom")
        val scene = ImageComposeScene(1400, 900, Density(2f)) {
            IntUiTheme(isDark = false) { QuarantinedTabView(tab, entry) }
        }
        try {
            var time = 0L
            repeat(20) { time += 16_000_000; scene.render(time); Thread.sleep(20) }
        } finally {
            scene.close()
        }
    }

    private fun render(tab: Tab, window: Long, check: () -> Unit) {
        val tabsState = TabsState().apply { open(tab) }
        val scene = ImageComposeScene(1200, 800, Density(1f)) {
            IntUiTheme(isDark = false) {
                CompositionLocalProvider(LocalWindowId provides window) {
                    TabbedViewerPanel(
                        tabsState = tabsState,
                        repo = null,
                        editStore = FileEditStore(),
                        localHistory = LocalHistory(dir.resolve("history")),
                    )
                }
            }
        }
        try {
            var time = 0L
            repeat(10) { time += 16_000_000; scene.render(time) }
            check()
        } finally {
            scene.close()
        }
    }
}
