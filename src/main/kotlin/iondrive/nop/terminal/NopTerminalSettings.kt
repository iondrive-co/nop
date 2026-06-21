package iondrive.nop.terminal

import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import java.awt.Color
import java.awt.Font

/**
 * Terminal look that follows nop's active theme. The default foreground/background are read live
 * from [fg]/[bg], which the host updates whenever the theme toggles — so flipping nop between
 * light and dark repaints open terminals to match the surrounding editor instead of staying stuck
 * on one palette. ANSI colours keep JediTerm's xterm-256 defaults, which read fine on either
 * background.
 */
class NopTerminalSettings(
    @Volatile var bg: Color,
    @Volatile var fg: Color,
) : DefaultSettingsProvider() {
    override fun getTerminalFontSize(): Float = 13f

    override fun getTerminalFont(): Font = Font(Font.MONOSPACED, Font.PLAIN, getTerminalFontSize().toInt())

    override fun getDefaultBackground(): TerminalColor = TerminalColor(bg.red, bg.green, bg.blue)

    override fun getDefaultForeground(): TerminalColor = TerminalColor(fg.red, fg.green, fg.blue)
}
