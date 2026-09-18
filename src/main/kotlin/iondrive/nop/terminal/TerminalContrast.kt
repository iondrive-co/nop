package iondrive.nop.terminal

import java.awt.Color
import java.util.concurrent.ConcurrentHashMap

/**
 * Ensures terminal text maintains readable contrast against the active cell background.
 *
 * TUIs often emit ANSI 256 or truecolor escape sequences designed for dark terminal backgrounds.
 * On light themes, pale blues (ANSI 111), lavenders (ANSI 103), and light golds (ANSI 179) have
 * contrast ratios below 2:1 against a light background, rendering them illegible.
 *
 * This utility adjusts foreground text brightness to achieve at least WCAG AA contrast (4.5:1)
 * while preserving the original hue and saturation.
 */
internal object TerminalContrast {
    /** WCAG AA minimum contrast ratio for normal text. */
    const val MIN_CONTRAST = 4.5

    private val cache = ConcurrentHashMap<Long, Color>()

    /**
     * WCAG 2.1 relative luminance:
     * https://www.w3.org/WAI/GL/wiki/Relative_luminance
     */
    fun relativeLuminance(color: Color): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    /**
     * Contrast ratio between two colors:
     * (L1 + 0.05) / (L2 + 0.05) where L1 >= L2.
     */
    fun contrastRatio(fg: Color, bg: Color): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Cached wrapper around [ensureContrast].
     */
    fun cachedEnsureContrast(fg: Color, bg: Color, minContrast: Double = MIN_CONTRAST): Color {
        val key = (fg.rgb.toLong() shl 32) or (bg.rgb.toLong() and 0xffffffffL)
        return cache.computeIfAbsent(key) { ensureContrast(fg, bg, minContrast) }
    }

    /**
     * Adjusts [fg] to achieve at least [minContrast] ratio against [bg], preserving [fg]'s hue.
     * If the contrast ratio is already >= [minContrast], [fg] is returned unchanged.
     */
    fun ensureContrast(fg: Color, bg: Color, minContrast: Double = MIN_CONTRAST): Color {
        if (contrastRatio(fg, bg) >= minContrast) return fg

        val bgLum = relativeLuminance(bg)
        val isLightBg = bgLum >= 0.5

        val hsb = FloatArray(3)
        Color.RGBtoHSB(fg.red, fg.green, fg.blue, hsb)
        val h = hsb[0]
        val s = hsb[1]
        val b = hsb[2]

        if (isLightBg) {
            // Light background: darken fg by decreasing brightness towards 0
            var low = 0.0f
            var high = b
            var bestColor = Color(0, 0, 0, fg.alpha)
            for (i in 0..15) {
                val mid = (low + high) / 2f
                val candidateRgb = Color.HSBtoRGB(h, s, mid)
                val candidate = Color((candidateRgb and 0x00ffffff) or (fg.alpha shl 24), true)
                if (contrastRatio(candidate, bg) >= minContrast) {
                    bestColor = candidate
                    low = mid // Keep as close to original brightness as possible
                } else {
                    high = mid
                }
            }
            return bestColor
        } else {
            // Dark background: lighten fg by increasing brightness towards 1
            var low = b
            var high = 1.0f
            var bestColor = Color(255, 255, 255, fg.alpha)
            for (i in 0..15) {
                val mid = (low + high) / 2f
                val candidateRgb = Color.HSBtoRGB(h, s, mid)
                val candidate = Color((candidateRgb and 0x00ffffff) or (fg.alpha shl 24), true)
                if (contrastRatio(candidate, bg) >= minContrast) {
                    bestColor = candidate
                    high = mid // Keep as close to original brightness as possible
                } else {
                    low = mid
                }
            }
            // If full brightness still falls short (e.g. deep saturated blue), reduce saturation
            if (contrastRatio(bestColor, bg) < minContrast) {
                var satLow = 0.0f
                var satHigh = s
                for (i in 0..15) {
                    val midSat = (satLow + satHigh) / 2f
                    val candidateRgb = Color.HSBtoRGB(h, midSat, 1.0f)
                    val candidate = Color((candidateRgb and 0x00ffffff) or (fg.alpha shl 24), true)
                    if (contrastRatio(candidate, bg) >= minContrast) {
                        bestColor = candidate
                        satLow = midSat
                    } else {
                        satHigh = midSat
                    }
                }
            }
            return bestColor
        }
    }
}
