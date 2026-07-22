package iondrive.nop.ui

import androidx.compose.ui.text.font.FontFamily
import org.junit.jupiter.api.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class FontsTest {
    // NopFonts.Mono loads JetBrains Mono from the classpath (shipped by the Jewel dependency) and
    // silently falls back to the platform monospace if the TTFs are missing. That fallback keeps
    // text rendering, but it also means a Jewel upgrade that relocated the fonts would revert every
    // editor/diff/search surface to DejaVu Sans Mono with no error anywhere. This test is the
    // tripwire: it fails if we're on the fallback, i.e. the bundled font is no longer resolvable.
    @Test fun `mono resolves to the bundled JetBrains Mono, not the platform fallback`() {
        assertNotNull(NopFonts.Mono)
        assertNotEquals(
            FontFamily.Monospace,
            NopFonts.Mono,
            "NopFonts.Mono fell back to the platform monospace — the JetBrains Mono TTFs are no " +
                "longer on the classpath (did the Jewel dependency move fonts/jetbrains-mono/?).",
        )
    }
}
