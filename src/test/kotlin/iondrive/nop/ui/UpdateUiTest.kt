package iondrive.nop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import iondrive.nop.update.SelfUpdate
import iondrive.nop.update.Version
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateUiTest {

    @AfterTest
    fun reset() {
        SelfUpdate.status = SelfUpdate.Status.Idle
    }

    /** How many pixels of [image] are, near enough, the colour [argb]. */
    private fun Image.count(argb: Int): Int {
        val bitmap = Bitmap.makeFromImage(this)
        var n = 0
        for (y in 0 until height) for (x in 0 until width) {
            val c = bitmap.getColor(x, y)
            val close = listOf(16, 8, 0).all { shift -> kotlin.math.abs(((c shr shift) and 0xFF) - ((argb shr shift) and 0xFF)) < 24 }
            if (close) n++
        }
        return n
    }

    private fun render(): Image {
        val scene = ImageComposeScene(400, 40, Density(1f)) {
            IntUiTheme(isDark = false) {
                Row {
                    UpdateBadge(onRestart = {}, onDetails = {}, height = 30.dp)
                    VersionLabel(onClick = {}, height = 30.dp)
                }
            }
        }
        return try {
            var image = scene.render(0)
            repeat(5) { i -> image = scene.render((i + 1) * 16_000_000L) }
            image
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the badge shows only once an update is staged`() {
        val green = ChangeColors.ADDED.toArgb()

        SelfUpdate.status = SelfUpdate.Status.Current
        assertEquals(0, render().count(green), "no badge while nop is up to date")

        SelfUpdate.status = SelfUpdate.Status.Ready(Version(9, 9, 9), atRestart = true)
        assertTrue(render().count(green) > 20, "the badge, in the colour of something added")
    }

    @Test
    fun `every status says something`() {
        val statuses = listOf(
            SelfUpdate.Status.Idle,
            SelfUpdate.Status.Off("nop is built from the checkout at /home/dev/nop; rebuild it to update"),
            SelfUpdate.Status.Disabled,
            SelfUpdate.Status.Checking,
            SelfUpdate.Status.Current,
            SelfUpdate.Status.Downloading(Version(9, 9, 9), 50, 200),
            SelfUpdate.Status.Ready(Version(9, 9, 9), atRestart = false),
            SelfUpdate.Status.Failed("could not reach api.github.com"),
        )
        for (status in statuses) assertTrue(updateStatusLine(status).isNotBlank())
        assertTrue("25%" in updateStatusLine(SelfUpdate.Status.Downloading(Version(9, 9, 9), 50, 200)))
    }
}
