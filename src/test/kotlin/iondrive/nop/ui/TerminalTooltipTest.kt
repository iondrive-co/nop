package iondrive.nop.ui

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalTooltipTest {
    private val screen = Rectangle(0, 24, 1200, 876)

    @Test
    fun `a bottom strip tooltip fits above its account`() {
        assertEquals(
            Point(600, 714),
            terminalTooltipPosition(Rectangle(600, 880, 160, 20), Dimension(400, 160), screen),
        )
    }

    @Test
    fun `a tooltip near the top opens below its account`() {
        assertEquals(
            Point(50, 56),
            terminalTooltipPosition(Rectangle(50, 30, 160, 20), Dimension(400, 160), screen),
        )
    }

    @Test
    fun `rightmost account stays within its monitor`() {
        assertEquals(
            Point(800, 714),
            terminalTooltipPosition(Rectangle(1100, 880, 100, 20), Dimension(400, 160), screen),
        )
    }

    @Test
    fun `monitor offsets and panels bound placement`() {
        assertEquals(
            Point(-400, 594),
            terminalTooltipPosition(
                Rectangle(-100, 900, 100, 20), Dimension(400, 300), Rectangle(-1200, 40, 1200, 900),
            ),
        )
    }

    @Test
    fun `a tooltip taller than the screen starts at the usable top edge`() {
        assertEquals(
            Point(50, 24),
            terminalTooltipPosition(Rectangle(50, 880, 160, 20), Dimension(400, 1000), screen),
        )
    }
}
