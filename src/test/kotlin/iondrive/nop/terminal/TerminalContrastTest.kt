package iondrive.nop.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

class TerminalContrastTest {

    @Test
    fun `relative luminance of white is 1 and black is 0`() {
        assertEquals(1.0, TerminalContrast.relativeLuminance(Color.WHITE), 1e-4)
        assertEquals(0.0, TerminalContrast.relativeLuminance(Color.BLACK), 1e-4)
    }

    @Test
    fun `contrast ratio of black and white is 21`() {
        assertEquals(21.0, TerminalContrast.contrastRatio(Color.BLACK, Color.WHITE), 0.1)
    }

    @Test
    fun `high contrast colors are left unchanged`() {
        val white = Color.WHITE
        val black = Color.BLACK
        val darkGray = Color(30, 30, 30)

        assertEquals(black, TerminalContrast.ensureContrast(black, white))
        assertEquals(white, TerminalContrast.ensureContrast(white, darkGray))
    }

    @Test
    fun `ANSI 111 pale blue on white is adjusted to meet WCAG AA while preserving hue`() {
        // ANSI 111: rgb(135, 175, 255)
        val paleBlue = Color(135, 175, 255)
        val white = Color.WHITE

        val originalContrast = TerminalContrast.contrastRatio(paleBlue, white)
        assertTrue(originalContrast < 3.0, "pale blue on white should have low contrast, was $originalContrast")

        val adjusted = TerminalContrast.ensureContrast(paleBlue, white)
        val newContrast = TerminalContrast.contrastRatio(adjusted, white)
        assertTrue(newContrast >= 4.5, "adjusted color must meet 4.5:1 contrast, was $newContrast")

        // Check hue preservation
        val origHsb = FloatArray(3)
        val adjHsb = FloatArray(3)
        Color.RGBtoHSB(paleBlue.red, paleBlue.green, paleBlue.blue, origHsb)
        Color.RGBtoHSB(adjusted.red, adjusted.green, adjusted.blue, adjHsb)
        assertEquals(origHsb[0], adjHsb[0], 0.01f, "hue should be preserved")
    }

    @Test
    fun `ANSI 179 gold on white is adjusted to meet WCAG AA while preserving hue`() {
        // ANSI 179: rgb(223, 175, 95)
        val gold = Color(223, 175, 95)
        val white = Color.WHITE

        val originalContrast = TerminalContrast.contrastRatio(gold, white)
        assertTrue(originalContrast < 3.0, "gold on white should have low contrast, was $originalContrast")

        val adjusted = TerminalContrast.ensureContrast(gold, white)
        val newContrast = TerminalContrast.contrastRatio(adjusted, white)
        assertTrue(newContrast >= 4.5, "adjusted color must meet 4.5:1 contrast, was $newContrast")

        val origHsb = FloatArray(3)
        val adjHsb = FloatArray(3)
        Color.RGBtoHSB(gold.red, gold.green, gold.blue, origHsb)
        Color.RGBtoHSB(adjusted.red, adjusted.green, adjusted.blue, adjHsb)
        assertEquals(origHsb[0], adjHsb[0], 0.01f, "hue should be preserved")
    }

    @Test
    fun `ANSI 103 lavender on white is adjusted to meet WCAG AA`() {
        // ANSI 103: rgb(135, 135, 175)
        val lavender = Color(135, 135, 175)
        val white = Color.WHITE

        val originalContrast = TerminalContrast.contrastRatio(lavender, white)
        assertTrue(originalContrast < 4.5, "lavender on white should have contrast < 4.5, was $originalContrast")

        val adjusted = TerminalContrast.ensureContrast(lavender, white)
        val newContrast = TerminalContrast.contrastRatio(adjusted, white)
        assertTrue(newContrast >= 4.5, "adjusted color must meet 4.5:1 contrast, was $newContrast")
    }

    @Test
    fun `ANSI 110 cyan on white is adjusted to meet WCAG AA`() {
        // ANSI 110: rgb(135, 175, 175)
        val teal = Color(135, 175, 175)
        val white = Color.WHITE

        val originalContrast = TerminalContrast.contrastRatio(teal, white)
        assertTrue(originalContrast < 4.5, "teal on white should have contrast < 4.5, was $originalContrast")

        val adjusted = TerminalContrast.ensureContrast(teal, white)
        val newContrast = TerminalContrast.contrastRatio(adjusted, white)
        assertTrue(newContrast >= 4.5, "adjusted color must meet 4.5:1 contrast, was $newContrast")
    }

    @Test
    fun `dark text on dark background is lightened to meet WCAG AA`() {
        val darkBlue = Color(10, 15, 60)
        val darkBg = Color(24, 24, 24)

        val originalContrast = TerminalContrast.contrastRatio(darkBlue, darkBg)
        assertTrue(originalContrast < 2.0, "dark blue on dark bg should have low contrast, was $originalContrast")

        val adjusted = TerminalContrast.ensureContrast(darkBlue, darkBg)
        val newContrast = TerminalContrast.contrastRatio(adjusted, darkBg)
        assertTrue(newContrast >= 4.5, "adjusted color must meet 4.5:1 contrast, was $newContrast")
    }

    @Test
    fun `caching preserves result`() {
        val paleBlue = Color(135, 175, 255)
        val white = Color.WHITE

        val uncached = TerminalContrast.ensureContrast(paleBlue, white)
        val cached = TerminalContrast.cachedEnsureContrast(paleBlue, white)
        assertEquals(uncached, cached)
    }
}
