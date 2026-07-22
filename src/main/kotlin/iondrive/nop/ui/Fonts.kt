package iondrive.nop.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * The font every code/monospace surface in nop renders with (editor, diffs, search results, blame,
 * history, markdown code).
 *
 * We deliberately do *not* use Compose's [FontFamily.Monospace]: on desktop that resolves to the
 * platform's generic monospace, which on most Linux boxes is DejaVu Sans Mono — a serviceable but
 * dated face with a small x-height and tight metrics. IntelliJ reads as "modern" in large part
 * because it ships and uses JetBrains Mono, whose taller x-height and even rhythm make code easier
 * to scan at the same point size.
 *
 * The Jewel dependency already bundles the JetBrains Mono TTFs on the classpath (that's the face
 * the IntelliJ theme uses), so we load them from there instead of committing another ~1MB of fonts
 * to this repo. If a Jewel upgrade ever moves them we fall back to the platform monospace, so text
 * always renders — just without the upgrade.
 */
object NopFonts {
    private const val DIR = "fonts/jetbrains-mono"

    private fun resourcePresent(path: String): Boolean =
        (Thread.currentThread().contextClassLoader ?: NopFonts::class.java.classLoader)
            ?.getResource(path) != null

    val Mono: FontFamily = run {
        val regular = "$DIR/JetBrainsMono-Regular.ttf"
        if (!resourcePresent(regular)) {
            FontFamily.Monospace
        } else {
            FontFamily(
                Font(regular, FontWeight.Normal, FontStyle.Normal),
                Font("$DIR/JetBrainsMono-Italic.ttf", FontWeight.Normal, FontStyle.Italic),
                Font("$DIR/JetBrainsMono-Bold.ttf", FontWeight.Bold, FontStyle.Normal),
                Font("$DIR/JetBrainsMono-BoldItalic.ttf", FontWeight.Bold, FontStyle.Italic),
            )
        }
    }
}
