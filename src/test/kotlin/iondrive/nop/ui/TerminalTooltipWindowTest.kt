package iondrive.nop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Text
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.EventQueue
import java.awt.Point
import java.awt.Robot
import java.awt.Window
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Native window checks run only on an explicitly isolated test display. */
@EnabledIfEnvironmentVariable(named = "NOP_NATIVE_UI_TEST", matches = "1")
class TerminalTooltipWindowTest {
    @Test
    fun `repeated hovers stay beside the chip after the owner moves and resizes`() {
        var anchor = Rect.Zero
        val host = onEdt {
            ComposeWindow().apply {
                title = "Tooltip placement test"
                setBounds(80, 60, 760, 500)
                val hostWindow = this
                setContent {
                    CompositionLocalProvider(LocalWindow provides hostWindow) {
                        IntUiTheme(isDark = false) {
                            Column(Modifier.fillMaxSize()) {
                                SwingPanel(factory = { JPanel() }, modifier = Modifier.weight(1f).fillMaxWidth())
                                Box(Modifier.padding(12.dp)) {
                                    TerminalTooltip(tooltip = {
                                        Column {
                                            Text("Example account")
                                            Text("Session: 42%, resets in 2h")
                                            Text("Weekly: 27%, resets in 4d")
                                        }
                                    }) {
                                        Box(Modifier.onGloballyPositioned { anchor = it.boundsInWindow() }) {
                                            Text("Example account")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                isVisible = true
            }
        }
        try {
            val robot = Robot()
            await { anchor.width > 0 && host.isShowing }
            repeat(12) { turn ->
                val away = onEdt { Point(host.x + host.insets.left + 50, host.y + host.insets.top + 50) }
                movePointer(robot, away)
                await { host.ownedWindows.none { it.isShowing } }
                if (turn % 3 == 0) {
                    onEdt { host.setBounds(80 + turn * 15, 60 + turn * 9, 760 + turn * 4, 500 + turn * 3) }
                    awaitStableBounds(host)
                }
                val at = onEdt {
                    Point(
                        host.x + host.insets.left + anchor.center.x.toInt(),
                        host.y + host.insets.top + anchor.center.y.toInt(),
                    )
                }
                movePointer(robot, at)
                await("hover $turn at $at") { host.ownedWindows.any { it.isShowing } }
                repeat(4) {
                    onEdt {
                        val popup = host.ownedWindows.single { it.isShowing }
                        val left = host.x + host.insets.left + anchor.left.toInt()
                        val top = host.y + host.insets.top + anchor.top.toInt()
                        assertEquals(left, popup.x, "hover $turn stays aligned to its chip")
                        assertEquals(top - popup.height - 6, popup.y, "hover $turn stays above its chip")
                        assertFalse(popup.focusableWindowState, "the tooltip leaves keyboard focus in the terminal")
                        assertTrue(popup.height > 40, "every usage row has room")
                    }
                    Thread.sleep(125)
                }
            }
        } finally {
            onEdt { host.dispose() }
        }
    }

    private fun movePointer(robot: Robot, point: Point) {
        val display = System.getenv("NOP_NATIVE_UI_INPUT_DISPLAY")
        if (display == null) {
            robot.mouseMove(point.x, point.y)
        } else {
            check(
                ProcessBuilder("xdotool", "mousemove", point.x.toString(), point.y.toString())
                    .apply { environment()["DISPLAY"] = display }
                    .start().waitFor() == 0,
            )
        }
    }

    private fun awaitStableBounds(window: Window) {
        var bounds = onEdt { window.bounds }
        var steadySince = System.nanoTime()
        await("window bounds to settle") {
            val next = window.bounds
            if (bounds != next) {
                bounds = next
                steadySince = System.nanoTime()
            }
            System.nanoTime() - steadySince >= 500_000_000L
        }
    }

    private fun await(detail: String = "the tooltip window", condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!onEdt(condition)) {
            check(System.nanoTime() < until) { "Timed out waiting for $detail" }
            Thread.sleep(25)
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
