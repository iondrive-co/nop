package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.awt.CardLayout
import javax.swing.JPanel
import kotlin.math.roundToInt

/**
 * Renders one [TerminalTab] — a launcher run, a shell, or an agent session. Launcher runs get a
 * slim Compose header (name · status + Stop/Re-run);
 * a plain shell fills the whole panel.
 *
 * All terminals share a *single* [cards] panel (a Swing [CardLayout]) hosted by one [SwingPanel].
 * This is deliberate: Compose Desktop embeds each `SwingPanel` as a heavyweight AWT component in
 * one shared native overlay, so having one `SwingPanel` per run makes them paint over each other
 * and never switch. Routing every terminal through one card panel sidesteps that — switching runs
 * is a plain `CardLayout.show`, which Swing handles correctly. The panel itself is remembered in
 * [App], above the tool panel, so it (and the live widgets in it) survive visits to the other tool
 * tabs. Colours are pushed in from nop's theme on every update so light/dark toggles take effect
 * live.
 */
@Composable
fun TerminalView(tab: TerminalTab, cards: JPanel) {
    val isDark = JewelTheme.isDark
    val bg = JewelTheme.globalColors.panelBackground
    val fg = if (isDark) Color(0xFFA9B7C6) else Color(0xFF000000)
    // Same link blues the markdown preview uses, so a URL looks the same wherever nop shows one.
    val link = if (isDark) Color(0xFF6897BB) else Color(0xFF1750EB)
    val awtBg = bg.toAwt()
    val awtFg = fg.toAwt()
    val awtLink = link.toAwt()

    Column(modifier = Modifier.fillMaxSize().background(bg)) {
        if (tab.session.isLauncher) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tab.session.title, modifier = Modifier.weight(1f))
                Text(
                    if (tab.session.running) "running" else "exited",
                    color = if (tab.session.running) ChangeColors.UNTRACKED else ChangeColors.REMOVED,
                )
                if (tab.session.running) {
                    OutlinedButton(onClick = { tab.session.stop() }) { Text("Stop") }
                } else {
                    OutlinedButton(onClick = { tab.session.restart() }) { Text("Re-run") }
                }
            }
        }
        SwingPanel(
            background = bg,
            factory = {
                ensureCard(cards, tab, awtBg, awtFg, awtLink)
                cards
            },
            update = {
                ensureCard(cards, tab, awtBg, awtFg, awtLink)
                tab.session.applyColors(awtBg, awtFg, awtLink)
                cards.background = awtBg
                // Only flip the visible card (and steal focus) when the selected run actually
                // changes — not on every recomposition, which would fight terminal text selection.
                if (cards.getClientProperty(SHOWN_ID) != tab.id) {
                    (cards.layout as CardLayout).show(cards, tab.id)
                    cards.putClientProperty(SHOWN_ID, tab.id)
                    tab.session.getOrCreateWidget(awtBg, awtFg, awtLink).requestFocusInWindow()
                }
            },
            modifier = Modifier.fillMaxSize().padding(bottom = LocalTerminalBottomInset.current),
        )
    }
}

/**
 * How much room the terminal leaves at the bottom of the window for whatever floats there — the
 * agent usage strip.
 *
 * A [SwingPanel] is a heavyweight AWT component: it is composited above everything Compose draws,
 * so any Compose content under its rectangle is simply not on screen. A terminal that filled its
 * panel drew straight over the usage strip and there was no way to see or click it.
 *
 * Measured rather than guessed. The strip's height depends on how many accounts are configured and
 * how they wrap, so a fixed clearance is wrong for everyone but the person it was tuned for — it
 * was 34dp, which was one account's worth. [UsageIndicator] reports what it actually occupies and
 * [App] puts the number here.
 */
val LocalTerminalBottomInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

private const val SHOWN_ID = "nop.shownTerminalId"

/** Lazily create the session's widget and add it to the shared card panel under the run id. */
private fun ensureCard(
    cards: JPanel,
    tab: TerminalTab,
    bg: java.awt.Color,
    fg: java.awt.Color,
    link: java.awt.Color,
) {
    val w = tab.session.getOrCreateWidget(bg, fg, link)
    if (w.parent !== cards) cards.add(w, tab.id)
}

private fun Color.toAwt(): java.awt.Color =
    java.awt.Color((red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())
