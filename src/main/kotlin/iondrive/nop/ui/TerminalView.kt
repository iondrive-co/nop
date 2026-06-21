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
 * Renders a [Tab.Terminal]. Launcher sessions get a slim Compose header (name · status +
 * Stop/Re-run); a plain shell fills the whole tab.
 *
 * All terminals share a *single* [cards] panel (a Swing [CardLayout]) hosted by one [SwingPanel].
 * This is deliberate: Compose Desktop embeds each `SwingPanel` as a heavyweight AWT component in
 * one shared native overlay, so having one `SwingPanel` per terminal tab makes them paint over
 * each other and never switch. Routing every terminal through one card panel sidesteps that —
 * switching tabs is a plain `CardLayout.show`, which Swing handles correctly. The panel itself is
 * remembered above the tab `when`, so it (and the live widgets in it) survive visits to non-terminal
 * tabs. Colours are pushed in from nop's theme on every update so light/dark toggles take effect live.
 */
@Composable
fun TerminalView(tab: Tab.Terminal, cards: JPanel) {
    val isDark = JewelTheme.isDark
    val bg = JewelTheme.globalColors.panelBackground
    val fg = if (isDark) Color(0xFFA9B7C6) else Color(0xFF1F2329)
    val awtBg = bg.toAwt()
    val awtFg = fg.toAwt()

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
                ensureCard(cards, tab, awtBg, awtFg)
                cards
            },
            update = {
                ensureCard(cards, tab, awtBg, awtFg)
                tab.session.applyColors(awtBg, awtFg)
                cards.background = awtBg
                // Only flip the visible card (and steal focus) when the selected terminal actually
                // changes — not on every recomposition, which would fight terminal text selection.
                if (cards.getClientProperty(SHOWN_ID) != tab.id) {
                    (cards.layout as CardLayout).show(cards, tab.id)
                    cards.putClientProperty(SHOWN_ID, tab.id)
                    tab.session.getOrCreateWidget(awtBg, awtFg).requestFocusInWindow()
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private const val SHOWN_ID = "nop.shownTerminalId"

/** Lazily create the session's widget and add it to the shared card panel under the tab id. */
private fun ensureCard(cards: JPanel, tab: Tab.Terminal, bg: java.awt.Color, fg: java.awt.Color) {
    val w = tab.session.getOrCreateWidget(bg, fg)
    if (w.parent !== cards) cards.add(w, tab.id)
}

private fun Color.toAwt(): java.awt.Color =
    java.awt.Color((red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())
