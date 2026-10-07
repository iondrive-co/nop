package iondrive.nop.ui

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import iondrive.nop.diff.InlineSpan
import iondrive.nop.git.ChangeKind
import iondrive.nop.git.FileChange
import iondrive.nop.git.GitRepo
import org.eclipse.jgit.api.Git
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DiffInteractionTest {

    @Test
    fun `the grab band is whole when nothing sits on the divider`() {
        val band = Rect(100f, 50f, 109f, 550f)
        assertEquals(listOf(0f to 500f), grabStrips(band, emptyList()))
    }

    @Test
    fun `the grab band leaves a gap at each control on the divider`() {
        val band = Rect(100f, 50f, 109f, 550f)
        val controls = listOf(Rect(96f, 150f, 112f, 166f), Rect(96f, 70f, 112f, 86f))
        assertEquals(listOf(0f to 20f, 36f to 100f, 116f to 500f), grabStrips(band, controls))
    }

    @Test
    fun `overlapping controls leave one gap and controls off the band leave none`() {
        val band = Rect(100f, 50f, 109f, 550f)
        val controls = listOf(
            Rect(96f, 100f, 112f, 116f),
            Rect(96f, 110f, 112f, 126f),
            // Beside the band, and scrolled out of view (clipped to nothing).
            Rect(300f, 200f, 316f, 216f),
            Rect(96f, 300f, 112f, 300f),
        )
        assertEquals(listOf(0f to 50f, 76f to 500f), grabStrips(band, controls))
    }

    @Test
    fun `a control at the band's ends leaves no empty strip`() {
        val band = Rect(100f, 50f, 109f, 550f)
        val controls = listOf(Rect(96f, 40f, 112f, 60f), Rect(96f, 540f, 112f, 560f))
        assertEquals(listOf(10f to 490f), grabStrips(band, controls))
    }

    @Test
    fun `a line carries its syntax colour only, its tints are backgrounds`() {
        val annotated = annotateLine("val x = 1", listOf(Token(0, 3, TokenKind.KEYWORD)), HighlightPalette.LightDiff)
        assertTrue(annotated.spanStyles.none { it.item.background != Color.Unspecified })
    }

    @Test
    fun `block backgrounds are placed at each line's offset in the block`() {
        val tint = Color(0xFFF7BDB9)
        val find = LineFindHits(listOf(0..1), active = null, color = Color.Yellow, activeColor = Color.Red)
        val backgrounds = blockBackgrounds(
            lines = listOf("alpha", null, "gamma delta"),
            spans = listOf(
                listOf(InlineSpan(1, 3, changed = true), InlineSpan(3, 5, changed = false)),
                emptyList(),
                listOf(InlineSpan(6, 11, changed = true)),
            ),
            highlightColor = tint,
            find = listOf(null, null, find),
        )
        // "alpha\n" + "\n" puts the third line at 7.
        assertEquals(
            listOf(TextBackground(1, 3, tint), TextBackground(13, 18, tint), TextBackground(7, 9, Color.Yellow)),
            backgrounds,
        )
    }

    @Test
    fun `selecting changed words shows the selection over their tint`(@TempDir dir: File) {
        val file = File(dir, "Sample.kt")
        val base = (1..30).joinToString("\n") { "val item$it = \"entry number $it\" // note $it" } + "\n"
        file.writeText(base)
        Git.init().setDirectory(dir).call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("base").setAuthor("dev", "dev@example.test")
                .setCommitter("dev", "dev@example.test").setSign(false).call()
        }
        file.writeText(base.replace("\"entry number 5\"", "\"rewritten five\""))
        val repo = GitRepo.discover(dir.toPath())!!
        val tint = DiffColors.Light.inlineWordBgOld.toArgb()
        val selection = Color.Magenta
        val width = 1200
        val height = 500
        val scene = ImageComposeScene(width, height, Density(1f)) {
            IntUiTheme(isDark = false) {
                CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(selection, selection)) {
                    DiffView(repo, Tab.Diff(FileChange("Sample.kt", ChangeKind.MODIFIED), dir), FileEditStore())
                }
            }
        }
        try {
            var time = 0L
            fun frame(): Image { time += 16_000_000; return scene.render(time) }

            // The diff loads off the UI thread; wait until the changed words are on screen.
            var before = 0
            val deadline = System.currentTimeMillis() + 15_000
            while (before == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                before = frame().count(tint)
            }
            assertTrue(before > 0, "the changed words are tinted before anything is selected")
            // Then let the list settle: its first frames are still sizing it.
            repeat(30) { frame(); Thread.sleep(20) }

            // Drag from the first line down the left half, past the changed line, with real gaps
            // between the events: the pointer input reads their timing.
            val from = Offset(120f, 40f)
            val to = Offset(width * 0.45f, height - 10f)
            val down = PointerButtons(isPrimaryPressed = true)
            fun step() { frame(); Thread.sleep(20) }
            scene.sendPointerEvent(PointerEventType.Move, from)
            step()
            scene.sendPointerEvent(PointerEventType.Press, from, buttons = down, button = PointerButton.Primary)
            step()
            for (i in 1..10) {
                scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (i / 10f), buttons = down)
                step()
            }
            scene.sendPointerEvent(PointerEventType.Release, to, buttons = PointerButtons(), button = PointerButton.Primary)
            step()
            val after = frame()

            assertTrue(after.count(selection.toArgb()) > 0, "the drag selected text")
            assertEquals(0, after.count(tint), "no changed word is left showing its tint over the selection")
        } finally {
            scene.close()
            repo.close()
        }
    }

    private fun Image.count(argb: Int): Int {
        val bitmap = Bitmap.makeFromImage(this)
        var n = 0
        for (y in 0 until height) for (x in 0 until width) if (bitmap.getColor(x, y) == argb) n++
        return n
    }
}
