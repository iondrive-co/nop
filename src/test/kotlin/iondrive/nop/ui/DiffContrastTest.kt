package iondrive.nop.ui

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Guards how readable diff text stays once a row paints a tint behind it.
 *
 * Comments are the token this exists for: they are the palette's dimmest colour, and the insert
 * tint is its most luminous background, so a changed comment on a green row is where legibility
 * bottoms out. The editor's comment grey managed 2.9:1 (dark) and 2.5:1 (light) there — under half
 * of what every other token gets — which is why [HighlightPalette.DarkDiff] and [LightDiff] exist.
 *
 * The thresholds are deliberately "no worse than the rest of the palette" rather than WCAG AA:
 * mid-tone tokens like strings and numbers only reach ~3.5-4:1 over the strongest inline tint
 * themselves, and lifting comments past them would make the dimmest thing on the row the brightest.
 */
class DiffContrastTest {

    // The surface the tints composite over, sampled from the running app: Jewel's panel background
    // in each theme. Tints are semi-transparent, so contrast is only meaningful against these.
    private val darkBase = Color(0xFF2B2D30)
    private val lightBase = Color(0xFFF7F8FA)

    /** WCAG 2.x relative luminance. */
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    /** WCAG 2.x contrast ratio, 1:1 (identical) to 21:1 (black on white). */
    private fun contrast(fg: Color, bg: Color): Double {
        val a = luminance(fg)
        val b = luminance(bg)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /** [tint] (which carries an alpha) painted over [base], the way Compose draws a row background. */
    private fun over(tint: Color, base: Color): Color {
        val a = tint.alpha
        return Color(
            red = tint.red * a + base.red * (1 - a),
            green = tint.green * a + base.green * (1 - a),
            blue = tint.blue * a + base.blue * (1 - a),
        )
    }

    private fun assertReadable(label: String, fg: Color, bg: Color, min: Double) {
        val ratio = contrast(fg, bg)
        assertTrue(ratio >= min, "$label: %.2f:1, want at least %.2f:1".format(ratio, min))
    }

    @Test
    fun `dark comments read on the insert and inline-change greens`() {
        val comment = HighlightPalette.DarkDiff.comment.color
        val insertRow = over(INSERT_BG, darkBase)
        // The strongest green: a changed word inside a CHANGE row, so the word tint layers over the
        // row's own blue tint rather than straight onto the panel.
        val changedWord = over(INLINE_WORD_BG, over(CHANGE_BG, darkBase))

        assertReadable("dark comment on insert row", comment, insertRow, 4.5)
        assertReadable("dark comment on changed word", comment, changedWord, 3.7)
    }

    @Test
    fun `light comments read on the insert and inline-change greens`() {
        val comment = HighlightPalette.LightDiff.comment.color
        val insertRow = over(INSERT_BG, lightBase)
        val changedWord = over(INLINE_WORD_BG, over(CHANGE_BG, lightBase))

        assertReadable("light comment on insert row", comment, insertRow, 4.5)
        assertReadable("light comment on changed word", comment, changedWord, 3.7)
    }

    @Test
    fun `diff comments stay dimmer than the code around them`() {
        // The lift is there to make comments legible, not to promote them: they must still read as
        // secondary against the body text colour, which is what keeps a diff scannable as code.
        val darkBody = luminance(Color(0xFFBCBEC4))
        val darkComment = luminance(HighlightPalette.DarkDiff.comment.color)
        assertTrue(darkComment < darkBody, "dark diff comment is no dimmer than body text")

        val lightBody = luminance(Color(0xFF000000))
        val lightComment = luminance(HighlightPalette.LightDiff.comment.color)
        assertTrue(lightComment > lightBody, "light diff comment is no lighter than body text")
    }

    @Test
    fun `diff comments beat the editor palette on tinted rows`() {
        // The whole point of the diff variants — if these ever converge, the variants are dead code.
        val insertDark = over(INSERT_BG, darkBase)
        assertTrue(
            contrast(HighlightPalette.DarkDiff.comment.color, insertDark) >
                contrast(HighlightPalette.Dark.comment.color, insertDark),
        )
        val insertLight = over(INSERT_BG, lightBase)
        assertTrue(
            contrast(HighlightPalette.LightDiff.comment.color, insertLight) >
                contrast(HighlightPalette.Light.comment.color, insertLight),
        )
    }

    @Test
    fun `diff palettes change nothing but the comment colour`() {
        for ((diff, editor) in listOf(
            HighlightPalette.DarkDiff to HighlightPalette.Dark,
            HighlightPalette.LightDiff to HighlightPalette.Light,
        )) {
            assertTrue(diff.copy(comment = editor.comment) == editor, "diff palette drifted from the editor's")
            // Italics are what still marks a comment as prose once the brightness gap narrows.
            assertTrue(diff.comment.fontStyle == editor.comment.fontStyle, "comments lost their italics")
        }
    }
}
