package iondrive.nop.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Point
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path

class SlackViewStateTest {
    @Test
    fun `placement survives restart and malformed settings fall back safely`(@TempDir root: Path) {
        val project = root.resolve("workshop")
        val store = SlackWindowStore { it }
        val placement = SlackPlacement(floating = true, visible = true, workspace = "TALPHA", width = 820, height = 640, x = 72, y = 96)
        store.save(project, placement)
        assertEquals(placement, SlackWindowStore { it }.load(project))
        assertEquals(SlackPlacement(), store.load(root.resolve("studio")))
        Files.writeString(project.resolve("slack-window.json"), """{"width":-1,"height":999999,"workspace":"https://example.invalid"}""")
        val clamped = store.load(project)
        assertEquals(400, clamped.width)
        assertEquals(10000, clamped.height)
        assertNull(clamped.workspace)
        Files.writeString(project.resolve("slack-window.json"), "invalid")
        assertEquals(SlackPlacement(), store.load(project))
    }

    @Test
    fun `floating keeps its browser alive until a docked view takes over`() {
        var leases = 0
        val view = SlackViewState(Path.of("/example/workshop")) {
            leases++
            AutoCloseable { leases-- }
        }
        view.show("TALPHA")
        view.float()
        view.float()
        assertEquals(1, leases)
        view.show("TBRAVO")
        view.dock()
        assertEquals(1, leases)
        assertFalse(view.floating)
        assertEquals("TBRAVO", view.selectedId.value)
        view.dockedViewReady()
        view.dockedViewReady()
        assertEquals(0, leases)
        assertTrue(view.visible.value)
    }

    @Test
    fun `closing and reopening floating view releases and reacquires its browser lease`() {
        var leases = 0
        val view = SlackViewState(Path.of("/example/studio"), SlackPlacement(floating = true, visible = true, workspace = "TALPHA")) {
            leases++
            AutoCloseable { leases-- }
        }
        assertEquals(1, leases)
        view.hide()
        view.hide()
        assertEquals(0, leases)
        assertFalse(view.visible.value)
        assertTrue(view.floating)
        view.show()
        assertEquals(1, leases)
        assertEquals("TALPHA", view.placement().workspace)
        view.hide()
        val restored = SlackViewState(view.project, view.placement()) {
            leases++
            AutoCloseable { leases-- }
        }
        assertEquals(0, leases)
        assertTrue(restored.floating)
        assertFalse(restored.visible.value)
    }

    @Test
    fun `clampPlacement brings top-offscreen and disconnected-monitor windows back onscreen`() {
        val primary = listOf(Rectangle(0, 28, 1920, 1052))

        // Top bit pushed above top panel: reopening brings y down to screen.y while keeping x and size.
        val topOff = SlackPlacement(floating = true, visible = true, width = 900, height = 700, x = 240, y = -90)
        assertEquals(topOff.copy(y = 28), clampPlacement(topOff, primary))

        // Bottom-right corner pushed past screen edge: shifts left and up just enough to fit.
        val bottomRightOff = SlackPlacement(floating = true, visible = true, width = 900, height = 700, x = 1500, y = 700)
        assertEquals(bottomRightOff.copy(x = 1020, y = 380), clampPlacement(bottomRightOff, primary))

        // Window saved on an unplugged second monitor returns onto the remaining primary screen.
        val absentMonitor = SlackPlacement(floating = true, visible = true, width = 900, height = 700, x = 2200, y = 120)
        assertEquals(absentMonitor.copy(x = 1020, y = 120), clampPlacement(absentMonitor, primary))

        // Oversized window shrinks to fit smaller screen and keeps PlatformDefault null coordinates intact.
        val smallScreen = listOf(Rectangle(0, 0, 800, 600))
        val unpositionedOversized = SlackPlacement(floating = true, visible = true, width = 1200, height = 900, x = null, y = null)
        assertEquals(unpositionedOversized.copy(width = 800, height = 600), clampPlacement(unpositionedOversized, smallScreen))
        assertEquals(unpositionedOversized, clampPlacement(unpositionedOversized, emptyList()))
    }

    @Test
    fun `isWindowOffscreen and clampDragTopY handle single and multi-monitor layouts`() {
        val left = Rectangle(0, 28, 1920, 1052)
        val right = Rectangle(1920, 28, 1920, 1052)
        val dual = listOf(left, right)

        assertFalse(isWindowOffscreen(Rectangle(100, 40, 900, 700), dual))
        // Straddling the seam between two side-by-side monitors is completely onscreen.
        assertFalse(isWindowOffscreen(Rectangle(1500, 40, 900, 700), dual))
        // Top edge above the usable area makes the titlebar unreachable.
        assertTrue(isWindowOffscreen(Rectangle(100, 0, 900, 700), dual))
        assertTrue(isWindowOffscreen(Rectangle(3500, 40, 900, 700), dual))

        // Dragging on a single screen cannot push the top edge above screen.y.
        assertEquals(28, clampDragTopY(-15, Point(500, 400), listOf(left)))
        assertEquals(120, clampDragTopY(120, Point(500, 400), listOf(left)))

        // Stacked monitor above allows dragging into negative y coordinates on the upper screen.
        val upper = Rectangle(0, -1080, 1920, 1080)
        assertEquals(-400, clampDragTopY(-400, Point(500, -200), listOf(left, upper)))

        // With a client-drawn titlebar margin, dragging stays below screen.y + margin and detects y = 0.
        assertEquals(76, clampDragTopY(10, Point(500, 400), listOf(left), topMargin = 48))
        assertTrue(isWindowOffscreen(Rectangle(100, 28, 900, 700), listOf(left), topMargin = 48))
    }

    @Test
    fun `bringWindowOnScreenBounds centers over the main window inside multi-monitor virtual screens`() {
        // One virtual desktop spanning asymmetric monitors: a lower monitor extends below y = 3563,
        // and another monitor starts at y = 1440 inside the virtual rectangle.
        val virtual = listOf(Rectangle(0, 0, 9881, 4320))
        val mainWindow = Rectangle(2000, 3100, 1600, 1000)

        // Window stranded with its top offscreen above the y = 1440 monitor still sits inside the
        // virtual bounding box, so Bring on screen must move it over the active main window.
        val stranded = Rectangle(2200, 1380, 900, 700)
        val brought = bringWindowOnScreenBounds(stranded, mainWindow, virtual, topMargin = XPRA_TITLEBAR_MARGIN)
        assertEquals(Rectangle(2350, 3250, 900, 700), brought)

        // When the main window sits near the top of a monitor, keep room for a client-drawn titlebar.
        val topMain = Rectangle(100, 0, 1600, 1000)
        val atTop = bringWindowOnScreenBounds(Rectangle(100, 0, 900, 980), topMain, virtual, topMargin = XPRA_TITLEBAR_MARGIN)
        assertTrue(atTop.y >= XPRA_TITLEBAR_MARGIN)
        assertTrue(atTop.y + atTop.height <= topMain.height)

        // A window legitimately placed on the lower monitor stays untouched by clampPlacement on reopen.
        val lowerSaved = SlackPlacement(floating = true, visible = true, width = 900, height = 700, x = 2350, y = 3250)
        assertEquals(lowerSaved, clampPlacement(lowerSaved, virtual, topMargin = XPRA_TITLEBAR_MARGIN))

        // Without an anchor window, clamping falls back to usable screen bounds with the titlebar margin.
        assertEquals(
            Rectangle(100, XPRA_TITLEBAR_MARGIN, 900, 700),
            bringWindowOnScreenBounds(Rectangle(100, -40, 900, 700), null, virtual, topMargin = XPRA_TITLEBAR_MARGIN),
        )
    }
}
