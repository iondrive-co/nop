package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import iondrive.nop.browser.SlackAppearance
import iondrive.nop.browser.SlackBrowsers
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import java.awt.CardLayout
import java.awt.Component
import java.nio.file.Path
import javax.swing.JPanel

/** Chromium uses the same native host as the terminals while a workspace fills the tool region. */
@Composable
fun SlackPanel(
    project: Path,
    workspaceId: String?,
    connectionVersion: Int,
    cards: JPanel,
    onWorkspaceDetected: (String) -> Unit,
) {
    val session = remember(project, workspaceId, connectionVersion) { SlackBrowsers.of(project, workspaceId) }
    val state by key(session) { session.state.collectAsState() }
    val browser = state.browser
    val colors = JewelTheme.globalColors
    val background = colors.panelBackground
    val dark = JewelTheme.isDark
    val accent = if (dark) Color(0xFF6897BB) else Color(0xFF1750EB)
    val appearance = SlackAppearance(
        dark = dark,
        background = background.cssHex(background),
        panel = colors.toolwindowBackground.cssHex(background),
        foreground = colors.text.normal.cssHex(background),
        muted = colors.text.info.cssHex(background),
        border = colors.borders.normal.cssHex(background),
        hover = lerp(background, colors.text.normal, if (dark) .10f else .06f).cssHex(background),
        selection = lerp(background, accent, .25f).cssHex(background),
        accent = accent.cssHex(background),
    )
    SideEffect { session.setAppearance(appearance) }
    LaunchedEffect(state.workspace?.id) {
        state.workspace?.id?.takeIf { it != workspaceId }?.let(onWorkspaceDetected)
    }
    Column(Modifier.fillMaxSize().background(background)) {
        if (state.error != null) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.error!!, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { if (browser == null) session.start() else browser.reload() }) { Text("Retry") }
            }
        }
        if (browser != null) {
            val cardId = "slack-${System.identityHashCode(browser)}"
            SwingPanel(
                background = background,
                factory = { cards },
                update = {
                    val component = browser.uiComponent
                    if (showSlackCard(cards, component, cardId)) browser.setFocus(true)
                },
                modifier = Modifier.fillMaxSize().padding(bottom = LocalTerminalBottomInset.current),
            )
        } else if (state.error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(state.preparing ?: "Opening workspace…") }
        }
    }
}

internal fun showSlackCard(cards: JPanel, component: Component, cardId: String): Boolean {
    val added = component.parent !== cards
    if (added) cards.add(component, cardId)
    if (!added && cards.getClientProperty(SHOWN_ID) == cardId) return false
    (cards.layout as CardLayout).show(cards, cardId)
    cards.putClientProperty(SHOWN_ID, cardId)
    return true
}

private fun Color.cssHex(background: Color): String {
    val color = if (isSpecified) this else background
    return "#%06x".format(color.compositeOver(background).toArgb() and 0xFFFFFF)
}
