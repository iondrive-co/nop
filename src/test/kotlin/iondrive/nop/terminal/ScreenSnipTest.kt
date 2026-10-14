package iondrive.nop.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage

class ScreenSnipTest {

    @Test
    fun `mergeOverlapping combines intersecting rectangles and keeps separate ones disjoint`() {
        // Disjoint rectangles remain unchanged
        val disjoint = listOf(
            Rectangle(0, 0, 100, 100),
            Rectangle(200, 200, 50, 50),
        )
        val disjointMerged = X11ScreenCapture.mergeOverlapping(disjoint)
        assertEquals(2, disjointMerged.size)
        assertTrue(disjointMerged.contains(Rectangle(0, 0, 100, 100)))
        assertTrue(disjointMerged.contains(Rectangle(200, 200, 50, 50)))

        // Overlapping rectangles are merged into their bounding union
        val overlapping = listOf(
            Rectangle(0, 0, 100, 100),
            Rectangle(50, 50, 100, 100),
        )
        val overlappingMerged = X11ScreenCapture.mergeOverlapping(overlapping)
        assertEquals(1, overlappingMerged.size)
        assertEquals(Rectangle(0, 0, 150, 150), overlappingMerged[0])

        // Transitive overlapping (A overlaps B, B overlaps C)
        val transitive = listOf(
            Rectangle(0, 0, 60, 60),
            Rectangle(40, 40, 60, 60),
            Rectangle(80, 80, 60, 60),
        )
        val transitiveMerged = X11ScreenCapture.mergeOverlapping(transitive)
        assertEquals(1, transitiveMerged.size)
        assertEquals(Rectangle(0, 0, 140, 140), transitiveMerged[0])
    }

    @Test
    fun `isUniformColor identifies monochrome images`() {
        val blank = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        assertTrue(X11ScreenCapture.isUniformColor(blank))

        val varied = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val g = varied.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(10, 10, 30, 30)
        } finally {
            g.dispose()
        }
        assertFalse(X11ScreenCapture.isUniformColor(varied))
    }

    @Test
    fun `X11ScreenCapture captures non-blank workspace contents on Xpra display`() {
        if (!X11ScreenCapture.isXpra()) return

        val capture = X11ScreenCapture.capture()
        assertNotNull(capture, "capture should succeed on Xpra display")
        val result = capture!!
        assertTrue(result.bounds.width > 0)
        assertTrue(result.bounds.height > 0)
        assertTrue(result.overlayBounds.isNotEmpty())
        assertFalse(
            X11ScreenCapture.isUniformColor(result.image),
            "captured workspace image should contain real rendered content rather than uniform black",
        )
    }
}
