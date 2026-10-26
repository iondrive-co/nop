package iondrive.nop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.SwingDialog
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.jewel.foundation.theme.JewelTheme
import java.awt.Dimension
import java.awt.Dialog
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlin.math.roundToInt

/** A non-focusing native window keeps the whole tooltip above heavyweight terminal components. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun TerminalTooltip(tooltip: @Composable () -> Unit, content: @Composable () -> Unit) {
    val owner = LocalWindow.current
    val density = LocalDensity.current.density
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var anchor by remember { mutableStateOf(Rect.Zero) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        shown = false
        if (hovered) {
            delay(500)
            shown = true
        }
    }
    DisposableEffect(owner) {
        val movement = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) { shown = false }
            override fun componentResized(e: ComponentEvent) { shown = false }
            override fun componentHidden(e: ComponentEvent) { shown = false }
        }
        val focus = object : WindowAdapter() {
            override fun windowDeactivated(e: WindowEvent) { shown = false }
        }
        owner?.addComponentListener(movement)
        owner?.addWindowListener(focus)
        onDispose {
            owner?.removeComponentListener(movement)
            owner?.removeWindowListener(focus)
        }
    }
    Box(
        Modifier.onGloballyPositioned { anchor = it.boundsInWindow() }
            .hoverable(interaction)
            .onPointerEvent(PointerEventType.Press) { shown = false },
    ) { content() }

    if (shown && owner?.isShowing == true) {
        SwingDialog(
            create = {
                ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
                    isUndecorated = true
                    type = Window.Type.POPUP
                    isResizable = false
                    focusableWindowState = false
                    isAutoRequestFocus = false
                }
            },
            dispose = { it.dispose() },
            update = { popup ->
                val config = owner.graphicsConfiguration
                val screen = Rectangle(config.bounds)
                val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
                screen.x += insets.left
                screen.y += insets.top
                screen.width -= insets.left + insets.right
                screen.height -= insets.top + insets.bottom
                val bounds = Rectangle(
                    owner.x + owner.insets.left + (anchor.left / density).roundToInt(),
                    owner.y + owner.insets.top + (anchor.top / density).roundToInt(),
                    (anchor.width / density).roundToInt(),
                    (anchor.height / density).roundToInt(),
                )
                // Give Compose measurement bounds, then fit and place before the native window
                // is mapped. A popup has no dialog positioning state or window-manager centring.
                if (!popup.isDisplayable) {
                    popup.preferredSize = screen.size
                    popup.pack()
                    popup.preferredSize = null
                }
                popup.pack()
                popup.location = terminalTooltipPosition(bounds, popup.size, screen)
            },
        ) {
            Box(
                Modifier.background(JewelTheme.globalColors.panelBackground)
                    .border(1.dp, if (JewelTheme.isDark) Color(0xFF55585E) else Color(0xFFB8BBC2))
                    .padding(8.dp),
            ) { tooltip() }
        }
    }
}

/** Prefer above the strip, then below it, keeping the tooltip inside the monitor's usable area. */
internal fun terminalTooltipPosition(anchor: Rectangle, size: Dimension, screen: Rectangle): Point {
    val above = anchor.y - size.height - 6
    val y = if (above >= screen.y) above else anchor.y + anchor.height + 6
    return Point(
        anchor.x.coerceIn(screen.x, (screen.x + screen.width - size.width).coerceAtLeast(screen.x)),
        y.coerceIn(screen.y, (screen.y + screen.height - size.height).coerceAtLeast(screen.y)),
    )
}
